import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Collectors;

public class POP3Server {
    private final int port = 110;
    private final Path mailDir = Paths.get("MailServer");
    private final String fqdnServer = "mail.example.net";

    private final ExecutorService threadPool = Executors.newFixedThreadPool(10); // Handle multiple clients

    // POP3 protocol states
    enum State {
        AUTHORIZATION, TRANSACTION, UPDATE
    }

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
            System.out.println("POP3Server started on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        POP3Session session = new POP3Session(); // Each client gets its own session
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
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private boolean processCommand(String command, PrintWriter out, POP3Session session) {
        Matcher m;
        if ((m = quitPattern.matcher(command)).matches()) {
            handleQuit(out, session);
            return false;
        }

        if (session.state == State.AUTHORIZATION) {
            if ((m = userPattern.matcher(command)).matches()) {
                handleUser(m, out, session);
            } else if ((m = passPattern.matcher(command)).matches()) {
                handlePass(m, out, session);
            } else {
                out.println("-ERR Command not allowed");
            }
        } else if (session.state == State.TRANSACTION) {
            if ((m = statPattern.matcher(command)).matches()) {
                handleStat(out, session);
            } else if ((m = listPattern.matcher(command)).matches()) {
                handleList(m, out, session);
            } else if ((m = retrPattern.matcher(command)).matches()) {
                handleRetr(m, out, session);
            } else if ((m = delePattern.matcher(command)).matches()) {
                handleDele(m, out, session);
            } else if ((m = noopPattern.matcher(command)).matches()) {
                out.println("+OK");
            } else if ((m = rsetPattern.matcher(command)).matches()) {
                handleRset(out, session);
            } else {
                out.println("-ERR Unknown command");
            }
        } else if (session.state == State.UPDATE) {
            out.println("-ERR Server is updating");
        }
        return true;
    }

    private void handleUser(Matcher m, PrintWriter out, POP3Session session) {
        String userCandidate = m.group(1);
        Path userPath = mailDir.resolve(userCandidate);
        if (Files.exists(userPath)) {
            session.username = userCandidate;
            out.println("+OK User accepted");
        } else {
            out.println("-ERR No such user");
        }
    }

    private void handlePass(Matcher m, PrintWriter out, POP3Session session) {
        if (session.username == null) {
            out.println("-ERR USER command not issued");
            return;
        }

        String enteredPassword = m.group(1);
        Path passwordFile = mailDir.resolve("passwords.txt");

        if (!Files.exists(passwordFile)) {
            out.println("-ERR Password file missing");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(passwordFile);
            boolean validPassword = lines.stream().anyMatch(line ->
                line.startsWith(session.username + ":") && line.split(":")[1].equals(enteredPassword));

            if (validPassword) {
                session.messages = Files.list(mailDir.resolve(session.username))
                                        .sorted()
                                        .collect(Collectors.toList());
                session.state = State.TRANSACTION;
                out.println("+OK Authentication successful");
            } else {
                out.println("-ERR Invalid password");
            }
        } catch (IOException e) {
            out.println("-ERR Error reading password file");
        }
    }

    private void handleStat(PrintWriter out, POP3Session session) {
        int count = 0;
        long totalSize = 0;
        for (int i = 0; i < session.messages.size(); i++) {
            if (!session.deletedMessages.contains(i + 1)) {
                totalSize += session.messages.get(i).toFile().length();
                count++;
            }
        }
        out.println("+OK " + count + " " + totalSize);
    }

    private void handleList(Matcher m, PrintWriter out, POP3Session session) {
        try {
            if (m.group(1) == null) {
                for (int i = 0; i < session.messages.size(); i++) {
                    if (!session.deletedMessages.contains(i + 1)) {
                        out.println((i + 1) + " " + Files.size(session.messages.get(i)));
                    }
                }
                out.println(".");
            } else {
                int msgNum = Integer.parseInt(m.group(1));
                if (msgNum < 1 || msgNum > session.messages.size() || session.deletedMessages.contains(msgNum)) {
                    out.println("-ERR No such message");
                } else {
                    out.println("+OK " + msgNum + " " + Files.size(session.messages.get(msgNum - 1)));
                }
            }
        } catch (IOException | NumberFormatException e) {
            out.println("-ERR Error processing request");
        }
    }

    private void handleRetr(Matcher m, PrintWriter out, POP3Session session) {
        int msgNum = Integer.parseInt(m.group(1));
        if (msgNum < 1 || msgNum > session.messages.size() || session.deletedMessages.contains(msgNum)) {
            out.println("-ERR No such message");
        } else {
            try {
                String content = Files.readString(session.messages.get(msgNum - 1));
                out.println("+OK " + content.length() + " octets");
                out.println(content);
                out.println(".");
            } catch (IOException e) {
                out.println("-ERR Error reading message");
            }
        }
    }

    private void handleDele(Matcher m, PrintWriter out, POP3Session session) {
        int msgNum = Integer.parseInt(m.group(1));
        if (msgNum < 1 || msgNum > session.messages.size() || session.deletedMessages.contains(msgNum)) {
            out.println("-ERR No such message");
        } else {
            session.deletedMessages.add(msgNum);
            out.println("+OK Message " + msgNum + " marked for deletion");
        }
    }

    private void handleRset(PrintWriter out, POP3Session session) {
        session.deletedMessages.clear();
        out.println("+OK Deletion marks reset");
    }

    private void handleQuit(PrintWriter out, POP3Session session) {
        session.deletedMessages.forEach(msgNum -> {
            try {
                Files.deleteIfExists(session.messages.get(msgNum - 1));
            } catch (IOException ignored) {}
        });
        out.println("+OK Goodbye");
        session.state = State.UPDATE;
    }

    private static class POP3Session {
        String username;
        List<Path> messages;
        List<Integer> deletedMessages = new ArrayList<>();
        State state = State.AUTHORIZATION;
    }

}
