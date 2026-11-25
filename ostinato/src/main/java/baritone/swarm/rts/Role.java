package baritone.swarm.rts;

import java.util.Locale;

/**
 * Ostinato: RTS mode. A standing job a bot repeats until it is given another one. No game types: the window, the
 * headless bot and the client all read the same names. See {@code docs/rts-mode.md}.
 */
public enum Role {
    IDLE('-', "idle"),
    FARMER('F', "farmer"),
    MINER('M', "miner"),
    STORAGE('S', "storage"),
    ARMORER('A', "armorer"),
    BUILDER('B', "builder");

    /** One letter for the map marker and the wall tile. */
    public final char letter;
    public final String id;

    Role(char letter, String id) {
        this.letter = letter;
        this.id = id;
    }

    /** Null for an unknown name. Accepts the id, the enum name and a few plain synonyms. */
    public static Role parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "idle", "none", "clear", "stop" -> {
                return IDLE;
            }
            case "farmer", "farm" -> {
                return FARMER;
            }
            case "miner", "mine" -> {
                return MINER;
            }
            case "storage", "sorter", "organizer", "storage_organizer" -> {
                return STORAGE;
            }
            case "armorer", "armourer", "armor", "armor_maker", "armormaker" -> {
                return ARMORER;
            }
            case "builder", "build" -> {
                return BUILDER;
            }
            default -> {
                return null;
            }
        }
    }

    /** Roles a player can assign from {@code #role}. Builder is set by {@code #blueprint live}, not by hand. */
    public boolean assignable() {
        return this != BUILDER;
    }
}
