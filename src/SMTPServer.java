import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class SMTPServer {
    private final int port;
    private final Path mailDir;
    private final String fqdnServer = "mail.example.net"; 

    private final ExecutorService threadPool = Executors.newFixedThreadPool(2); // Handle multiple clients

    // SMTP Session states
    enum State {
        CONNECTED, GREETED, MAIL_FROM, RCPT_TO, DATA_INPUT
    }

    // SMTP Command patterns
    private final Pattern heloEhloPattern = Pattern.compile("(?i)^(HELO|EHLO)\\s+([^\\s]+)$");
    private final Pattern mailFromPattern = Pattern.compile("(?i)^MAIL FROM:<([^<>\\s]+@([^<>\\s]+))>$");
    private final Pattern rcptToPattern = Pattern.compile("(?i)^RCPT TO:<([^<>\\s]+@[^<>\\s]+\\.[a-zA-Z]{2,})>$");
    private final Pattern dataPattern = Pattern.compile("(?i)^DATA$");
    private final Pattern rsetPattern = Pattern.compile("(?i)^RSET$");
    private final Pattern noopPattern = Pattern.compile("(?i)^NOOP$");
    private final Pattern quitPattern = Pattern.compile("(?i)^QUIT$");

    public SMTPServer() {
        this.port = 25;
        this.mailDir = Paths.get("MailServer");
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("SMTPServer started on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        SMTPSession session = new SMTPSession(); // Each client gets its own session
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            out.println("220 " + fqdnServer + " Welcome to SMTP Server");

            String command;
            while ((command = in.readLine()) != null) {
                if (!processCommand(command, in, out, session)) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        }
    }

    private boolean processCommand(String command, BufferedReader in, PrintWriter out, SMTPSession session) {
        try {
            Matcher matcher;

            if ((matcher = heloEhloPattern.matcher(command)).matches()) {
                if (session.state != State.CONNECTED) {
                    out.println("503 Bad sequence of commands");
                    return true;
                }
                session.heloUser = matcher.group(2);
                session.state = State.GREETED;
                out.println("250 " + fqdnServer + " Hello " + session.heloUser);
            }

            else if ((matcher = mailFromPattern.matcher(command)).matches()) {
                if (session.state != State.GREETED) {
                    out.println("503 Bad sequence of commands");
                    return true;
                }
                session.sender = matcher.group(1);
                session.state = State.MAIL_FROM;
                out.println("250 OK");
            }

            else if ((matcher = rcptToPattern.matcher(command)).matches()) {
                if (session.state != State.MAIL_FROM && session.state != State.RCPT_TO) {
                    out.println("503 Bad sequence of commands");
                    return true;
                }
                String recipient = matcher.group(1);
                String user = recipient.substring(0, recipient.indexOf("@"));
                Path userDir = mailDir.resolve(user);

                if (!Files.exists(userDir)) {
                    out.println("550 User not found");
                } else {
                    session.recipients.add(recipient);
                    session.state = State.RCPT_TO;
                    out.println("250 OK");
                }
            }

            else if ((matcher = dataPattern.matcher(command)).matches()) {
                if (session.state != State.RCPT_TO) {
                    out.println("503 Bad sequence of commands");
                    return true;
                }
                session.state = State.DATA_INPUT;
                out.println("354 Start mail input; end with <CRLF>.<CRLF>");
                StringBuilder emailBody = new StringBuilder();
                String line;

                while (!(line = in.readLine()).equals(".")) {
                    emailBody.append(line).append("\n");
                }

                saveEmail(session.sender, session.recipients, emailBody.toString());
                out.println("250 Message accepted");
                session.state = State.GREETED;
            }

            else if ((matcher = rsetPattern.matcher(command)).matches()) {
                session.reset();
                out.println("250 Reset OK");
            }

            else if ((matcher = noopPattern.matcher(command)).matches()) {
                out.println("250 OK");
            }

            else if ((matcher = quitPattern.matcher(command)).matches()) {
                out.println("221 Bye");
                return false;
            }

            else {
                out.println("500 Syntax error, command unrecognized");
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private synchronized void saveEmail(String sender, List<String> recipients, String emailBodyContent) {
        String dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT")));
        String header = "From: " + sender + "\n" +
                        "To: " + String.join(", ", recipients) + "\n" +
                        "Date: " + dateHeader + "\n\n";
        String fullEmail = header + emailBodyContent;

        for (String recipient : recipients) {
            try {
                String user = recipient.substring(0, recipient.indexOf("@"));
                Path userDir = mailDir.resolve(user);

                if (!Files.exists(userDir)) {
                    Files.createDirectories(userDir);
                }

                String fileName = System.currentTimeMillis() + ".txt";
                Path emailFile = userDir.resolve(fileName);
                Files.write(emailFile, fullEmail.getBytes(StandardCharsets.UTF_8));
                System.out.println("Saved email for " + recipient + " in " + emailFile);
            } catch (IOException e) {
                System.err.println("Error saving email for " + recipient + ": " + e.getMessage());
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
