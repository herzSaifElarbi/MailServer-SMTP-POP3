public class App {
    public static void main(String[] args) throws Exception {
        SMTPServer server = new SMTPServer();
        server.start();
        //POP3Server server = new POP3Server();
        //server.start();
    }
}
