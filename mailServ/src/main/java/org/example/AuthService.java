package org.example;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface AuthService extends Remote {
    boolean authenticate(String username, String password) throws RemoteException;
    boolean createUser(String username, String password) throws RemoteException;
    boolean deleteUser(String username) throws RemoteException;
    boolean updatePassword(String username, String newPassword) throws RemoteException;
}