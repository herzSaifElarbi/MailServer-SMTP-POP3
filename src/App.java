public class App {
    public static void main(String[] args) {
        // Create threads for both servers
        Thread smtpThread = new Thread(() -> {
            SMTPServer smtpServer = new SMTPServer();
            smtpServer.start();
        });

        Thread pop3Thread = new Thread(() -> {
            POP3Server pop3Server = new POP3Server();
            pop3Server.start();
        });

        // Start both servers
        smtpThread.start();
        pop3Thread.start();
    }
}
