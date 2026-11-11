package baritone.swarm.accounts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: read launcher profiles as an import source. Password fields are never copied.
 * This class does not write the launcher file and does not log the JSON.
 */
final class LauncherProfile {

    final String name;
    final String uuid;
    final String accessToken;
    final String refreshToken;

    private LauncherProfile(String name, String uuid, String accessToken, String refreshToken) {
        this.name = name;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    @Override
    public String toString() {
        return "launcher profile [redacted]";
    }

    static List<LauncherProfile> read(List<Path> files) throws IOException {
        List<LauncherProfile> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        for (Path file : files) {
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonObject accounts = root.getAsJsonObject("accounts");
                if (accounts == null) {
                    continue;
                }
                for (var entry : accounts.entrySet()) {
                    LauncherProfile profile = one(entry.getValue());
                    if (profile != null) {
                        out.add(profile);
                    }
                }
            } catch (RuntimeException ignored) {
                // Parser text can echo the file. Drop it.
            }
        }
        return out;
    }

    private static LauncherProfile one(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject account = element.getAsJsonObject();
        JsonObject profile = account.getAsJsonObject("minecraftProfile");
        if (profile == null) {
            return null;
        }
        String name = text(profile, "name");
        String uuid = text(profile, "id");
        String access = text(account, "accessToken");
        if (name == null || uuid == null || access == null || access.isBlank()) {
            return null;
        }
        return new LauncherProfile(name, uuid, access, text(account, "refreshToken"));
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value.getAsString();
    }
}
