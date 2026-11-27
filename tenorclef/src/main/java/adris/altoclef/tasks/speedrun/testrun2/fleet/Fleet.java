package adris.altoclef.tasks.speedrun.testrun2.fleet;

import adris.altoclef.headless.TenorClefBotContext;
import adris.altoclef.tasks.speedrun.testrun2.util.GameFiles;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Linked TenorClef names. File: altoclef/fleet.txt one IGN per line. */
public final class Fleet {

    public static final String FILE = "fleet.txt";

    private static final Set<String> NAMES = new LinkedHashSet<>();

    private Fleet() {}

    private static Set<String> namesSet() {
        TenorClefBotContext tc = TenorClefBotContext.currentOrNull();
        return tc != null ? tc.fleetMembers : NAMES;
    }

    private static Object namesLock() {
        TenorClefBotContext tc = TenorClefBotContext.currentOrNull();
        return tc != null ? tc.fleetLock : NAMES;
    }

    public static void load() {
        synchronized (namesLock()) {
            Set<String> set = namesSet();
            set.clear();
            String raw = GameFiles.read(FILE);
            if (raw == null) return;
            for (String line : raw.split("\n")) {
                String n = line.trim();
                if (n.isEmpty() || n.startsWith("#")) continue;
                set.add(n);
            }
        }
    }

    public static void save() {
        synchronized (namesLock()) {
            StringBuilder b = new StringBuilder("# TenorClef fleet — one IGN per line\n");
            for (String n : namesSet()) b.append(n).append('\n');
            GameFiles.write(FILE, b.toString());
        }
    }

    public static void add(String name) {
        if (name == null || name.isBlank()) return;
        synchronized (namesLock()) {
            load();
            namesSet().add(name.trim());
            save();
        }
    }

    public static void remove(String name) {
        synchronized (namesLock()) {
            load();
            namesSet().remove(name);
            save();
        }
    }

    public static List<String> members() {
        synchronized (namesLock()) {
            load();
            return new ArrayList<>(namesSet());
        }
    }

    public static boolean known(String name) {
        synchronized (namesLock()) {
            load();
            for (String n : namesSet()) {
                if (n.equalsIgnoreCase(name)) return true;
            }
            return false;
        }
    }
}
