package baritone.swarm.accounts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Ostinato: bot accounts under the Ostinato config directory.
 * The index has names only. Tokens live in a protected blob. Passwords are never stored.
 */
public final class AccountStore {

    public enum Kind { OFFLINE, MICROSOFT }

    public record Listed(String name, String uuid, Kind kind, String username, long expiryEpochMs) {
        @Override
        public String toString() {
            return "account " + name;
        }
    }

    private final Path dir;
    private final SecretProtector protector;

    public AccountStore(Path dir, SecretProtector protector) {
        this.dir = dir;
        this.protector = protector;
    }

    public boolean protectorMissing() {
        return protector == null;
    }

    public void addOffline(String name) throws IOException {
        String checked = check(name);
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + checked).getBytes(StandardCharsets.UTF_8));
        write(new Listed(checked, uuid.toString().replace("-", ""), Kind.OFFLINE, checked, 0), null);
    }

    public void remove(String name) throws IOException {
        String checked = check(name);
        List<Listed> next = new ArrayList<>();
        for (Listed listed : list()) {
            if (!listed.name.equalsIgnoreCase(checked)) {
                next.add(listed);
            }
        }
        saveIndex(next);
        Files.deleteIfExists(blob(checked));
    }

    public List<Listed> list() {
        Path index = index();
        if (!Files.isRegularFile(index)) {
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
            JsonArray accounts = root.getAsJsonArray("accounts");
            if (accounts == null) {
                return List.of();
            }
            List<Listed> out = new ArrayList<>();
            for (var element : accounts) {
                JsonObject item = element.getAsJsonObject();
                String name = text(item, "name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                Kind kind = "offline".equalsIgnoreCase(text(item, "kind")) ? Kind.OFFLINE : Kind.MICROSOFT;
                long expiry = item.has("expiry") ? item.get("expiry").getAsLong() : 0L;
                out.add(new Listed(name, text(item, "uuid"), kind, text(item, "username"), expiry));
            }
            return List.copyOf(out);
        } catch (RuntimeException | IOException ignored) {
            return List.of();
        }
    }

    public Listed find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Listed listed : list()) {
            if (listed.name.equalsIgnoreCase(name) || (listed.username != null && listed.username.equalsIgnoreCase(name))) {
                return listed;
            }
        }
        return null;
    }

    public boolean needsRefresh(String name) {
        Listed listed = find(name);
        if (listed == null || listed.kind != Kind.MICROSOFT || listed.expiryEpochMs <= 0) {
            return false;
        }
        return listed.expiryEpochMs < System.currentTimeMillis() + 60_000L;
    }

    public baritone.swarm.spawn.SpawnSession session(String name) throws IOException {
        Listed listed = find(name);
        if (listed == null) {
            return null;
        }
        if (listed.kind == Kind.OFFLINE) {
            return new baritone.swarm.spawn.SpawnSession(listed.username, listed.uuid, null);
        }
        AuthBlob.Decoded decoded = readBlob(listed.name);
        return new baritone.swarm.spawn.SpawnSession(decoded.username(), decoded.uuid(), decoded.access());
    }

    public String refreshToken(String name) throws IOException {
        Listed listed = find(name);
        if (listed == null || listed.kind != Kind.MICROSOFT) {
            return "";
        }
        return readBlob(listed.name).refresh();
    }

    public void saveMicrosoft(String username, String uuid, String access, String refresh, long expiry) throws IOException {
        if (protector == null) {
            throw new IOException("Windows DPAPI is not available");
        }
        String name = check(username);
        if (access == null || access.isBlank() || uuid == null || uuid.isBlank()) {
            throw new IOException("could not write account");
        }
        byte[] plain = AuthBlob.encode(name, uuid, access, refresh, expiry);
        try {
            write(new Listed(name, uuid, Kind.MICROSOFT, name, expiry), protector.protect(plain));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** Import one launcher profile. Returns null when that name is absent. Does not write the launcher file. */
    public Listed importNamed(String name, List<Path> launcherFiles) throws IOException {
        if (protector == null) {
            throw new IOException("Windows DPAPI is not available");
        }
        for (LauncherProfile profile : LauncherProfile.read(launcherFiles)) {
            if (profile.name.equalsIgnoreCase(name)) {
                saveMicrosoft(profile.name, profile.uuid, profile.accessToken, profile.refreshToken, 0L);
                return find(profile.name);
            }
        }
        return null;
    }

    public int importAll(List<Path> launcherFiles) throws IOException {
        if (protector == null) {
            throw new IOException("Windows DPAPI is not available");
        }
        int count = 0;
        for (LauncherProfile profile : LauncherProfile.read(launcherFiles)) {
            saveMicrosoft(profile.name, profile.uuid, profile.accessToken, profile.refreshToken, 0L);
            count++;
        }
        return count;
    }

    private AuthBlob.Decoded readBlob(String name) throws IOException {
        if (protector == null) {
            throw new IOException("Windows DPAPI is not available");
        }
        Path file = blob(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("could not read account");
        }
        byte[] plain = protector.unprotect(Files.readAllBytes(file));
        try {
            return AuthBlob.decode(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private void write(Listed listed, byte[] protectedBlob) throws IOException {
        Files.createDirectories(dir);
        if (protectedBlob != null) {
            PrivateFiles.writeRestricted(blob(listed.name), protectedBlob);
        } else {
            Files.deleteIfExists(blob(listed.name));
        }
        List<Listed> next = new ArrayList<>();
        for (Listed existing : list()) {
            if (!existing.name.equalsIgnoreCase(listed.name)) {
                next.add(existing);
            }
        }
        next.add(listed);
        saveIndex(next);
    }

    private void saveIndex(List<Listed> accounts) throws IOException {
        JsonArray array = new JsonArray();
        for (Listed listed : accounts) {
            JsonObject item = new JsonObject();
            item.addProperty("name", listed.name);
            item.addProperty("uuid", listed.uuid == null ? "" : listed.uuid);
            item.addProperty("kind", listed.kind == Kind.OFFLINE ? "offline" : "microsoft");
            item.addProperty("username", listed.username == null ? listed.name : listed.username);
            item.addProperty("expiry", listed.expiryEpochMs);
            array.add(item);
        }
        JsonObject root = new JsonObject();
        root.add("accounts", array);
        Files.createDirectories(dir);
        Path pending = Files.createTempFile(dir, ".index-", ".tmp");
        try {
            Files.writeString(pending, root.toString());
            try {
                Files.move(pending, index(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, index(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private Path index() {
        return dir.resolve("index.json");
    }

    private Path blob(String name) {
        return dir.resolve(name.toLowerCase(Locale.ROOT) + ".bin");
    }

    private static String check(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Account name must match [A-Za-z0-9_]{1,16}");
        }
        return name;
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }
}
