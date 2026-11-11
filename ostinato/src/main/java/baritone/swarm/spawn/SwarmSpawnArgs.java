package baritone.swarm.spawn;

import java.util.ArrayList;
import java.util.List;

// Ostinato: Minecraft-independent command grammar and bounded name expansion.
public record SwarmSpawnArgs(List<String> names, String server) {
    public static String name(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Bot name must match [A-Za-z0-9_]{1,16}");
        }
        return value;
    }

    public static SwarmSpawnArgs parse(List<String> args) {
        if (args.isEmpty() || args.size() > 3) {
            throw new IllegalArgumentException("Usage: swarm spawn <name> [count] [server]");
        }
        String base = name(args.get(0));
        int count = 1;
        int next = 1;
        if (args.size() > 1 && args.get(1).matches("[+-]?[0-9]+")) {
            try {
                count = Integer.parseInt(args.get(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Spawn count must be between 1 and 16");
            }
            next++;
        }
        if (count < 1 || count > 16) {
            throw new IllegalArgumentException("Spawn count must be between 1 and 16");
        }
        String server = next < args.size() ? args.get(next++) : null;
        if (next < args.size()) {
            throw new IllegalArgumentException("Usage: swarm spawn <name> [count] [server]");
        }
        if (server != null && (server.isBlank() || server.startsWith("-") || server.matches(".*\\s.*"))) {
            throw new IllegalArgumentException("Server must be a host or host:port");
        }
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String suffix = Integer.toString(i);
            names.add(count == 1 ? base : base.substring(0, Math.min(base.length(), 16 - suffix.length())) + suffix);
        }
        return new SwarmSpawnArgs(List.copyOf(names), server);
    }

    public static String despawn(List<String> args) {
        if (args.size() != 1) {
            throw new IllegalArgumentException("Usage: swarm despawn <name|all>");
        }
        return args.get(0).equalsIgnoreCase("all") ? "all" : name(args.get(0));
    }
}
