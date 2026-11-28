package adris.altoclef.tasks.speedrun.testrun2;

import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.testrun2.util.GameFiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Wall-clock and game-tick speedrun splits with persistent personal bests,
 * published to Ostinato's Command Center via {@link baritone.api.swarm.ISwarm#publishSpeedrunSplits(String)}.
 */
public final class SpeedrunClock {

    public record SplitRecord(long ms, long ticks, long pbMs) {}

    private static long start;
    private static long tickCount;
    private static String currentPhase = "";
    private static final Map<String, SplitRecord> splits = new LinkedHashMap<>();
    private static final Map<String, Long> personalBests = new LinkedHashMap<>();
    private static boolean pbLoaded;
    private static long lastPublishMs;

    private SpeedrunClock() {}

    public static synchronized void reset() {
        ensurePbLoaded();
        start = System.currentTimeMillis();
        tickCount = 0L;
        currentPhase = "start";
        splits.clear();
        Debug.logMessage("TESRUN2 clock 0:00.0");
        publishNow(true);
    }

    /** Advance the speedrun game-tick counter (called once per game tick while active). */
    public static synchronized void tick() {
        if (start == 0) {
            return;
        }
        tickCount++;
        publishNow(false);
    }

    public static synchronized void setPhase(String phase) {
        if (phase != null && !phase.equals(currentPhase)) {
            currentPhase = phase;
            publishNow(true);
        }
    }

    public static synchronized void split(String name) {
        if (start == 0) reset();
        ensurePbLoaded();
        long t = System.currentTimeMillis() - start;
        long prevPb = personalBests.getOrDefault(name, -1L);
        if (prevPb < 0 || t < prevPb) {
            personalBests.put(name, t);
            savePbsQuietly();
        }
        long pbForDisplay = prevPb < 0 ? t : prevPb;
        splits.put(name, new SplitRecord(t, tickCount, pbForDisplay));
        currentPhase = name;
        String delta = prevPb < 0 ? "" : " (PB " + fmtDelta(t - prevPb) + ")";
        Debug.logMessage("TESRUN2 split " + name + "  " + fmt(t) + " [" + tickCount + "t]" + delta);
        publishNow(true);
    }

    public static synchronized String now() {
        if (start == 0) return "0:00.0";
        return fmt(System.currentTimeMillis() - start);
    }

    public static synchronized long millis() {
        return start == 0 ? 0 : System.currentTimeMillis() - start;
    }

    public static synchronized long ticks() {
        return tickCount;
    }

    public static synchronized Map<String, SplitRecord> records() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(splits));
    }

    public static synchronized long personalBest(String name) {
        ensurePbLoaded();
        return personalBests.getOrDefault(name, -1L);
    }

    public static synchronized String encodeForFeed() {
        if (start == 0 && splits.isEmpty()) {
            return "";
        }
        ensurePbLoaded();
        long elapsed = millis();
        long totalPb = personalBests.getOrDefault("total", -1L);
        StringBuilder sb = new StringBuilder();
        sb.append(clean(currentPhase)).append('|')
                .append(elapsed).append('|')
                .append(tickCount).append('|')
                .append(totalPb).append('|');
        boolean first = true;
        for (Map.Entry<String, SplitRecord> e : splits.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            SplitRecord r = e.getValue();
            sb.append(clean(e.getKey())).append('=')
                    .append(r.ms()).append(':')
                    .append(r.ticks()).append(':')
                    .append(r.pbMs());
        }
        return sb.toString();
    }

    private static void publishNow(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPublishMs < 1000L) {
            return;
        }
        lastPublishMs = now;
        try {
            var baritone = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && baritone.getSwarm() != null) {
                baritone.getSwarm().publishSpeedrunSplits(encodeForFeed());
            }
        } catch (Throwable ignored) {
            // Public API optional hook; safe to ignore in unit tests.
        }
    }

    private static void ensurePbLoaded() {
        if (pbLoaded) {
            return;
        }
        pbLoaded = true;
        try {
            Path f = pbFile();
            if (Files.isRegularFile(f)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(f)) {
                    p.load(in);
                }
                for (String key : p.stringPropertyNames()) {
                    try {
                        personalBests.put(key, Long.parseLong(p.getProperty(key).trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void savePbsQuietly() {
        try {
            Path f = pbFile();
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            Properties p = new Properties();
            for (Map.Entry<String, Long> e : personalBests.entrySet()) {
                p.setProperty(e.getKey(), Long.toString(e.getValue()));
            }
            try (OutputStream out = Files.newOutputStream(f)) {
                p.store(out, "TenorClef Speedrun Personal Bests (ms)");
            }
        } catch (Throwable ignored) {
        }
    }

    private static Path pbFile() {
        return GameFiles.dir().resolve("speedrun-pb.properties");
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder("TESRUN2 splits");
        long prev = 0;
        for (var e : splits.entrySet()) {
            SplitRecord r = e.getValue();
            sb.append(" | ").append(e.getKey()).append(' ').append(fmt(r.ms() - prev))
                    .append(" (").append(r.ticks()).append("t)");
            prev = r.ms();
        }
        sb.append(" | total ").append(now()).append(" (").append(tickCount).append("t)");
        return sb.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[|,=:]", "_");
    }

    private static String fmt(long ms) {
        long min = ms / 60_000;
        long sec = (ms / 1000) % 60;
        long tenths = (ms / 100) % 10;
        return String.format("%d:%02d.%d", min, sec, tenths);
    }

    private static String fmtDelta(long deltaMs) {
        String sign = deltaMs >= 0 ? "+" : "-";
        return sign + fmt(Math.abs(deltaMs));
    }
}
