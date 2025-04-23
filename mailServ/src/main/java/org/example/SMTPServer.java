package org.example;

import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class SMTPServer {
    private final int port = 25;
    private final String fqdnServer = "mail.example.com";
    private final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    // SMTP protocol states
    private enum State { CONNECTED, GREETED, MAIL_FROM, RCPT_TO, DATA_INPUT }

    // Command patterns
    private final Pattern heloEhloPattern = Pattern.compile("(?i)^(HELO|EHLO)\\s+(\\S+)$");
    private final Pattern mailFromPattern = Pattern.compile("(?i)^MAIL FROM:<([^<>\\s]+@[^<>\\s]+)>$");
    private final Pattern rcptToPattern = Pattern.compile("(?i)^RCPT TO:<([^<>\\s]+@[^<>\\s]+)>$");
    private final Pattern dataPattern = Pattern.compile("(?i)^DATA$");
    private final Pattern quitPattern = Pattern.compile("(?i)^QUIT$");

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("SMTP Server started on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        SMTPSession session = new SMTPSession();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            out.println("220 " + fqdnServer + " SMTP Service Ready");
            String command;
            while ((command = in.readLine()) != null) {
                if (!processCommand(command, in, out, session)) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Client handling error: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private boolean processCommand(String command, BufferedReader in, PrintWriter out, SMTPSession session) throws IOException {
        Matcher matcher;
        if ((matcher = heloEhloPattern.matcher(command)).matches()) {
            return handleHelo(matcher, out, session);
        } else if ((matcher = mailFromPattern.matcher(command)).matches()) {
            return handleMailFrom(matcher, out, session);
        } else if ((matcher = rcptToPattern.matcher(command)).matches()) {
            return handleRcptTo(matcher, out, session);
        } else if ((matcher = dataPattern.matcher(command)).matches()) {
            return handleData(in, out, session);
        } else if ((matcher = quitPattern.matcher(command)).matches()) {
            out.println("221 " + fqdnServer + " closing connection");
            return false;
        } else {
            out.println("500 Syntax error, command unrecognized");
            return true;
        }
    }

    private boolean handleHelo(Matcher matcher, PrintWriter out, SMTPSession session) {
        session.clientHost = matcher.group(2);
        session.state = State.GREETED;
        out.println("250 " + fqdnServer + " Hello " + session.clientHost);
        return true;
    }

    private boolean handleMailFrom(Matcher matcher, PrintWriter out, SMTPSession session) {
        if (session.state != State.GREETED) {
            out.println("503 Bad sequence of commands");
            return true;
        }

        String sender = matcher.group(1);
        if (!sender.endsWith("@" + fqdnServer)) {
            out.println("550 Sender not local");
            return true;
        }

        String username = sender.substring(0, sender.indexOf("@"));
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            AuthService authService = (AuthService) registry.lookup("AuthService");
            if (!authService.userExists(username)) {
                out.println("550 Sender not recognized");
                return true;
            }
        } catch (Exception e) {
            out.println("451 Authentication service unavailable");
            return true;
        }

        session.sender = sender;
        session.state = State.MAIL_FROM;
        out.println("250 Sender OK");
        return true;
    }

    private boolean handleRcptTo(Matcher matcher, PrintWriter out, SMTPSession session) {
        if (session.state != State.MAIL_FROM && session.state != State.RCPT_TO) {
            out.println("503 Bad sequence of commands");
            return true;
        }

        String recipient = matcher.group(1);
        if (!recipient.endsWith("@" + fqdnServer)) {
            out.println("550 Recipient not local");
            return true;
        }

        String username = recipient.substring(0, recipient.indexOf("@"));
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            AuthService authService = (AuthService) registry.lookup("AuthService");
            if (!authService.userExists(username)) {
                out.println("550 Recipient not found");
                return true;
            }
        } catch (Exception e) {
            out.println("451 Authentication service unavailable");
            return true;
        }

        session.recipients.add(recipient);
        session.state = State.RCPT_TO;
        out.println("250 Recipient OK");
        return true;
    }

    private boolean handleData(BufferedReader in, PrintWriter out, SMTPSession session) throws IOException {
        if (session.state != State.RCPT_TO) {
            out.println("503 Bad sequence of commands");
            return true;
        }
        out.println("354 Start mail input; end with <CRLF>.<CRLF>");

        StringBuilder emailContent = new StringBuilder();
        String line;
        while (!(line = in.readLine()).equals(".")) {
            emailContent.append(line).append("\r\n");
        }

        // Extract subject if present
        String subject = "";
        String content = emailContent.toString();
        int headerEnd = content.indexOf("\r\n\r\n");
        if (headerEnd > 0) {
            String headers = content.substring(0, headerEnd);
            for (String header : headers.split("\r\n")) {
                if (header.toLowerCase().startsWith("subject:")) {
                    subject = header.substring(8).trim();
                    break;
                }
            }
            content = content.substring(headerEnd + 4);
        }

        saveEmail(session.sender, session.recipients, subject, content);
        out.println("250 Message accepted for delivery");
        session.state = State.GREETED;
        return true;
    }

    private void saveEmail(String sender, List<String> recipients, String subject, String content) {
        String sql = "INSERT INTO emails (sender, recipient, subject, content, date_sent, is_deleted) " +
                "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, 0)";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (String recipient : recipients) {
                // Extract username part before @
                String username = recipient.substring(0, recipient.indexOf("@"));

                ps.setString(1, sender);
                ps.setString(2, username);  // Store only the username
                ps.setString(3, subject);
                ps.setString(4, content);
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            System.out.println("Saved " + Arrays.stream(results).sum() + " email records");
        } catch (SQLException e) {
            System.err.println("Failed to save email: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static class SMTPSession {
        String clientHost;
        String sender;
        List<String> recipients = new ArrayList<>();
        State state = State.CONNECTED;
    }
}