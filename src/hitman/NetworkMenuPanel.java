package hitman;

import java.awt.*;
import javax.swing.*;

// This is the screen where you pick whether you want to host a game or join one.
//
// Hosting flow:
//   - You enter your name and click "Host Game"
//   - We start the server in the background (it waits for 2 players to connect)
//   - Screen shows "Waiting for opponent..."
//   - Once the second player joins, we connect ourselves as player 1 and go to the game
//
// Joining flow:
//   - You enter your name + the host's IP address
//   - We connect to that IP
//   - We send our name, get assigned a player slot, and go to the game
//
// Why do I use readOnce() instead of starting the listener right away?
//   The listener spawns a background thread that reads everything from the server
//   in an infinite loop. If we started it before getting the YOUR_INDEX message,
//   that thread would eat the message before we could see it. So we read one line
//   manually first (readOnce), then start the listener for everything after that.
public class NetworkMenuPanel extends JPanel {

    // The port number we use for the server. 8888 is just a random unused port.
    private static final int PORT = 8888;
    private MyFrame frame;

    public NetworkMenuPanel(MyFrame frame) {
        this.frame = frame;

        // Dark green background to match the rest of the game
        setBackground(new Color(20, 60, 20));
        setLayout(new BorderLayout(10, 10));

        // ===== Title at the top =====
        JLabel title = new JLabel("Network Game", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 28));
        title.setForeground(new Color(255, 215, 0)); // gold color
        title.setBorder(BorderFactory.createEmptyBorder(30, 0, 10, 0));

        // ===== Input fields for name and IP =====
        // Using a grid layout so the labels and text fields line up nicely
        JPanel inputPanel = new JPanel(new GridLayout(2, 2, 8, 8));
        inputPanel.setBackground(new Color(20, 60, 20));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 40, 10, 40));

        JLabel nameLabel = new JLabel("Your Name:", SwingConstants.RIGHT);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(new Font("Arial", Font.PLAIN, 14));
        JTextField nameField = new JTextField();

        // The IP field is only needed if you're joining someone else's game.
        // For hosting we just leave it as "localhost" which means "this computer"
        JLabel ipLabel = new JLabel("Host IP (join only):", SwingConstants.RIGHT);
        ipLabel.setForeground(Color.WHITE);
        ipLabel.setFont(new Font("Arial", Font.PLAIN, 14));
        JTextField ipField = new JTextField("localhost");

        inputPanel.add(nameLabel); inputPanel.add(nameField);
        inputPanel.add(ipLabel);   inputPanel.add(ipField);

        // ===== Status label =====
        // This shows messages like "Waiting for opponent..." or error messages
        JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
        statusLabel.setForeground(new Color(180, 255, 180));
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 13));

        // ===== Three buttons at the bottom =====
        JPanel buttonPanel = new JPanel(new GridLayout(1, 3, 10, 10));
        buttonPanel.setBackground(new Color(20, 60, 20));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 40, 20, 40));

        JButton hostBtn = makeButton("Host Game", new Color(30, 100, 200));
        JButton joinBtn = makeButton("Join Game",  new Color(0, 150, 80));
        JButton backBtn = makeButton("Back",        new Color(180, 30, 30));

        buttonPanel.add(hostBtn);
        buttonPanel.add(joinBtn);
        buttonPanel.add(backBtn);

        // Stack the input fields, status label, and buttons vertically in the center
        JPanel centerPanel = new JPanel(new GridLayout(3, 1, 5, 5));
        centerPanel.setBackground(new Color(20, 60, 20));
        centerPanel.add(inputPanel);
        centerPanel.add(statusLabel);
        centerPanel.add(buttonPanel);

        add(title,       BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);

        // ===== Host Game button =====
        hostBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { error("Please enter your name."); return; }

            // Disable buttons while we're setting up so the user doesn't click twice
            hostBtn.setEnabled(false);
            joinBtn.setEnabled(false);
            statusLabel.setText("Waiting for opponent on port " + PORT + "…");

            GameServer server = new GameServer();

            // We do all the network stuff on a background thread so the GUI doesn't freeze
            new Thread(() -> {
                try {
                    // Step 1: Start the server
                    // It internally spawns its own thread to listen for connections
                    server.start(name, null);

                    // Step 2: Wait a tiny bit for the server to be fully ready
                    // Without this delay, the next line might run before the
                    // server has actually opened its socket. 300ms is plenty.
                    Thread.sleep(300);

                    // Step 3: Connect ourselves as the first player
                    // "localhost" means we're connecting to a server on our own computer
                    GameClient client = new GameClient();
                    client.connect("localhost", PORT);
                    client.send(name); // tell the server our name

                    // Step 4: Get our player slot from the server
                    // The server sends "YOUR_INDEX:0" or "YOUR_INDEX:1" right after we connect
                    String indexLine = client.readOnce();
                    int myIndex = parseIndex(indexLine);
                    client.setMyIndex(myIndex);

                    // Step 5: Switch to the game screen
                    // invokeLater makes sure GUI changes happen on the main GUI thread,
                    // not on this background thread (Swing requires this)
                    SwingUtilities.invokeLater(() -> {
                        NetworkGamePanel ngp = new NetworkGamePanel(frame, client, myIndex);
                        client.setListener(ngp);
                        client.startListening(); // now safe to start the listener
                        frame.showPanel(ngp);
                    });

                } catch (Exception ex) {
                    // Something went wrong — show the error and re-enable the buttons
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Error starting host: " + ex.getMessage());
                        hostBtn.setEnabled(true);
                        joinBtn.setEnabled(true);
                    });
                }
            }).start();
        });

        // ===== Join Game button =====
        joinBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            String ip   = ipField.getText().trim();

            // Make sure both name and IP are filled in
            if (name.isEmpty()) { error("Please enter your name."); return; }
            if (ip.isEmpty())   { error("Please enter the host's IP address."); return; }

            hostBtn.setEnabled(false);
            joinBtn.setEnabled(false);
            statusLabel.setText("Connecting to " + ip + ":" + PORT + "…");

            // Same as hosting — do the network stuff on a background thread
            new Thread(() -> {
                try {
                    // Connect to the host's IP
                    GameClient client = new GameClient();
                    client.connect(ip, PORT);
                    client.send(name); // send our name

                    // Get our player slot (should be 1 since we're joining second)
                    String indexLine = client.readOnce();
                    int myIndex = parseIndex(indexLine);
                    client.setMyIndex(myIndex);

                    // Switch to game screen on the GUI thread
                    SwingUtilities.invokeLater(() -> {
                        NetworkGamePanel ngp = new NetworkGamePanel(frame, client, myIndex);
                        client.setListener(ngp);
                        client.startListening();
                        frame.showPanel(ngp);
                    });

                } catch (Exception ex) {
                    // Most common error here is "Connection refused" which means
                    // either the host hasn't clicked Host Game yet, or we typed the wrong IP
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Could not connect: " + ex.getMessage());
                        hostBtn.setEnabled(true);
                        joinBtn.setEnabled(true);
                    });
                }
            }).start();
        });

        // Back button just takes us to the main menu
        backBtn.addActionListener(e -> frame.showPanel(new MainMenuPanel(frame)));
    }

    // ===== Helper methods =====

    // Just a shortcut for making the buttons all look the same
    private JButton makeButton(String label, Color bg) {
        JButton btn = new JButton(label);
        btn.setBackground(bg);
        btn.setForeground(Color.BLACK);
        btn.setFont(new Font("Arial", Font.BOLD, 14));
        btn.setOpaque(true);           // Mac needs this otherwise the background color doesn't show
        btn.setFocusPainted(false);    // hides that ugly focus rectangle
        return btn;
    }

    // Pops up an error dialog
    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    // Takes a string like "YOUR_INDEX:0" and pulls out the number
    // Returns 0 as a safe default if something's wrong with the message
    private int parseIndex(String msg) {
        if (msg != null && msg.startsWith("YOUR_INDEX:")) {
            try {
                return Integer.parseInt(msg.split(":")[1].trim());
            }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}