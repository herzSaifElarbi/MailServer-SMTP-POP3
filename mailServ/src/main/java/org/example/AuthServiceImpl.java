package org.example;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {
    public AuthServiceImpl() throws RemoteException {
        super();
    }
    @Override
    public boolean userExists(String username) throws RemoteException {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";
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
        String sql = "SELECT password FROM users WHERE username = ?";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return password.equals(rs.getString("password"));
            }
        } catch (SQLException e) {
            throw new RemoteException("DB error in authenticate", e);
        }
    }
    @Override
    public boolean createUser(String username, String password) throws RemoteException {
        String sql = "INSERT INTO users(username,password) VALUES(?,?)";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            // Error code 1062 = duplicate entry
            if (e.getErrorCode() == 1062) {
                return false;
            }
            throw new RemoteException("DB error in createUser", e);
        }
    }
    @Override
    public boolean updatePassword(String username, String newPassword) throws RemoteException {
        String sql = "UPDATE users SET password = ? WHERE username = ?";
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
        String sql = "DELETE FROM users WHERE username = ?";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RemoteException("DB error in deleteUser", e);
        }
    }
}
