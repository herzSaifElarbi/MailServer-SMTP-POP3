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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SMTPServer {
    private int port;
    private Path mailDir;
    private String fqdnServer = "mail.example.net"; // Nom de domaine du serveur

    // Variables pour stocker l'expéditeur, les destinataires et l'utilisateur HELO
    private String heloUser;
    private String sender;
    private List<String> recipients = new ArrayList<>();

    // États de session SMTP
    private enum State {
        CONNECTED,    // Connexion établie, attente HELO/EHLO
        GREETED,      // HELO/EHLO reçu et validé
        MAIL_FROM,    // MAIL FROM reçu et validé
        RCPT_TO,      // Au moins un RCPT TO reçu
        DATA_INPUT    // Mode DATA en cours
    }

    private State state = State.CONNECTED;

    // Expressions régulières pour les commandes SMTP
    private final Pattern heloEhloPattern = Pattern.compile("(?i)^(HELO|EHLO)\\s+([^\\s]+)$");
    private final Pattern mailFromPattern = Pattern.compile("(?i)^MAIL FROM:<([^<>\\s]+@([^<>\\s]+))>$");
    private final Pattern rcptToPattern = Pattern.compile("(?i)^RCPT TO:<([^<>\\s]+@[^<>\\s]+\\.[a-zA-Z]{2,})>$");
    private final Pattern dataPattern = Pattern.compile("(?i)^DATA$");
    private final Pattern rsetPattern = Pattern.compile("(?i)^RSET$");
    private final Pattern noopPattern = Pattern.compile("(?i)^NOOP$");
    private final Pattern quitPattern = Pattern.compile("(?i)^QUIT$");

    private final ExecutorService threadPool = Executors.newFixedThreadPool(10); //number of threads = 10

    public SMTPServer() {
        this.port = 25;
        this.mailDir = Paths.get("MailServer");
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("SMTPServer Starting ......");
            while (true) {                
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }
    private void handleClient(Socket clientSocket){
        try(
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ){
            resetSession();
            out.println("220 " + fqdnServer + " Welcome to SMTP Server");
            String command;
            while ((command = in.readLine()) != null) {
                if (!processCommand(command, in, out)) {
                    break; // Quitter la boucle si la session est fermée
                }
            }

        }
        catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        }
    }

    private void resetSession() {
        heloUser = null;
        sender = null;
        recipients.clear();
        state = State.CONNECTED;
    }

    private boolean processCommand(String command, BufferedReader in, PrintWriter out) {
        try {
            Matcher matcher;

            // HELO/EHLO
            if ((matcher = heloEhloPattern.matcher(command)).matches()) {
                if (state != State.CONNECTED) {
                    out.println("503 Bad sequence of commands");
                    return true;
                }
                String user = matcher.group(2); 
                Path userDir = mailDir.resolve(user);

                if (!Files.exists(userDir)) {
                    out.println("550 User not found");
                    return true;
                }

                heloUser = user;
                state = State.GREETED;
                out.println("250 " + fqdnServer + " Hello " + user);
            }

            // MAIL FROM
            else if ((matcher = mailFromPattern.matcher(command)).matches()) {
                if (state != State.GREETED) {
                    out.println("503 Bad sequence of commands");
                    return true;
                }

                String emailSender = matcher.group(1);
                String domain = matcher.group(2);

                if (!emailSender.startsWith(heloUser + "@")) {
                    out.println("550 Sender does not match HELO domain");
                    return true;
                }

                sender = emailSender;
                state = State.MAIL_FROM;
                out.println("250 OK");
            }

            // RCPT TO
            else if ((matcher = rcptToPattern.matcher(command)).matches()) {
                if (state != State.MAIL_FROM && state != State.RCPT_TO) {
                    out.println("503 Bad sequence of commands");
                    return true;
                }
                String recipient = matcher.group(1);
                String user = recipient.substring(0, recipient.indexOf("@"));
                Path userDir = mailDir.resolve(user);

                if (!Files.exists(userDir)) {
                    out.println("550 User not found");
                } else {
                    recipients.add(recipient);
                    state = State.RCPT_TO;
                    out.println("250 OK");
                }
            }

            // DATA
            else if ((matcher = dataPattern.matcher(command)).matches()) {
                if (state != State.RCPT_TO) {
                    out.println("503 Bad sequence of commands");
                    return true;
                }
                state = State.DATA_INPUT;
                out.println("354 Start mail input; end with <CRLF>.<CRLF>");
                StringBuilder emailBody = new StringBuilder();
                String line;

                while (!(line = in.readLine()).equals(".")) {
                    emailBody.append(line).append("\n");
                }

                saveEmail(emailBody.toString());
                out.println("250 Message accepted");
                state = State.GREETED;
            }

            // RSET
            else if ((matcher = rsetPattern.matcher(command)).matches()) {
                sender = null;
                recipients.clear();
                state = State.GREETED;
                out.println("250 Reset OK");
            }

            // NOOP
            else if ((matcher = noopPattern.matcher(command)).matches()) {
                out.println("250 OK");
            }

            // QUIT
            else if ((matcher = quitPattern.matcher(command)).matches()) {
                out.println("221 Bye");
                return false;
            }

            // Commande inconnue
            else {
                out.println("500 Syntax error, command unrecognized");
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void saveEmail(String emailBodyContent) {
        String dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT")));
        String header = "From: " + sender + "\n" +
                        "To: " + String.join(", ", recipients) + "\n" +
                        "Date: " + dateHeader + "\n\n";
        String fullEmail = header + emailBodyContent;

        for (String recipient : recipients) {
            try {
                String user = recipient.substring(0, recipient.indexOf("@"));
                Path userDir = mailDir.resolve(user);

                String fileName = System.currentTimeMillis() + ".txt";
                Path emailFile = userDir.resolve(fileName);
                Files.write(emailFile, fullEmail.getBytes(StandardCharsets.UTF_8));
                System.out.println("Saved email for " + recipient + " in " + emailFile);
            } catch (IOException e) {
                System.err.println("Error saving email for " + recipient + ": " + e.getMessage());
            }
        }
    }

}
