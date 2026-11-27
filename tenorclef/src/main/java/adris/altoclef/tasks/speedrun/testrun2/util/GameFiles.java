package adris.altoclef.tasks.speedrun.testrun2.util;

import adris.altoclef.headless.BotClientBridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class GameFiles {

    private GameFiles() {}

    public static Path dir() {
        return BotClientBridge.runDirectory().resolve("altoclef");
    }

    public static void write(String name, String body) {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve(name), body, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }

    public static String read(String name) {
        try {
            Path p = dir().resolve(name);
            if (!Files.exists(p)) return null;
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void append(String name, String line) {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve(name), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {}
    }
}
