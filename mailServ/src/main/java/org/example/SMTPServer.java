package org.example;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.rmi.registry.*;

public class SMTPServer {
    // Server configuration
    private final int port = 25;
    private final Path mailDir = Paths.get("MailServer");
    private final String fqdnServer = "mail.example.com";
    private final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    // SMTP protocol states
    private enum State { CONNECTED, GREETED, MAIL_FROM, RCPT_TO, DATA_INPUT }

    // Command patterns
    private final Pattern heloEhloPattern = Pattern.compile("(?i)^(HELO|EHLO)\\s+(\\S+)$");
    private final Pattern mailFromPattern = Pattern.compile("(?i)^MAIL FROM:<([^<>\\s]+@[^<>\\s]+)>$");
    private final Pattern rcptToPattern = Pattern.compile("(?i)^RCPT TO:<([^<>\\s]+@[^<>\\s]+)>$");
    private final Pattern dataPattern = Pattern.compile("(?i)^DATA$");
    private final Pattern rsetPattern = Pattern.compile("(?i)^RSET$");
    private final Pattern noopPattern = Pattern.compile("(?i)^NOOP$");
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
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
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
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
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
        } else if ((matcher = rsetPattern.matcher(command)).matches()) {
            return handleRset(out, session);
        } else if ((matcher = noopPattern.matcher(command)).matches()) {
            out.println("250 OK");
            return true;
        } else if ((matcher = quitPattern.matcher(command)).matches()) {
            out.println("221 " + fqdnServer + " closing connection");
            return false;
        } else {
            out.println("500 Syntax error, command unrecognized");
            return true;
        }
    }

    private boolean handleHelo(Matcher matcher, PrintWriter out, SMTPSession session) {
        if (session.state != State.CONNECTED) {
            out.println("503 Bad sequence of commands");
            return true;
        }
        session.heloUser = matcher.group(2);
        session.state = State.GREETED;
        out.println("250 " + fqdnServer + " Hello " + session.heloUser);
        return true;
    }
    private boolean handleMailFrom(Matcher matcher, PrintWriter out, SMTPSession session) {
        if (session.state != State.GREETED) {
            out.println("503 Bad sequence of commands");
            return true;
        }

        try {
            String sender = matcher.group(1);
            String username = sender.substring(0, sender.indexOf("@"));

            // Only check if user exists, not password
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            AuthService authService = (AuthService) registry.lookup("AuthService");

            if (!authService.userExists(username)) {  // We need to add this method to AuthService
                out.println("550 Sender not recognized");
                return true;
            }

            session.sender = sender;
            session.state = State.MAIL_FROM;
            out.println("250 Sender OK");
            return true;
        } catch (Exception e) {
            out.println("451 Authentication service unavailable");
            return true;
        }
    }
    private boolean handleRcptTo(Matcher matcher, PrintWriter out, SMTPSession session) {
        if (session.state != State.MAIL_FROM && session.state != State.RCPT_TO) {
            out.println("503 Bad sequence of commands");
            return true;
        }

        String recipient = matcher.group(1);
        String username = recipient.substring(0, recipient.indexOf("@"));
        Path userDir = mailDir.resolve(username);

        if (!Files.exists(userDir)) {
            out.println("550 Recipient not found");
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

        StringBuilder emailBody = new StringBuilder();
        String line;
        while (!(line = in.readLine()).equals(".")) {
            emailBody.append(line).append("\n");
        }

        saveEmail(session.sender, session.recipients, emailBody.toString());
        out.println("250 Message accepted for delivery");
        session.state = State.GREETED;
        return true;
    }

    private boolean handleRset(PrintWriter out, SMTPSession session) {
        session.reset();
        out.println("250 Reset OK");
        return true;
    }

    private synchronized void saveEmail(String sender, List<String> recipients, String body) {
        String dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now(ZoneId.of("GMT")));

        String headers = "From: " + sender + "\n" +
                "To: " + String.join(", ", recipients) + "\n" +
                "Date: " + dateHeader + "\n\n";

        for (String recipient : recipients) {
            try {
                String username = recipient.substring(0, recipient.indexOf("@"));
                Path userDir = mailDir.resolve(username);

                if (!Files.exists(userDir)) {
                    Files.createDirectories(userDir);
                }

                String filename = System.currentTimeMillis() + ".txt";
                Files.write(
                        userDir.resolve(filename),
                        (headers + body).getBytes(StandardCharsets.UTF_8)
                );
            } catch (IOException e) {
                System.err.println("Failed to save email for " + recipient + ": " + e.getMessage());
            }
        }
    }

    private static class SMTPSession {
        String heloUser;
        String sender;
        List<String> recipients = new ArrayList<>();
        State state = State.CONNECTED;

        void reset() {
            heloUser = null;
            sender = null;
            recipients.clear();
            state = State.CONNECTED;
        }
    }
}