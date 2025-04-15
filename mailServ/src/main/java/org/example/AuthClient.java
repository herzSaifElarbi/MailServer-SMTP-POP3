package org.example;

import java.rmi.registry.*;

public class AuthClient {
    public static void main(String[] args) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            AuthService authService = (AuthService) registry.lookup("AuthService");

            // Test authentication
            System.out.println("Authenticating user1:password1 → " +
                    authService.authenticate("user1", "password1")); // Should return true

            // Test creating a new user
            System.out.println("Creating user4 → " +
                    authService.createUser("user4", "password4")); // Should return true

            // Test updating password
            System.out.println("Updating user1's password → " +
                    authService.updatePassword("user1", "newpass123")); // Should return true

            // Test deleting a user
            System.out.println("Deleting user2 → " +
                    authService.deleteUser("user2")); // Should return true
        } catch (Exception e) {
            System.err.println("❌ Client exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}