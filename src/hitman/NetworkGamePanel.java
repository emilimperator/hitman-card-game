package hitman;

import java.awt.*;
import java.util.*;
import javax.swing.*;

public class NetworkGamePanel extends JPanel implements GameClient.MessageListener {

    private MyFrame    frame;
    private GameClient client;
    private int        myIndex;

    private JLabel    currentPlayerLabel;
    private JLabel    deckSizeLabel;
    private JLabel    lastCardLabel;
    private JLabel    opponentInfoLabel;
    private JPanel    handPanel;
    private JTextArea gameLog;
    private JButton   drawBtn;

    private NetworkGameState.ParsedState lastState = null;
    private volatile boolean angelPending = false;

    public NetworkGamePanel(MyFrame frame, GameClient client, int myIndex) {
        this.frame   = frame;
        this.client  = client;
        this.myIndex = myIndex;

        setLayout(new BorderLayout(5, 5));
        setBackground(new Color(34, 85, 34));

        JPanel topPanel = new JPanel(new GridLayout(3, 1));
        topPanel.setBackground(new Color(20, 60, 20));
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        currentPlayerLabel = new JLabel("Connecting…", SwingConstants.CENTER);
        currentPlayerLabel.setForeground(Color.WHITE);
        currentPlayerLabel.setFont(new Font("Arial", Font.BOLD, 18));

        deckSizeLabel = new JLabel("", SwingConstants.CENTER);
        deckSizeLabel.setForeground(new Color(200, 200, 200));
        deckSizeLabel.setFont(new Font("Arial", Font.PLAIN, 14));

        opponentInfoLabel = new JLabel("", SwingConstants.CENTER);
        opponentInfoLabel.setForeground(new Color(255, 200, 100));
        opponentInfoLabel.setFont(new Font("Arial", Font.PLAIN, 13));

        topPanel.add(currentPlayerLabel);
        topPanel.add(deckSizeLabel);
        topPanel.add(opponentInfoLabel);
        add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.setBackground(new Color(34, 85, 34));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        lastCardLabel = new JLabel("Last card played: None", SwingConstants.CENTER);
        lastCardLabel.setForeground(Color.WHITE);
        lastCardLabel.setFont(new Font("Arial", Font.ITALIC, 14));
        centerPanel.add(lastCardLabel, BorderLayout.NORTH);

        gameLog = new JTextArea(8, 30);
        gameLog.setEditable(false);
        gameLog.setBackground(new Color(20, 50, 20));
        gameLog.setForeground(new Color(180, 255, 180));
        gameLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        gameLog.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        JScrollPane logScroll = new JScrollPane(gameLog);
        logScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(100, 180, 100)),
            "Game Log", 0, 0,
            new Font("Arial", Font.BOLD, 12), Color.WHITE));
        centerPanel.add(logScroll, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBackground(new Color(20, 60, 20));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel handLabel = new JLabel("Your Hand:", SwingConstants.CENTER);
        handLabel.setForeground(Color.WHITE);
        handLabel.setFont(new Font("Arial", Font.BOLD, 13));
        bottomPanel.add(handLabel, BorderLayout.NORTH);

        handPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 5));
        handPanel.setBackground(new Color(20, 60, 20));
        JScrollPane handScroll = new JScrollPane(handPanel);
        handScroll.setPreferredSize(new Dimension(400, 100));
        handScroll.setBackground(new Color(20, 60, 20));
        handScroll.setBorder(null);
        bottomPanel.add(handScroll, BorderLayout.CENTER);

        drawBtn = new JButton("Draw Card");
        drawBtn.setFont(new Font("Arial", Font.BOLD, 16));
        drawBtn.setBackground(new Color(180, 30, 30));
        drawBtn.setForeground(Color.WHITE);
        drawBtn.setFocusPainted(false);
        drawBtn.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        drawBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        drawBtn.setEnabled(false);
        drawBtn.addActionListener(e -> client.send("DRAW"));
        bottomPanel.add(drawBtn, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    @Override
    public void onMessage(String message) {
        if (message.startsWith("STATE:")) {
            String stateStr = message.substring(6);
            NetworkGameState.ParsedState ps = NetworkGameState.deserialize(stateStr);
            SwingUtilities.invokeLater(() -> applyState(ps));

        } else if (message.startsWith("PEEK:")) {
            String cards = message.substring(5);
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this,
                    "Top 3 cards of the deck:\n" + cards.replace(",", "\n"),
                    "Future — Peek",
                    JOptionPane.INFORMATION_MESSAGE));

        } else if (message.equals("QUERY_ANGEL")) {
            if (lastState != null && lastState.currentPlayerIndex == myIndex) {
                SwingUtilities.invokeLater(this::showAngelDialog);
            }

        } else if (message.startsWith("QUERY_STOP:")) {
            String actionName = message.split(":")[1];
            SwingUtilities.invokeLater(() -> {
                int resp = JOptionPane.showConfirmDialog(this,
                    "Your opponent played " + actionName + "!\nPlay Stop to cancel?",
                    "Play Stop?",
                    JOptionPane.YES_NO_OPTION);
                client.send("STOP_RESPONSE:" + (resp == JOptionPane.YES_OPTION ? "YES" : "NO"));
            });

        // this handles when the opponent uses take card on us
        } else if (message.startsWith("QUERY_GIVE:")) {
            String actorName = message.substring(11);
            SwingUtilities.invokeLater(() -> {
                ArrayList<String> myHand = lastState.playerHands[myIndex];
                if (myHand.isEmpty()) {
                    client.send("GIVE_RESPONSE:NONE");
                    return;
                }
                String[] cardArr = myHand.toArray(new String[0]);
                String chosen = (String) JOptionPane.showInputDialog(
                    this,
                    actorName + " played Take Card!\nChoose a card to give them:",
                    "Give Card",
                    JOptionPane.QUESTION_MESSAGE,
                    null, cardArr, cardArr[0]);
                
                // fallback just in case someone clicks cancel so it doesn't freeze
                if (chosen == null) chosen = cardArr[0];
                client.send("GIVE_RESPONSE:" + chosen);
            });

        } else if (message.startsWith("WINNER:")) {
            String winner = message.substring(7);
            SwingUtilities.invokeLater(() ->
                frame.showPanel(new GameOverPanel(frame, winner)));

        } else if (message.startsWith("ELIMINATED:")) {
            String eliminated = message.substring(11);
            SwingUtilities.invokeLater(() -> log(eliminated + " was eliminated!"));

        } else if (message.equals("DISCONNECTED")) {
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this,
                    "Connection lost. Returning to main menu.",
                    "Disconnected",
                    JOptionPane.ERROR_MESSAGE));
            SwingUtilities.invokeLater(() -> frame.showPanel(new MainMenuPanel(frame)));
        }
    }

    private void applyState(NetworkGameState.ParsedState ps) {
        lastState = ps;

        boolean isMyTurn = (ps.currentPlayerIndex == myIndex);

        String currentName = ps.playerNames[ps.currentPlayerIndex];
        String turnText = isMyTurn
            ? "YOUR TURN — " + ps.playerNames[myIndex].toUpperCase()
            : "Waiting for " + currentName.toUpperCase() + "…";
        currentPlayerLabel.setText(turnText);
        currentPlayerLabel.setForeground(isMyTurn ? new Color(100, 255, 100) : Color.WHITE);

        deckSizeLabel.setText("Deck: " + ps.deckSize + " cards remaining");

        int opponentIndex = (myIndex == 0) ? 1 : 0;
        if (opponentIndex < ps.playerCount) {
            String opName   = ps.playerNames[opponentIndex];
            int    opCards  = ps.playerHands[opponentIndex].size();
            boolean opAlive = ps.playerAlive[opponentIndex];
            opponentInfoLabel.setText(
                opName + ": " + opCards + " card(s)" + (opAlive ? "" : " — ELIMINATED"));
        }

        if (!ps.lastLog.isEmpty()) {
            log(ps.lastLog);
        }

        handPanel.removeAll();
        ArrayList<String> myHand = ps.playerHands[myIndex];
        Collections.sort(myHand);
        for (String cardName : myHand) {
            JButton cardBtn = createCardButton(cardName, isMyTurn);
            handPanel.add(cardBtn);
        }
        handPanel.revalidate();
        handPanel.repaint();

        drawBtn.setEnabled(isMyTurn && !angelPending);
    }

    private JButton createCardButton(String cardName, boolean isMyTurn) {
        JButton btn = new JButton("<html><center>" + cardName + "</center></html>");
        btn.setPreferredSize(new Dimension(80, 70));
        btn.setOpaque(true);
        btn.setBorderPainted(true);
        btn.setBackground(getCardColor(cardName));
        btn.setForeground(Color.BLACK);
        btn.setFont(new Font("Arial", Font.BOLD, 11));
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createLineBorder(Color.BLACK, 2));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setEnabled(isMyTurn);
        btn.addActionListener(e -> handleCardPlay(cardName));
        return btn;
    }

    private void handleCardPlay(String cardName) {
        if (lastState == null) return;

        switch (cardName) {
            case "Skip":
                client.send("PLAY:Skip");
                break;
            case "Shuffle":
                client.send("PLAY:Shuffle");
                break;
            case "Future":
                client.send("PLAY:Future");
                break;
            case "Take Bottom":
                client.send("DRAW_BOTTOM");
                break;
            case "Bomb": {
                String target = selectOpponent("Select Bomb target:");
                if (target != null) client.send("PLAY:Bomb:" + target);
                break;
            }
            case "Take Card": {
                String target = selectOpponent("Select Take Card target:");
                if (target == null) break;
                // just tell the server we are playing it, the server will ask them for the card
                client.send("PLAY:TakeCard:" + target);
                break;
            }
            case "Stop":
                log("Stop is played reactively — wait for an opponent's action.");
                break;
            case "Angel":
                log("Angel activates automatically when you draw Hitman.");
                break;
            default:
                log("Unknown card: " + cardName);
        }
    }

    private void showAngelDialog() {
        angelPending = true;
        drawBtn.setEnabled(false);

        int deckSize = (lastState != null) ? lastState.deckSize : 1;
        String input = JOptionPane.showInputDialog(this,
            "You were saved by Angel!\nDeck has " + deckSize + " cards.\n" +
            "Where to place Hitman?\n1 = top, " + (deckSize + 1) + " = bottom",
            "Place Hitman",
            JOptionPane.QUESTION_MESSAGE);

        int position;
        try {
            position = (input != null) ? Integer.parseInt(input.trim()) : 0;
        } catch (NumberFormatException e) {
            position = 0;
        }

        client.send("ANGEL_PLACE:" + position);
        angelPending = false;
    }

    private String selectOpponent(String prompt) {
        if (lastState == null) return null;
        int opIdx = getOpponentIndex();
        if (!lastState.playerAlive[opIdx]) {
            log("No valid target.");
            return null;
        }
        return lastState.playerNames[opIdx];
    }

    private int getOpponentIndex() {
        return (myIndex == 0) ? 1 : 0;
    }

    private Color getCardColor(String cardName) {
        switch (cardName) {
            case "Hitman":      return new Color(180, 30, 30);
            case "Angel":       return new Color(255, 215, 0);
            case "Skip":        return new Color(30, 100, 200);
            case "Future":      return new Color(130, 40, 180);
            case "Bomb":        return new Color(200, 100, 0);
            case "Take Card":   return new Color(0, 150, 130);
            case "Stop":        return new Color(180, 0, 100);
            case "Shuffle":     return new Color(0, 150, 80);
            case "Take Bottom": return new Color(100, 80, 40);
            default:            return new Color(80, 80, 80);
        }
    }

    private void log(String message) {
        gameLog.append("→ " + message + "\n");
        gameLog.setCaretPosition(gameLog.getDocument().getLength());
    }
}