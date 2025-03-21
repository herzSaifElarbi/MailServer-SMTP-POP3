import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SMTPServer {
    private int port;
    private Path mailDir;
    private String fqdnServer = "mail.example.net"; // Server's FQDN

    // Variables to store the sender and recipients
    private String sender;
    private List<String> recipients = new ArrayList<>();

    // Define SMTP session states per RFC 5321
    private enum State {
        CONNECTED,     // Connection established; waiting for HELO/EHLO
        GREETED,       // HELO/EHLO received
        MAIL_FROM,     // MAIL FROM command received
        RCPT_TO,       // At least one RCPT TO received
        DATA_INPUT     // In DATA mode (reading email content)
    }
    
    // Current state of the SMTP session
    private State state = State.CONNECTED;
    
    // Regular expressions for SMTP commands (with capturing groups when needed)
    private String heloEhloRegex = "(?i)^(HELO|EHLO)\\s+([^\\s]+)$";
    private String mailFromRegex = "(?i)^MAIL FROM:<([^<>\\s]+@[^<>\\s]+\\.[a-zA-Z]{2,})>$";
    private String rcptToRegex = "(?i)^RCPT TO:<([^<>\\s]+@[^<>\\s]+\\.[a-zA-Z]{2,})>$";
    private String dataRegex = "(?i)^DATA$";
    private String rsetRegex = "(?i)^RSET$";
    private String noopRegex = "(?i)^NOOP$";
    private String quitRegex = "(?i)^QUIT$";
    private String vrfyRegex = "(?i)^VRFY\\s+[^\\s]+$";
    private String expnRegex = "(?i)^EXPN\\s+[^\\s]+$";
    private String helpRegex = "(?i)^HELP(\\s+[^\\s]+)?$";
    
    public SMTPServer(){
        this.port = 25;
        this.mailDir = Paths.get("MailServer");
    }
    
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("SMTPServer Starting ......");
            while (true) {
                try (
                    Socket clientSocket = serverSocket.accept();
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
                ) {
                    // Reset session variables and state for new connection
                    sender = null;
                    recipients.clear();
                    state = State.CONNECTED;
                    out.println("220 " + fqdnServer + " Welcome to SMTP Server");
                    
                    String command;
                    while ((command = in.readLine()) != null) {
                        if (!processCommand(command, in, out)) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Connection error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }
    
    private boolean processCommand(String command, BufferedReader in, PrintWriter out) {
        try {
            // Check for HELO/EHLO command
            Pattern heloPattern = Pattern.compile(heloEhloRegex);
            Matcher matcher = heloPattern.matcher(command);
            if (matcher.matches()) {
                if (state != State.CONNECTED) {
                    out.println("503 Bad sequence of commands");
                    return true;
                }
                String commandType = matcher.group(1);  // "HELO" or "EHLO"
                String clientDomain = matcher.group(2);   // The domain provided by the client
                state = State.GREETED;
                if (commandType.equalsIgnoreCase("HELO")) {
                    out.println("250 " + fqdnServer + " Hello " + clientDomain);
                } else { // EHLO with extensions
                    out.println("250-" + fqdnServer + " Hello " + clientDomain);
                    out.println("250 SIZE 10485760");
                }
            }
            // Handle MAIL FROM command: capture and store sender address
            else if (command.matches(mailFromRegex)) {
                if (state != State.GREETED) {
                    out.println("503 Bad sequence of commands (expecting HELO/EHLO first)");
                    return true;
                }
                Pattern mailFromPattern = Pattern.compile(mailFromRegex);
                Matcher mailMatcher = mailFromPattern.matcher(command);
                if (mailMatcher.matches()) {
                    sender = mailMatcher.group(1);
                    System.out.println("Sender: " + sender);
                    state = State.MAIL_FROM;
                    out.println("250 OK");
                }
            }
            // Handle RCPT TO command: capture and add recipient address
            else if (command.matches(rcptToRegex)) {
                if (state != State.MAIL_FROM && state != State.RCPT_TO) {
                    out.println("503 Bad sequence of commands (expecting MAIL FROM first)");
                    return true;
                }
                Pattern rcptToPattern = Pattern.compile(rcptToRegex);
                Matcher rcptMatcher = rcptToPattern.matcher(command);
                if (rcptMatcher.matches()) {
                    String recipient = rcptMatcher.group(1);
                    recipients.add(recipient);
                    System.out.println("Added recipient: " + recipient);
                    state = State.RCPT_TO;
                    out.println("250 OK");
                }
            }
            // Handle DATA command: allowed only after at least one RCPT TO
            else if (command.matches(dataRegex)) {
                if (state != State.RCPT_TO) {
                    out.println("503 Bad sequence of commands (expecting RCPT TO before DATA)");
                    return true;
                }
                state = State.DATA_INPUT;
                out.println("354 Start mail input; end with <CRLF>.<CRLF>");
                StringBuilder emailBody = new StringBuilder();
                String line;
                // Read email data until a line with only a period is encountered
                while (!(line = in.readLine()).equals(".")) {
                    emailBody.append(line).append("\n");
                }
                // Save the email with a proper RFC5322 header
                saveEmail(emailBody.toString());
                out.println("250 Message accepted");
                // Reset for a new transaction on the same connection
                state = State.GREETED;
            }
            // Handle RSET command: resets the current transaction
            else if (command.matches(rsetRegex)) {
                sender = null;
                recipients.clear();
                state = State.GREETED;
                out.println("250 Reset OK");
            }
            // Handle NOOP command: no operation
            else if (command.matches(noopRegex)) {
                out.println("250 OK");
            }
            // Handle QUIT command: end the session
            else if (command.matches(quitRegex)) {
                out.println("221 Bye");
                return false;
            }
            // Handle VRFY command
            else if (command.matches(vrfyRegex)) {
                out.println("252 Cannot VRFY user, but will accept message and attempt delivery");
            }
            // Handle EXPN command
            else if (command.matches(expnRegex)) {
                out.println("502 Command not implemented");
            }
            // Handle HELP command
            else if (command.matches(helpRegex)) {
                out.println("214 Help message");
            }
            // Unrecognized command
            else {
                out.println("500 Syntax error, command unrecognized");
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Save the email content to a file for each recipient using RFC5322 format
    private void saveEmail(String emailBodyContent) {
        // Build a minimal RFC5322 header using the stored sender and recipients
        String dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT")));
        String header = "From: " + sender + "\n" +
                        "To: " + String.join(", ", recipients) + "\n" +
                        //"Subject: Project Update\n" +
                        "Date: " + dateHeader + "\n\n";
        String fullEmail = header + emailBodyContent;
        
        // Save the email for each recipient in their respective folder
        for (String recipient : recipients) {
            try {
                // Extract username (part before '@') for folder name
                String user = recipient.substring(0, recipient.indexOf("@"));
                Path userDir = mailDir.resolve(user);
                // Create the directory if it doesn't exist
                if (!Files.exists(userDir)) {
                    Files.createDirectories(userDir);
                }
                // Create a file name based on current timestamp
                String fileName = System.currentTimeMillis() + ".txt";
                Path emailFile = userDir.resolve(fileName);
                Files.write(emailFile, fullEmail.getBytes(StandardCharsets.UTF_8));
                System.out.println("Saved email for " + recipient + " in " + emailFile.toString());
            } catch (IOException e) {
                System.err.println("Error saving email for " + recipient + ": " + e.getMessage());
            }
        }
    }
}
