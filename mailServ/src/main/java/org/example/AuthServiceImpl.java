package org.example;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;

public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {

    public AuthServiceImpl() throws RemoteException {
        super();
    }

    @Override
    public boolean userExists(String username) throws RemoteException {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ? AND status = 'active'";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new RemoteException("DB error in userExists", e);
        }
    }

    @Override
    public boolean authenticate(String username, String password) throws RemoteException {
        String sql = "SELECT password_hash FROM users WHERE username = ? AND status = 'active'";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                // In prod, compare hashes (e.g. BCrypt) rather than plain‐text
                return password.equals(rs.getString("password_hash"));
            }
        } catch (SQLException e) {
            throw new RemoteException("DB error in authenticate", e);
        }
    }

    @Override
    public boolean createUser(String username, String password) throws RemoteException {
        String sql = "INSERT INTO users(username, password_hash, status) VALUES(?, ?, 'active')";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) return false;  // duplicate username
            throw new RemoteException("DB error in createUser", e);
        }
    }

    @Override
    public boolean updatePassword(String username, String newPassword) throws RemoteException {
        String sql = "UPDATE users SET password_hash = ? WHERE username = ? AND status = 'active'";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newPassword);
            ps.setString(2, username);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RemoteException("DB error in updatePassword", e);
        }
    }

    @Override
    public boolean deleteUser(String username) throws RemoteException {
        // Soft-delete via status field
        String sql = "UPDATE users SET status = 'inactive' WHERE username = ? AND status = 'active'";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RemoteException("DB error in deleteUser", e);
        }
    }
}
