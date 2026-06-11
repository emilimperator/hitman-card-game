package hitman;

import java.io.*;
import java.net.*;
import java.util.*;

public class GameServer {

    private static final int PORT = 8888;
    private ClientConnection[] clients = new ClientConnection[2];
    private Game game;
    private final Object gameLock = new Object();

    private volatile boolean hitmanPending = false;
    private volatile Card pendingHitman    = null;

    public void start(String hostPlayerName, ServerReadyCallback onBothConnected) {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                System.out.println("[Server] Listening on port " + PORT);

                Socket s0 = serverSocket.accept();
                clients[0] = new ClientConnection(s0, 0);
                System.out.println("[Server] Player 1 connected.");

                Socket s1 = serverSocket.accept();
                clients[1] = new ClientConnection(s1, 1);
                System.out.println("[Server] Player 2 connected.");

                String name0 = clients[0].readLine();
                String name1 = clients[1].readLine();

                clients[0].send("YOUR_INDEX:0");
                clients[1].send("YOUR_INDEX:1");

                ArrayList<Player> players = new ArrayList<>();
                players.add(new Player(name0));
                players.add(new Player(name1));
                game = new Game(players);

                System.out.println("[Server] Game created: " + name0 + " vs " + name1);

                if (onBothConnected != null) {
                    onBothConnected.onReady();
                }

                broadcastState("Game started! " + name0 + "'s turn.");

                new Thread(() -> readLoop(0)).start();
                new Thread(() -> readLoop(1)).start();

            } catch (IOException e) {
                System.out.println("[Server] Error: " + e.getMessage());
            }
        }).start();
    }

    private void readLoop(int playerIndex) {
        try {
            String line;
            while ((line = clients[playerIndex].readLine()) != null) {
                System.out.println("[Server] From player " + playerIndex + ": " + line);
                synchronized (gameLock) {
                    handleMessage(line, playerIndex);
                }
            }
        } catch (IOException e) {
            System.out.println("[Server] Player " + playerIndex + " disconnected: " + e.getMessage());
        }
    }

    private void handleMessage(String msg, int fromIndex) {
        String[] parts = msg.split(":", -1);
        String action  = parts[0].trim();
        Player actor   = game.players.get(fromIndex);

        if (!actor.isAlive()) return;

        switch (action) {

            case "DRAW": {
                if (game.currentPlayerIndex != fromIndex) return; 
                try {
                    Card drawn = game.drawCard();
                    if (drawn != null && drawn.getName().equals("Hitman")) {
                        hitmanPending = true;
                        pendingHitman = drawn;
                        broadcast("QUERY_ANGEL");
                        broadcastState(actor.getName() + " was saved by Angel! Place the Hitman.");
                    } else {
                        if (!actor.isAlive()) {
                            broadcastElimination(actor.getName());
                        } else {
                            checkWinnerAndAdvance(actor.getName() + " drew a card.");
                        }
                    }
                } catch (EmptyDeckException e) {
                    broadcast("STATE:" + NetworkGameState.serialize(game, "Deck is empty!", "NONE"));
                }
                break;
            }

            case "ANGEL_PLACE": {
                if (!hitmanPending) return;
                int position = 0;
                try {
                    position = Integer.parseInt(parts[1].trim()) - 1;
                } catch (NumberFormatException e) {
                    position = -1;
                }
                if (position < 0) {
                    game.getDeck().insertAtRandom(pendingHitman);
                } else {
                    game.getDeck().insertAt(position, pendingHitman);
                }
                hitmanPending = false;
                pendingHitman = null;
                checkWinnerAndAdvance(actor.getName() + " placed the Hitman back in the deck.");
                break;
            }

            case "PLAY": {
                if (game.currentPlayerIndex != fromIndex) return;
                String cardName = parts[1].trim();
                switch (cardName) {

                    case "Skip": {
                        try { actor.playCard(new SkipCard()); } catch (InvalidPlayException e) { return; }
                        game.skipTurn();
                        broadcastState(actor.getName() + " played Skip.");
                        break;
                    }

                    case "Shuffle": {
                        try { actor.playCard(new ShuffleCard()); } catch (InvalidPlayException e) { return; }
                        game.shuffleDeck();
                        broadcastState(actor.getName() + " shuffled the deck.");
                        break;
                    }

                    case "Future": {
                        try { actor.playCard(new FutureCard()); } catch (InvalidPlayException e) { return; }
                        
                        Player opponent = getOpponent(actor);
                        if (opponent != null && targetHasStop(opponent)) {
                            int oppIndex = getPlayerIndex(opponent);
                            clients[oppIndex].send("QUERY_STOP:Future");
                            pendingBombActor  = actor;
                            pendingBombTarget = opponent;
                            pendingAction     = "Future";
                            broadcastState(actor.getName() + " played Future...");
                        } else {
                            applyFutureLogic(actor);
                        }
                        break;
                    }

                    case "Bomb": {
                        if (parts.length < 3) return;
                        try { actor.playCard(new BombCard()); } catch (InvalidPlayException e) { return; }
                        
                        String targetName = parts[2].trim();
                        Player target = getPlayerByName(targetName);
                        if (target == null || !target.isAlive()) return;

                        int targetIndex = getPlayerIndex(target);
                        if (targetHasStop(target)) {
                            clients[targetIndex].send("QUERY_STOP:Bomb");
                            pendingBombActor  = actor;
                            pendingBombTarget = target;
                            pendingAction     = "Bomb";
                            broadcastState(actor.getName() + " played Bomb...");
                        } else {
                            applyBombLogic(actor, target);
                        }
                        break;
                    }

                    case "TakeCard": {
                        if (parts.length < 3) return;
                        try { actor.playCard(new TakeCard()); } catch (InvalidPlayException e) { return; }
                        
                        String targetName = parts[2].trim();
                        Player target = getPlayerByName(targetName);
                        if (target == null || !target.isAlive()) return;

                        int targetIndex = getPlayerIndex(target);
                        
                        // save who is stealing from who so we can process it later
                        pendingBombActor    = actor;
                        pendingBombTarget   = target;
                        pendingAction       = "TakeCard";
                        
                        if (targetHasStop(target)) {
                            clients[targetIndex].send("QUERY_STOP:TakeCard");
                            broadcastState(actor.getName() + " played Take Card...");
                        } else {
                            // no stop card, skip straight to asking them for the card
                            clients[targetIndex].send("QUERY_GIVE:" + actor.getName());
                            broadcastState(actor.getName() + " played Take Card...");
                        }
                        break;
                    }
                }
                break;
            }

            case "STOP_RESPONSE": {
                String answer = parts[1].trim();
                if (answer.equals("YES") && pendingBombTarget != null) {
                    try { pendingBombTarget.playCard(new StopCard()); } catch (InvalidPlayException e) {}
                    broadcastState(pendingBombTarget.getName() + " played Stop — action cancelled!");
                    pendingBombActor  = null;
                    pendingBombTarget = null;
                    pendingAction     = null;
                } else {
                    if ("Bomb".equals(pendingAction)) {
                        applyBombLogic(pendingBombActor, pendingBombTarget);
                        pendingBombActor  = null;
                        pendingBombTarget = null;
                        pendingAction     = null;
                    } else if ("TakeCard".equals(pendingAction)) {
                        // target didn't stop it, ask them what card they want to give up
                        int targetIdx = getPlayerIndex(pendingBombTarget);
                        clients[targetIdx].send("QUERY_GIVE:" + pendingBombActor.getName());
                    } else if ("Future".equals(pendingAction)) {
                        applyFutureLogic(pendingBombActor);
                        pendingBombActor  = null;
                        pendingBombTarget = null;
                        pendingAction     = null;
                    }
                }
                break;
            }
            
            // new listener just for when the opponent selects the card
            case "GIVE_RESPONSE": {
                if (!"TakeCard".equals(pendingAction) || pendingBombTarget == null || pendingBombActor == null) return;
                
                String cardToGive = parts[1].trim();
                if ("NONE".equals(cardToGive)) {
                    broadcastState(pendingBombTarget.getName() + " had no cards to give.");
                } else {
                    applyTakeCardLogic(pendingBombActor, pendingBombTarget, cardToGive);
                }
                
                pendingBombActor  = null;
                pendingBombTarget = null;
                pendingAction     = null;
                break;
            }

            case "DRAW_BOTTOM": {
                if (game.currentPlayerIndex != fromIndex) return;
                
                try { actor.playCard(new TakeBottomCard()); } catch (InvalidPlayException e) { return; }

                try {
                    Card bottom = game.drawFromBottom();
                    if (bottom.getName().equals("Hitman")) {
                        if (actor.hasAngel()) {
                            actor.removeAngel();
                            hitmanPending = true;
                            pendingHitman = bottom;
                            broadcast("QUERY_ANGEL");
                            broadcastState(actor.getName() + " drew Hitman from bottom — Angel saved!");
                        } else {
                            actor.eliminate();
                            actor.getHand().clear();
                            broadcastElimination(actor.getName());
                        }
                    } else {
                        actor.addCard(bottom);
                        checkWinnerAndAdvance(actor.getName() + " drew from the bottom.");
                    }
                } catch (EmptyDeckException e) {
                    broadcastState("Deck is empty!");
                }
                break;
            }
        }
    }

    private Player pendingBombActor  = null;
    private Player pendingBombTarget = null;
    private String pendingAction     = null;

    private void applyBombLogic(Player actor, Player target) {
        game.bombPlayer(target, 2);
        game.forceTurn(target);
        broadcastState(actor.getName() + " bombed " + target.getName() + "! They take 2 turns.");
    }

    private void applyTakeCardLogic(Player actor, Player target, String cardName) {
        Card toGive = target.getHand().stream()
            .filter(c -> c.getName().equals(cardName))
            .findFirst().orElse(null);
        if (toGive != null) {
            target.removeCard(toGive);
            actor.addCard(toGive);
            broadcastState(target.getName() + " gave " + cardName + " to " + actor.getName() + ".");
        } else {
            broadcastState(target.getName() + " no longer has that card.");
        }
    }
    
    private void applyFutureLogic(Player actor) {
        ArrayList<Card> peeked = game.peekTop3();
        StringBuilder peek = new StringBuilder("PEEK:");
        for (int i = 0; i < peeked.size(); i++) {
            peek.append(peeked.get(i).getName());
            if (i < peeked.size() - 1) peek.append(",");
        }
        int actorIndex = getPlayerIndex(actor);
        clients[actorIndex].send(peek.toString());
        broadcastState(actor.getName() + " peeked at the top 3 cards.");
    }

    private void checkWinnerAndAdvance(String logMessage) {
        Player winner = game.checkWinner();
        if (winner != null) {
            broadcast("WINNER:" + winner.getName());
            broadcast("STATE:" + NetworkGameState.serialize(game, winner.getName() + " wins!", "NONE"));
        } else {
            game.nextTurn();
            broadcastState(logMessage);
        }
    }

    private void broadcastElimination(String name) {
        broadcast("ELIMINATED:" + name);
        Player winner = game.checkWinner();
        if (winner != null) {
            broadcast("WINNER:" + winner.getName());
        }
        broadcastState(name + " was eliminated!");
    }

    private void broadcastState(String logMessage) {
        String state = "STATE:" + NetworkGameState.serialize(game, logMessage, hitmanPending ? "PENDING" : "NONE");
        broadcast(state);
    }

    private void broadcast(String message) {
        for (ClientConnection cc : clients) {
            if (cc != null) cc.send(message);
        }
    }

    private Player getPlayerByName(String name) {
        return game.players.stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
    }
    
    private Player getOpponent(Player p) {
        return game.players.stream().filter(op -> op != p && op.isAlive()).findFirst().orElse(null);
    }

    private int getPlayerIndex(Player p) {
        for (int i = 0; i < game.players.size(); i++) {
            if (game.players.get(i) == p) return i;
        }
        return -1;
    }

    private boolean targetHasStop(Player target) {
        return target.getHand().stream().anyMatch(c -> c.getName().equals("Stop"));
    }

    public interface ServerReadyCallback {
        void onReady();
    }

    private static class ClientConnection {
        private final Socket socket;
        private final BufferedReader reader;
        private final PrintWriter writer;
        final int index;

        ClientConnection(Socket socket, int index) throws IOException {
            this.socket = socket;
            this.index  = index;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        }

        String readLine() throws IOException {
            return reader.readLine();
        }

        void send(String message) {
            writer.println(message);
        }
    }
}