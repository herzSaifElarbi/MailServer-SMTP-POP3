
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
// rana ya7ine nabdaw fi commands b regex
public class SMTPServer {
    private int port;
    private Path mailDir;
    public SMTPServer(){
        this.port = 25;
        this.mailDir = Paths.get("MailServer");
    }
    public void start(){
        try(ServerSocket serverSocket = new ServerSocket(port)){
            System.out.println("SMTPServer Starting ......");
            while (true) {
                try (
                    Socket clientSocket = serverSocket.accept(); //waiting for connection
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));//reading comamands
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)//sending messages
                    ){
                        out.println("220 Welcome to SMTP Server");
                        String command;
                        while ((command = in.readLine()) != null) {
                            if (!processCommand(command, in, out)) {
                                break;
                            }
                        }
                    }catch (IOException e) {
                        e.printStackTrace();
                    }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }
    private boolean processCommand(String command, BufferedReader in, PrintWriter out) {
        out.println("got a message back!");
        return true;
    }
}
