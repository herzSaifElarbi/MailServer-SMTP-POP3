package org.example;
import java.sql.*;
public class DBUtil {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/mail_server?useSSL=false";
    private static final String USER = "root"; // adjust this
    private static final String PASS = "Amin@07032001"; // adjust this
    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("MySQL driver not found", e);
        }
    }
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }
}
