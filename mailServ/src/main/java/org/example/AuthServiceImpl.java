package org.example;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.nio.file.*;
import com.google.gson.*;
import java.util.*;

public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {
    private final Path userDB = Paths.get("MailServer/users.json");
    private final Path mailDir = Paths.get("MailServer");
    @Override
    public boolean userExists(String username) throws RemoteException {
        try {
            String json = Files.readString(userDB);
            JsonArray users = JsonParser.parseString(json).getAsJsonArray();

            for (JsonElement user : users) {
                if (user.getAsJsonObject().get("username").getAsString().equals(username)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new RemoteException("Error reading user database");
        }
    }
    public AuthServiceImpl() throws RemoteException {
        super();
        try {
            if (!Files.exists(userDB)) {
                Files.createDirectories(mailDir);  // Ensure MailServer exists
                Files.write(userDB, "[]".getBytes());
            }
        } catch (IOException e) {
            throw new RemoteException("Failed to initialize user database");
        }
    }

    @Override
    public boolean authenticate(String username, String password) throws RemoteException {
        try {
            String json = Files.readString(userDB);
            JsonArray users = JsonParser.parseString(json).getAsJsonArray();

            for (JsonElement user : users) {
                JsonObject userObj = user.getAsJsonObject();
                if (userObj.get("username").getAsString().equals(username) &&
                        userObj.get("password").getAsString().equals(password)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new RemoteException("Error reading user database");
        }
    }

    @Override
    public boolean createUser(String username, String password) throws RemoteException {
        try {
            // Check if user already exists
            String json = Files.readString(userDB);
            JsonArray users = JsonParser.parseString(json).getAsJsonArray();

            for (JsonElement user : users) {
                if (user.getAsJsonObject().get("username").getAsString().equals(username)) {
                    return false;
                }
            }

            // Create user directory
            Path userDir = mailDir.resolve(username);
            if (!Files.exists(userDir)) {
                Files.createDirectory(userDir);
            }

            // Add to JSON database
            JsonObject newUser = new JsonObject();
            newUser.addProperty("username", username);
            newUser.addProperty("password", password);
            users.add(newUser);

            Files.write(userDB, new Gson().toJson(users).getBytes());
            return true;
        } catch (IOException e) {
            throw new RemoteException("Error creating user: " + e.getMessage());
        }
    }

    @Override
    public boolean deleteUser(String username) throws RemoteException {
        try {
            String json = Files.readString(userDB);
            JsonArray users = JsonParser.parseString(json).getAsJsonArray();
            JsonArray updatedUsers = new JsonArray();
            boolean found = false;

            // Remove from JSON
            for (JsonElement user : users) {
                JsonObject userObj = user.getAsJsonObject();
                if (!userObj.get("username").getAsString().equals(username)) {
                    updatedUsers.add(userObj);
                } else {
                    found = true;
                }
            }

            if (found) {
                // Delete mail directory
                Path userDir = mailDir.resolve(username);
                if (Files.exists(userDir)) {
                    deleteDirectoryRecursively(userDir);
                }

                // Update database
                Files.write(userDB, new Gson().toJson(updatedUsers).getBytes());
                return true;
            }
            return false;
        } catch (IOException e) {
            throw new RemoteException("Error deleting user: " + e.getMessage());
        }
    }

    @Override
    public boolean updatePassword(String username, String newPassword) throws RemoteException {
        try {
            String json = Files.readString(userDB);
            JsonArray users = JsonParser.parseString(json).getAsJsonArray();
            boolean updated = false;

            for (JsonElement user : users) {
                JsonObject userObj = user.getAsJsonObject();
                if (userObj.get("username").getAsString().equals(username)) {
                    userObj.addProperty("password", newPassword);
                    updated = true;
                    break;
                }
            }

            if (updated) {
                Files.write(userDB, new Gson().toJson(users).getBytes());
                return true;
            }
            return false;
        } catch (IOException e) {
            throw new RemoteException("Error updating password");
        }
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    deleteDirectoryRecursively(entry);
                }
            }
        }
        Files.delete(path);
    }
}