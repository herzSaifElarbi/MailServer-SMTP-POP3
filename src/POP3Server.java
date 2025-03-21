import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;

public class POP3Server {
    private final int port = 110;
    private final Path mailDir = Paths.get("MailServer");
    private final String fqdnServer = "mail.example.net"; // FQDN du serveur
    private String username; // Utilisateur authentifié

    // Définition des états du protocole POP3
    private enum State {
        AUTHORIZATION,  // Avant authentification
        TRANSACTION,    // Après authentification, gestion des emails
        UPDATE          // Déconnexion et suppression des messages marqués
    }
    private State state;
    private String userRegex = "(?i)^USER\\s+(\\S+)$";
    private String passRegex = "(?i)^PASS\\s+(.+)$";
    private String statRegex = "(?i)^STAT$";
    private String listRegex = "(?i)^LIST(?:\\s+(\\d+))?$";
    private String retrRegex = "(?i)^RETR\\s+(\\d+)$";
    private String deleRegex = "(?i)^DELE\\s+(\\d+)$";
    private String noopRegex = "(?i)^NOOP$";
    private String rsetRegex = "(?i)^RSET$";
    private String quitRegex = "(?i)^QUIT$";
    
    public POP3Server(){
    }
        
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("POP3Server Starting ......");
            while (true) {
                try (
                    Socket clientSocket = serverSocket.accept();
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
                ) {
                    // Reset session variables and state for new connection
                    username = null;
                    state = State.AUTHORIZATION;
                    out.println("+OK " + fqdnServer + " POP3 server ready");

                    String command;
                    while ((command = in.readLine()) != null) {
                        if (!processCommand(command, in, out)) {
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
    private boolean processCommand(String command, BufferedReader in, PrintWriter out) {
        out.println("+OK ");
        return true;
    }
    
    
}
