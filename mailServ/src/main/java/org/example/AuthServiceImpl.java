package org.example;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.nio.file.*;
import com.google.gson.*;
import java.util.*;

public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {
    private final Path userDB = Paths.get("MailServer/users.json");

    public AuthServiceImpl() throws RemoteException {
        super();
        if (!Files.exists(userDB)) {
            try {
                Files.write(userDB, "[]".getBytes()); // Initialize empty JSON array
            } catch (IOException e) {
                throw new RemoteException("Failed to initialize user database");
            }
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
            String json = Files.readString(userDB);
            JsonArray users = JsonParser.parseString(json).getAsJsonArray();

            // Check if user already exists
            for (JsonElement user : users) {
                if (user.getAsJsonObject().get("username").getAsString().equals(username)) {
                    return false;
                }
            }

            // Add new user
            JsonObject newUser = new JsonObject();
            newUser.addProperty("username", username);
            newUser.addProperty("password", password);
            users.add(newUser);

            Files.write(userDB, new Gson().toJson(users).getBytes());
            return true;
        } catch (IOException e) {
            throw new RemoteException("Error updating user database");
        }
    }

    @Override
    public boolean deleteUser(String username) throws RemoteException {
        try {
            String json = Files.readString(userDB);
            JsonArray users = JsonParser.parseString(json).getAsJsonArray();
            JsonArray updatedUsers = new JsonArray();
            boolean found = false;

            for (JsonElement user : users) {
                JsonObject userObj = user.getAsJsonObject();
                if (!userObj.get("username").getAsString().equals(username)) {
                    updatedUsers.add(userObj);
                } else {
                    found = true;
                }
            }

            if (found) {
                Files.write(userDB, new Gson().toJson(updatedUsers).getBytes());
                return true;
            }
            return false;
        } catch (IOException e) {
            throw new RemoteException("Error updating user database");
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
            throw new RemoteException("Error updating user database");
        }
    }
}