package org.example;
import java.rmi.registry.Registry;
import java.rmi.registry.LocateRegistry;

public class RMIAuthServer {
    public static void main(String[] args) {
        try {
            AuthService authService = new AuthServiceImpl();
            Registry registry = LocateRegistry.createRegistry(1099); // Default RMI port
            registry.rebind("AuthService", authService);
            System.out.println("✅ RMI Authentication Server is running...");
        } catch (Exception e) {
            System.err.println("❌ Server exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}