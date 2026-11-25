package baritone.swarm.center;

/**
 * Ostinato: the command-center layouts. Wall is the default. Map is the war-room. Editor is the 3D
 * Design/Live schematic view ({@code docs/schematic-editor.md}).
 */
public enum CenterLayout {
    WALL,
    MAP,
    EDITOR;

    public static CenterLayout parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("layout is wall, map or editor");
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "wall", "tiles" -> WALL;
            case "map", "war", "warroom" -> MAP;
            case "editor", "schematic", "studio" -> EDITOR;
            default -> throw new IllegalArgumentException("layout is wall, map or editor");
        };
    }

    public String wire() {
        return switch (this) {
            case WALL -> "wall";
            case MAP -> "map";
            case EDITOR -> "editor";
        };
    }

    public String label() {
        return switch (this) {
            case WALL -> "Wall";
            case MAP -> "War room";
            case EDITOR -> "Editor";
        };
    }

    public String formId() {
        return "layout-" + wire();
    }
}
