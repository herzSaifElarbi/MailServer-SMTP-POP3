package org.example;

import java.io.*;
import java.net.*;
import java.rmi.registry.*;
import java.sql.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class POP3Server {
    private final int port = 110;
    private final String fqdnServer = "mail.example.com";
    private final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    // Session states
    private enum State { AUTHORIZATION, TRANSACTION }

    // Command patterns
    private static final Pattern USER_CMD = Pattern.compile("(?i)^USER\\s+(\\S+)$");
    private static final Pattern PASS_CMD = Pattern.compile("(?i)^PASS\\s+(.+)$");
    private static final Pattern STAT_CMD = Pattern.compile("(?i)^STAT$");
    private static final Pattern LIST_CMD = Pattern.compile("(?i)^LIST(?:\\s+(\\d+))?$");
    private static final Pattern RETR_CMD = Pattern.compile("(?i)^RETR\\s+(\\d+)$");
    private static final Pattern DELE_CMD = Pattern.compile("(?i)^DELE\\s+(\\d+)$");
    private static final Pattern NOOP_CMD = Pattern.compile("(?i)^NOOP$");
    private static final Pattern RSET_CMD = Pattern.compile("(?i)^RSET$");
    private static final Pattern QUIT_CMD = Pattern.compile("(?i)^QUIT$");

    public static void main(String[] args) {
        new POP3Server().start();
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("POP3 Server started on port " + port);
            while (true) {
                Socket client = serverSocket.accept();
                threadPool.execute(() -> handleClient(client));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket client) {
        POP3Session session = new POP3Session();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

            out.println("+OK " + fqdnServer + " POP3 server ready");
            String line;
            while ((line = in.readLine()) != null) {
                if (!processCommand(line, out, session)) break;
            }
        } catch (IOException e) {
            System.err.println("Client handling error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private boolean processCommand(String cmd, PrintWriter out, POP3Session s) {
        Matcher m;
        if ((m = QUIT_CMD.matcher(cmd)).matches()) {
            return handleQuit(out, s);
        }
        switch (s.state) {
            case AUTHORIZATION: return handleAuthorization(cmd, out, s);
            case TRANSACTION:   return handleTransaction(cmd, out, s);
            default:
                out.println("-ERR Invalid state");
                return true;
        }
    }

    private boolean handleAuthorization(String cmd, PrintWriter out, POP3Session s) {
        Matcher m;
        if ((m = USER_CMD.matcher(cmd)).matches()) {
            s.username = m.group(1);
            out.println("+OK User accepted");
        } else if ((m = PASS_CMD.matcher(cmd)).matches()) {
            try {
                Registry registry = LocateRegistry.getRegistry("localhost", 1099);
                AuthService auth = (AuthService) registry.lookup("AuthService");
                if (auth.authenticate(s.username, m.group(1))) {
                    s.state = State.TRANSACTION;
                    s.messages = loadMessages(s.username);
                    out.println("+OK Mailbox locked and ready");
                } else {
                    out.println("-ERR Invalid password");
                }
            } catch (Exception e) {
                System.err.println("POP3 auth lookup failed: " + e);
                out.println("-ERR Auth service unavailable");
            }
        } else {
            out.println("-ERR Command not allowed in AUTHORIZATION");
        }
        return true;
    }

    private boolean handleTransaction(String cmd, PrintWriter out, POP3Session s) {
        Matcher m;
        if (STAT_CMD.matcher(cmd).matches()) {
            long count = s.messages.size() - s.deletions.size();
            long totalSize = s.messages.stream()
                    .filter(r -> !s.deletions.contains(r.email_id))
                    .mapToLong(r -> r.size)
                    .sum();
            out.println("+OK " + count + " " + totalSize);

        } else if ((m = LIST_CMD.matcher(cmd)).matches()) {
            if (m.group(1) == null) {
                s.messages.forEach(r -> {
                    if (!s.deletions.contains(r.email_id)) out.println(r.email_id + " " + r.size);
                });
                out.println(".");
            } else {
                long email_id = Long.parseLong(m.group(1));
                s.messages.stream()
                        .filter(r -> r.email_id == email_id && !s.deletions.contains(r.email_id))
                        .findFirst()
                        .ifPresentOrElse(
                                r -> out.println("+OK " + r.email_id + " " + r.size),
                                () -> out.println("-ERR No such message")
                        );
            }

        } else if ((m = RETR_CMD.matcher(cmd)).matches()) {
            long email_id = Long.parseLong(m.group(1));
            s.messages.stream()
                    .filter(r -> r.email_id == email_id && !s.deletions.contains(r.email_id))
                    .findFirst()
                    .ifPresentOrElse(r -> {
                        out.println("+OK " + r.size + " octets");
                        out.println(r.content);
                        out.println(".");
                    }, () -> out.println("-ERR No such message"));

        } else if ((m = DELE_CMD.matcher(cmd)).matches()) {
            long email_id = Long.parseLong(m.group(1));
            s.deletions.add(email_id);
            out.println("+OK Message " + email_id + " marked for deletion");

        } else if (NOOP_CMD.matcher(cmd).matches()) {
            out.println("+OK");

        } else if (RSET_CMD.matcher(cmd).matches()) {
            s.deletions.clear();
            out.println("+OK Reset");

        } else {
            out.println("-ERR Unknown command");
        }
        return true;
    }

    private List<EmailRecord> loadMessages(String username) {
        List<EmailRecord> list = new ArrayList<>();
        // Changed to only load messages where is_deleted = 0
        String sql = "SELECT email_id, content, LENGTH(content) as size FROM emails " +
                "WHERE recipient = ? AND is_deleted = 0 ORDER BY date_sent";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EmailRecord r = new EmailRecord();
                    r.email_id = rs.getLong("email_id");
                    r.content = rs.getString("content");
                    r.size = rs.getLong("size");
                    list.add(r);
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to load messages: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("Loaded " + list.size() + " messages for user " + username);
        return list;
    }

    private boolean handleQuit(PrintWriter out, POP3Session s) {
        if (s.state == State.TRANSACTION && !s.deletions.isEmpty()) {
            String sql = "UPDATE emails SET is_deleted = 1 WHERE email_id = ? AND is_deleted = 0";
            try (Connection c = DBUtil.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (Long email_id : s.deletions) {
                        ps.setLong(1, email_id);
                        ps.addBatch();
                    }
                    int[] results = ps.executeBatch();
                    c.commit();
                    int successCount = Arrays.stream(results).sum();
                    System.out.println("Successfully marked " + successCount + " messages as deleted");
                    if (successCount == s.deletions.size()) {
                        out.println("+OK Goodbye");
                    } else {
                        out.println("+OK Some messages couldn't be deleted");
                    }
                } catch (SQLException e) {
                    c.rollback();
                    System.err.println("POP3 delete batch failed: " + e.getMessage());
                    out.println("-ERR Could not delete messages");
                }
            } catch (SQLException e) {
                System.err.println("Database connection error: " + e.getMessage());
                out.println("-ERR Server error");
            }
        } else {
            out.println("+OK Goodbye");
        }
        return false;
    }

    private static class POP3Session {
        String username;
        State state = State.AUTHORIZATION;
        List<EmailRecord> messages = new ArrayList<>();
        Set<Long> deletions = new HashSet<>();
    }

    private static class EmailRecord {
        long email_id;
        String content;
        long size;
    }
}