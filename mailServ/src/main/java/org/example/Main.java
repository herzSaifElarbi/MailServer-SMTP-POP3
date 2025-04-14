package org.example;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
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