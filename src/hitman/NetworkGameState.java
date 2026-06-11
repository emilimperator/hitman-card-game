package hitman;

import java.util.*;

// This class is basically the bridge between our live Game object and the
// network. Sockets can only send text, not Java objects directly, so we need
// to convert the game state into a string that can travel through the socket,
// and then convert it back on the other side.
//
// I went with a plain text format using "|" to separate fields because it's
// way easier to debug than something like Java serialization — if something
// breaks I can just print the string and read it.
public class NetworkGameState {

    // ===== Turning the Game into a string (for the server to send) =====

    // The format ends up looking like:
    //   deckSize|currentPlayerIndex|player0Info|player1Info|lastLog|hitmanStatus
    //
    // Example:
    //   21|0|Emil:true:0:Angel,Future,Skip|Alan:true:0:Bomb,Stop|Emil drew a card.|NONE
    public static String serialize(Game game, String lastLog, String hitmanPos) {
        StringBuilder sb = new StringBuilder();

        // First we add the deck size
        sb.append(game.getDeckSize());
        sb.append("|");

        // Then whose turn it is right now
        sb.append(game.currentPlayerIndex);
        sb.append("|");

        // Then each player's info
        for (int i = 0; i < game.players.size(); i++) {
            Player p = game.players.get(i);
            sb.append(serializePlayer(p));
            sb.append("|");
        }

        // The last log message — but first we need to clean it
        // If someone's name had a "|" in it (or the log message did) it would
        // mess up our format completely, so we replace any "|" with a space
        sb.append(lastLog.replace("|", " "));
        sb.append("|");

        // Finally a flag for whether we're waiting for the Angel to place a Hitman
        sb.append(hitmanPos);

        return sb.toString();
    }

    // Helper to turn one player into a string
    // Format: name:alive:extraTurns:card1,card2,card3
    // Example: Emil:true:0:Angel,Skip,Future
    private static String serializePlayer(Player p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getName()).append(":");
        sb.append(p.isAlive()).append(":");
        sb.append(p.hasExtraTurn() ? "1" : "0").append(":");

        // Cards in hand, separated by commas
        // If the hand is empty we just leave it blank after the last colon
        ArrayList<Card> hand = p.getHand();
        for (int i = 0; i < hand.size(); i++) {
            sb.append(hand.get(i).getName());
            if (i < hand.size() - 1) sb.append(","); // no comma after the last one
        }
        return sb.toString();
    }


    // ===== Turning the string back into something usable (for the client) =====

    // We don't want to rebuild a full Game object on the client side because
    // then the client could accidentally run game logic and the two computers
    // would get out of sync. So instead we just store the data in this simple
    // class and the GUI uses it directly to update the screen.
    public static class ParsedState {
        public int deckSize;
        public int currentPlayerIndex;
        public String[] playerNames;
        public boolean[] playerAlive;
        public int[] playerExtraTurns;        // just 0 or 1, we only care if any exist
        public ArrayList<String>[] playerHands;
        public String lastLog;
        public String hitmanPos;              // either "NONE" or "PENDING"
        public int playerCount;
    }

    // Takes the string sent by the server and turns it back into something we can use
    @SuppressWarnings("unchecked")
    public static ParsedState deserialize(String stateStr) {
        // Split on "|" — the -1 makes sure we keep empty fields at the end
        // (without -1, trailing empty strings would be dropped which would mess things up)
        String[] fields = stateStr.split("\\|", -1);

        ParsedState ps = new ParsedState();

        // First field is deck size
        ps.deckSize = Integer.parseInt(fields[0].trim());

        // Second is whose turn it is
        ps.currentPlayerIndex = Integer.parseInt(fields[1].trim());

        // The fields in the middle are players. The last two are always
        // the log message and hitman status, so everything in between is players.
        // We have 4 "fixed" fields (deckSize, currentIndex, lastLog, hitmanPos)
        // so player count is total fields minus 4.
        ps.playerCount = fields.length - 4;
        ps.playerNames      = new String[ps.playerCount];
        ps.playerAlive      = new boolean[ps.playerCount];
        ps.playerExtraTurns = new int[ps.playerCount];
        ps.playerHands      = new ArrayList[ps.playerCount];

        // Loop through each player and parse their info
        for (int i = 0; i < ps.playerCount; i++) {
            // Each player looks like: name:alive:extraTurns:card1,card2,...
            String[] parts = fields[2 + i].split(":", -1);

            ps.playerNames[i]      = parts[0];
            ps.playerAlive[i]      = Boolean.parseBoolean(parts[1]);
            ps.playerExtraTurns[i] = Integer.parseInt(parts[2]);

            // Now the cards in their hand
            ps.playerHands[i] = new ArrayList<>();
            if (parts.length > 3 && !parts[3].isEmpty()) {
                String[] cards = parts[3].split(",");
                for (String card : cards) {
                    ps.playerHands[i].add(card.trim());
                }
            }
        }

        // Second to last field is the log message
        ps.lastLog = fields[fields.length - 2];

        // Last field is the hitman status
        ps.hitmanPos = fields[fields.length - 1];

        return ps;
    }
}