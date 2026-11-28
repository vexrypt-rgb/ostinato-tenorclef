package baritone.gui.model;

import baritone.api.command.ArgDecl;
import baritone.api.command.FormDecl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: P3 auto-generated in-game command forms model (design doc: In-game menus —
 * "Command forms are auto-generated from argument types: coordinates with a use-crosshair control,
 * a block picker, a bot picker. The form always shows the equivalent # or @ command.").
 * Pure (no Minecraft imports, headless-testable).
 */
public final class CommandForms {

    public enum Section {
        ALL("All"),
        MOVEMENT("Movement"),
        BUILDING("Building"),
        SWARM("Swarm"),
        GENERAL("General");

        public final String label;

        Section(String label) {
            this.label = label;
        }
    }

    public record FormEntry(String command, FormDecl form, Section section, String permission) {}

    public record Preview(String line, String error) {
        public boolean ok() {
            return error == null || error.isEmpty();
        }
    }

    private CommandForms() {}

    public static Section classify(String commandName) {
        String c = commandName == null ? "" : commandName.trim().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "goto", "follow", "come", "home", "sethome", "explore", "path", "axis", "elytra" -> Section.MOVEMENT;
            case "mine", "build", "blueprint", "sel", "farm", "tunnel", "schematica", "litematica" -> Section.BUILDING;
            case "swarm", "role", "pov", "center", "theme" -> Section.SWARM;
            default -> Section.GENERAL;
        };
    }

    public static List<FormEntry> filter(List<FormEntry> all, Section section) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (section == null || section == Section.ALL) {
            return List.copyOf(all);
        }
        List<FormEntry> out = new ArrayList<>();
        for (FormEntry e : all) {
            if (e.section() == section) {
                out.add(e);
            }
        }
        return out;
    }

    /** Validates {@code values} against {@code entry.form()} and builds the exact routed command line (without {@code #}). */
    public static Preview buildCommand(FormEntry entry, Map<String, String> values, String targetBot) {
        if (entry == null || entry.form() == null) {
            return new Preview("", "no form selected");
        }
        try {
            String base = entry.form().line(values);
            String routed = InGamePalette.routeCommand(base, targetBot, List.of());
            return new Preview(routed, null);
        } catch (IllegalArgumentException e) {
            return new Preview(entry.form().prefix(), e.getMessage());
        }
    }

    /** Suggests a value for {@code arg} from the current crosshair target, or empty string if not applicable. */
    public static String crosshairFillFor(ArgDecl arg, RadialMenu.Crosshair hit) {
        if (arg == null || hit == null) {
            return "";
        }
        String n = arg.name().toLowerCase(Locale.ROOT);
        if (hit.hasBlock() && (n.contains("target") || n.contains("pos") || n.contains("coord")
                || n.contains("origin") || n.equals("x") || n.equals("xyz"))) {
            return hit.x() + " " + hit.y() + " " + hit.z();
        }
        if (hit.hasBlock() && !hit.blockId().isEmpty() && (n.contains("block") || n.contains("ore"))) {
            String id = hit.blockId();
            return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        }
        if (hit.entityName() != null && !hit.entityName().isBlank() && (n.contains("player") || n.contains("entity") || n.equals("name"))) {
            return hit.entityName();
        }
        return "";
    }
}
