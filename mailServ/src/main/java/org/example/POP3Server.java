package org.example;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.rmi.registry.*;

public class POP3Server {
    // Server configuration
    private final int port = 110;
    private final Path mailDir = Paths.get("MailServer");
    private final String fqdnServer = "mail.example.com";
    private final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    // POP3 protocol states
    private enum State { AUTHORIZATION, TRANSACTION, UPDATE }

    // Command patterns
    private final Pattern userPattern = Pattern.compile("(?i)^USER\\s+(\\S+)$");
    private final Pattern passPattern = Pattern.compile("(?i)^PASS\\s+(.+)$");
    private final Pattern statPattern = Pattern.compile("(?i)^STAT$");
    private final Pattern listPattern = Pattern.compile("(?i)^LIST(?:\\s+(\\d+))?$");
    private final Pattern retrPattern = Pattern.compile("(?i)^RETR\\s+(\\d+)$");
    private final Pattern delePattern = Pattern.compile("(?i)^DELE\\s+(\\d+)$");
    private final Pattern noopPattern = Pattern.compile("(?i)^NOOP$");
    private final Pattern rsetPattern = Pattern.compile("(?i)^RSET$");
    private final Pattern quitPattern = Pattern.compile("(?i)^QUIT$");



    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("POP3 Server started on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        POP3Session session = new POP3Session();
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            out.println("+OK " + fqdnServer + " POP3 server ready");

            String command;
            while ((command = in.readLine()) != null) {
                if (!processCommand(command, out, session)) {
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

    private boolean processCommand(String command, PrintWriter out, POP3Session session) {
        Matcher matcher;

        if ((matcher = quitPattern.matcher(command)).matches()) {
            return handleQuit(out, session);
        }

        switch (session.state) {
            case AUTHORIZATION:
                return handleAuthorization(matcher, command, out, session);
            case TRANSACTION:
                return handleTransaction(matcher, command, out, session);
            default:
                out.println("-ERR Invalid state");
                return true;
        }
    }

    private boolean handleAuthorization(Matcher matcher, String command, PrintWriter out, POP3Session session) {
        if ((matcher = userPattern.matcher(command)).matches()) {
            String username = matcher.group(1);
            Path userDir = mailDir.resolve(username);

            if (Files.exists(userDir)) {
                session.username = username;
                out.println("+OK User accepted");
            } else {
                out.println("-ERR No such user");
            }
            return true;
        } else if ((matcher = passPattern.matcher(command)).matches()) {
            if (session.username == null) {
                out.println("-ERR USER command not issued");
                return true;
            }

            try {
                Registry registry = LocateRegistry.getRegistry("localhost", 1099);
                AuthService authService = (AuthService) registry.lookup("AuthService");

                if (authService.authenticate(session.username, matcher.group(1))) {
                    session.state = State.TRANSACTION;
                    session.messages = loadMessages(session.username);
                    out.println("+OK Mailbox locked and ready");
                } else {
                    out.println("-ERR Invalid password");
                }
            } catch (Exception e) {
                out.println("-ERR Authentication service unavailable");
            }
            return true;
        } else {
            out.println("-ERR Command not allowed in current state");
            return true;
        }
    }

    private boolean handleTransaction(Matcher matcher, String command, PrintWriter out, POP3Session session) {
        if ((matcher = statPattern.matcher(command)).matches()) {
            long totalSize = session.messages.stream()
                    .filter(msg -> !session.deletedMessages.contains(msg))
                    .mapToLong(p -> p.toFile().length())
                    .sum();
            out.println("+OK " + session.messages.size() + " " + totalSize);
            return true;
        } else if ((matcher = listPattern.matcher(command)).matches()) {
            return handleList(matcher, out, session);
        } else if ((matcher = retrPattern.matcher(command)).matches()) {
            return handleRetr(matcher, out, session);
        } else if ((matcher = delePattern.matcher(command)).matches()) {
            return handleDele(matcher, out, session);
        } else if ((matcher = noopPattern.matcher(command)).matches()) {
            out.println("+OK");
            return true;
        } else if ((matcher = rsetPattern.matcher(command)).matches()) {
            session.deletedMessages.clear();
            out.println("+OK Reset complete");
            return true;
        } else {
            out.println("-ERR Unknown command");
            return true;
        }
    }

    private boolean handleList(Matcher matcher, PrintWriter out, POP3Session session) {
        try {
            if (matcher.group(1) == null) {
                // List all messages
                for (int i = 0; i < session.messages.size(); i++) {
                    if (!session.deletedMessages.contains(session.messages.get(i))) {
                        out.println((i + 1) + " " + Files.size(session.messages.get(i)));
                    }
                }
                out.println(".");
            } else {
                // List specific message
                int msgNum = Integer.parseInt(matcher.group(1));
                if (msgNum < 1 || msgNum > session.messages.size()) {
                    out.println("-ERR No such message");
                } else if (session.deletedMessages.contains(session.messages.get(msgNum - 1))) {
                    out.println("-ERR Message marked for deletion");
                } else {
                    out.println("+OK " + msgNum + " " + Files.size(session.messages.get(msgNum - 1)));
                }
            }
            return true;
        } catch (IOException | NumberFormatException e) {
            out.println("-ERR Error processing request");
            return true;
        }
    }

    private boolean handleRetr(Matcher matcher, PrintWriter out, POP3Session session) {
        try {
            int msgNum = Integer.parseInt(matcher.group(1));
            if (msgNum < 1 || msgNum > session.messages.size()) {
                out.println("-ERR No such message");
            } else if (session.deletedMessages.contains(session.messages.get(msgNum - 1))) {
                out.println("-ERR Message marked for deletion");
            } else {
                String content = Files.readString(session.messages.get(msgNum - 1));
                out.println("+OK " + content.length() + " octets");
                out.println(content);
                out.println(".");
            }
            return true;
        } catch (IOException | NumberFormatException e) {
            out.println("-ERR Error retrieving message");
            return true;
        }
    }

    private boolean handleDele(Matcher matcher, PrintWriter out, POP3Session session) {
        try {
            int msgNum = Integer.parseInt(matcher.group(1));
            if (msgNum < 1 || msgNum > session.messages.size()) {
                out.println("-ERR No such message");
            } else {
                session.deletedMessages.add(session.messages.get(msgNum - 1));
                out.println("+OK Message " + msgNum + " marked for deletion");
            }
            return true;
        } catch (NumberFormatException e) {
            out.println("-ERR Invalid message number");
            return true;
        }
    }

    private boolean handleQuit(PrintWriter out, POP3Session session) {
        if (session.state == State.TRANSACTION) {
            try {
                // Actually delete marked messages
                for (Path message : session.deletedMessages) {
                    Files.deleteIfExists(message);
                }
                out.println("+OK Goodbye");
            } catch (IOException e) {
                out.println("-ERR Error deleting messages");
            }
        } else {
            out.println("+OK Goodbye");
        }
        return false;
    }

    private List<Path> loadMessages(String username) throws IOException {
        Path userDir = mailDir.resolve(username);
        if (!Files.exists(userDir)) {
            return Collections.emptyList();
        }

        try (var stream = Files.list(userDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".txt"))
                    .sorted()
                    .toList();
        }
    }

    private static class POP3Session {
        String username;
        State state = State.AUTHORIZATION;
        List<Path> messages = Collections.emptyList();
        Set<Path> deletedMessages = new HashSet<>();
    }
}