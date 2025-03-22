import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class POP3Server {
    // Server configuration
    private final int port = 110;
    private final Path mailDir = Paths.get("MailServer");
    private final String fqdnServer = "mail.example.net";
    
    // Session variables
    private String username;
    private List<Path> messages;
    private List<Integer> deletedMessages = new ArrayList<>();
    
    // POP3 protocol states
    private enum State {
        AUTHORIZATION,
        TRANSACTION,
        UPDATE
    }
    private State state;

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

    public POP3Server() {
        state = State.AUTHORIZATION;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("POP3Server started on port " + port);
            while (true) {
                try (Socket clientSocket = serverSocket.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                     PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
                    
                    resetSession();
                    out.println("+OK " + fqdnServer + " POP3 server ready");

                    String command;
                    while ((command = in.readLine()) != null) {
                        if (!processCommand(command, out)) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Client connection error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }
    
    private void resetSession() {
        username = null;
        messages = null;
        deletedMessages.clear();
        state = State.AUTHORIZATION;
    }

    private boolean processCommand(String command, PrintWriter out) {
        Matcher m;
        if ((m = quitPattern.matcher(command)).matches()) {
            handleQuit(out);
            return false;
        }
        
        if (state == State.AUTHORIZATION) {
            if ((m = userPattern.matcher(command)).matches()) {
                handleUser(m, out);
            } else if ((m = passPattern.matcher(command)).matches()) {
                handlePass(m, out);
            } else {
                out.println("-ERR Command not allowed in AUTHORIZATION state");
            }
        } else if (state == State.TRANSACTION) {
            if ((m = statPattern.matcher(command)).matches()) {
                handleStat(out);
            } else if ((m = listPattern.matcher(command)).matches()) {
                handleList(m, out);
            } else if ((m = retrPattern.matcher(command)).matches()) {
                handleRetr(m, out);
            } else if ((m = delePattern.matcher(command)).matches()) {
                handleDele(m, out);
            } else if ((m = noopPattern.matcher(command)).matches()) {
                out.println("+OK");
            } else if ((m = rsetPattern.matcher(command)).matches()) {
                handleRset(out);
            } else {
                out.println("-ERR Unknown command");
            }
        } else if (state == State.UPDATE) {
            out.println("-ERR Server is updating");
        }
        return true;
    }

    private void handleUser(Matcher m, PrintWriter out) {
        String userCandidate = m.group(1);
        Path userPath = mailDir.resolve(userCandidate);
        if (Files.exists(userPath)) {
            username = userCandidate;
            out.println("+OK User accepted");
        } else {
            out.println("-ERR No such user");
        }
    }

    private void handlePass(Matcher m, PrintWriter out) {
        if (username == null) {
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
            boolean validPassword = false;

            for (String line : lines) {
                String[] parts = line.split(":");
                if (parts.length == 2 && parts[0].equals(username) && parts[1].equals(enteredPassword)) {
                    validPassword = true;
                    break;
                }
            }

            if (validPassword) {
                messages = Files.list(mailDir.resolve(username))
                                .sorted()
                                .collect(Collectors.toList());
                state = State.TRANSACTION;
                out.println("+OK Authentication successful");
            } else {
                out.println("-ERR Invalid password");
            }

        } catch (IOException e) {
            out.println("-ERR Error reading password file");
        }
    }

    private void handleStat(PrintWriter out) {
        int count = 0;
        long totalSize = 0;
        for (int i = 0; i < messages.size(); i++) {
            int msgNum = i + 1;
            if (deletedMessages.contains(msgNum)) {
                continue;
            }
            totalSize += messages.get(i).toFile().length();
            count++;
        }
        out.println("+OK " + count + " " + totalSize);
    }

    private void handleList(Matcher m, PrintWriter out) {
        try {
            String arg = m.group(1);
            if (arg == null) {
                int count = 0;
                long totalSize = 0;
                List<String> scanListings = new ArrayList<>();
                
                for (int i = 0; i < messages.size(); i++) {
                    int msgNum = i + 1;
                    if (deletedMessages.contains(msgNum)) {
                        continue;
                    }
                    Path msgPath = messages.get(i);
                    long size = Files.size(msgPath);
                    scanListings.add(msgNum + " " + size);
                    count++;
                    totalSize += size;
                }
                
                out.println("+OK " + count + " messages (" + totalSize + " octets)");
                scanListings.forEach(out::println);
                out.println(".");
            } else {
                int msgNum = Integer.parseInt(arg);
                if (msgNum < 1 || msgNum > messages.size()) {
                    out.println("-ERR No such message");
                } else if (deletedMessages.contains(msgNum)) {
                    out.println("-ERR Message " + msgNum + " is marked for deletion");
                } else {
                    long size = Files.size(messages.get(msgNum - 1));
                    out.println("+OK " + msgNum + " " + size);
                }
            }
        } catch (IOException e) {
            out.println("-ERR Error retrieving message size");
        } catch (NumberFormatException e) {
            out.println("-ERR Invalid message number");
        }
    }

    private void handleRetr(Matcher m, PrintWriter out) {
        int msgNum = Integer.parseInt(m.group(1));
        if (msgNum < 1 || msgNum > messages.size()) {
            out.println("-ERR No such message");
        } else {
            try {
                String content = Files.readString(messages.get(msgNum - 1));
                out.println("+OK " + content.length() + " octets");
                out.println(content);
                out.println(".");
            } catch (IOException e) {
                out.println("-ERR Error reading message");
            }
        }
    }

    private void handleDele(Matcher m, PrintWriter out) {
        int msgNum = Integer.parseInt(m.group(1));
        if (msgNum < 1 || msgNum > messages.size()) {
            out.println("-ERR No such message");
        } else if (deletedMessages.contains(msgNum)) {
            out.println("-ERR Message already marked");
        } else {
            deletedMessages.add(msgNum);
            out.println("+OK Message " + msgNum + " marked for deletion");
        }
    }

    private void handleRset(PrintWriter out) {
        deletedMessages.clear();
        out.println("+OK Deletion marks reset");
    }

    private void handleQuit(PrintWriter out) {
        out.println("+OK Goodbye");
        state = State.UPDATE;
    }
}