/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Credentials for a single headless bot instance.
 *
 * <p>Supports both offline-mode bots (username + derived offline UUID) and
 * authenticated online-mode bots (username + Mojang UUID + access token).
 */
public record HeadlessAccount(String username, UUID uuid, String accessToken) {

    public HeadlessAccount {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        username = username.trim();
        uuid = uuid != null ? uuid : offlineUuid(username);
        accessToken = accessToken == null ? "" : accessToken.trim();
    }

    public static HeadlessAccount offline(String username) {
        return new HeadlessAccount(username, offlineUuid(username), "");
    }

    public static UUID offlineUuid(String username) {
        Objects.requireNonNull(username, "username");
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username.trim()).getBytes(StandardCharsets.UTF_8));
    }

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * Parses a Mojang UUID string with or without hyphens (32-char hex or canonical 36-char UUID).
     */
    public static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || "-".equals(s)) {
            return null;
        }
        if (s.length() == 32 && s.indexOf('-') < 0) {
            s = s.substring(0, 8) + '-'
                    + s.substring(8, 12) + '-'
                    + s.substring(12, 16) + '-'
                    + s.substring(16, 20) + '-'
                    + s.substring(20, 32);
        }
        return UUID.fromString(s);
    }

    /**
     * Loads accounts from JSON or delimited text (CSV / colon-separated).
     *
     * <p>Supported formats:
     * <ul>
     *   <li>JSON array of strings or objects ({@code username}/{@code name}, optional {@code uuid}/{@code id},
     *       optional {@code accessToken}/{@code access_token}/{@code token})</li>
     *   <li>JSON object containing an {@code accounts} array</li>
     *   <li>Plain text lines ({@code #} comments allowed): {@code username},
     *       {@code username,uuid}, or {@code username,uuid,accessToken} (comma or colon delimiter)</li>
     * </ul>
     */
    public static List<HeadlessAccount> load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String trimmed = stripBom(content).trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return parseJson(trimmed);
        }
        return parseDelimited(content);
    }

    private static String stripBom(String s) {
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    private static List<HeadlessAccount> parseJson(String json) {
        JsonElement root = JsonParser.parseString(json);
        List<HeadlessAccount> accounts = new ArrayList<>();
        if (root.isJsonArray()) {
            parseJsonArray(root.getAsJsonArray(), accounts);
        } else if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("accounts") && obj.get("accounts").isJsonArray()) {
                parseJsonArray(obj.getAsJsonArray("accounts"), accounts);
            } else {
                accounts.add(parseJsonObject(obj));
            }
        } else {
            throw new IllegalArgumentException("Expected JSON array or object for accounts file");
        }
        return List.copyOf(accounts);
    }

    private static void parseJsonArray(JsonArray arr, List<HeadlessAccount> out) {
        for (JsonElement elem : arr) {
            if (elem.isJsonPrimitive()) {
                out.add(offline(elem.getAsString()));
            } else if (elem.isJsonObject()) {
                out.add(parseJsonObject(elem.getAsJsonObject()));
            } else {
                throw new IllegalArgumentException("Unsupported account entry: " + elem);
            }
        }
    }

    private static HeadlessAccount parseJsonObject(JsonObject obj) {
        String username = firstString(obj, "username", "name");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Account entry missing username/name: " + obj);
        }
        String uuidRaw = firstString(obj, "uuid", "id");
        String token = firstString(obj, "accessToken", "access_token", "token");
        return new HeadlessAccount(username, parseUuid(uuidRaw), token);
    }

    private static String firstString(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsString();
            }
        }
        return null;
    }

    private static List<HeadlessAccount> parseDelimited(String content) {
        List<HeadlessAccount> accounts = new ArrayList<>();
        String[] lines = content.split("\\R");
        for (String rawLine : lines) {
            String line = stripBom(rawLine).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            String delimiter = line.indexOf(',') >= 0 ? "," : ":";
            String[] parts = line.split(delimiter, 3);
            String username = parts[0].trim();
            UUID uuid = parts.length >= 2 ? parseUuid(parts[1]) : null;
            String token = parts.length >= 3 ? parts[2].trim() : "";
            accounts.add(new HeadlessAccount(username, uuid, token));
        }
        return List.copyOf(accounts);
    }
}
