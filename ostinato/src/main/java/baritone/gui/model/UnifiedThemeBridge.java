package baritone.gui.model;

import baritone.swarm.center.CenterTheme;

/**
 * Ostinato: P3 unified theme token bridge between {@link CenterTheme} (command center + {@code #theme <name>})
 * and the in-game GUI screens ({@code Theme}, {@code RadialScreen}, {@code PaletteScreen}, {@code CommandsPanel}).
 * Pure (no Minecraft imports, headless-testable).
 */
public final class UnifiedThemeBridge {

    public static final String SYNC_PROPERTY = "ostinato.syncInGameTheme";

    private UnifiedThemeBridge() {}

    /** True unless explicitly disabled via {@code -Dostinato.syncInGameTheme=false}. */
    public static boolean syncEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(SYNC_PROPERTY, "true"));
    }

    /** Converts a 24-bit RGB {@link CenterTheme} token to 32-bit ARGB with the given alpha (0..255). */
    public static int argb(int rgb24, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (rgb24 & 0xFFFFFF);
    }

    public static int accent(CenterTheme theme, int fallbackArgb) {
        if (theme == null || !syncEnabled() || CenterTheme.DEFAULT_DARK.equals(theme)) {
            return fallbackArgb;
        }
        return argb(theme.accent(), 0xFF);
    }

    public static int text(CenterTheme theme, int fallbackArgb) {
        if (theme == null || !syncEnabled() || CenterTheme.DEFAULT_DARK.equals(theme)) {
            return fallbackArgb;
        }
        return argb(theme.text(), 0xFF);
    }

    public static int surface(CenterTheme theme, int alpha, int fallbackArgb) {
        if (theme == null || !syncEnabled() || CenterTheme.DEFAULT_DARK.equals(theme)) {
            return fallbackArgb;
        }
        return argb(theme.surface(), alpha);
    }

    public static int border(CenterTheme theme, int fallbackArgb) {
        if (theme == null || !syncEnabled() || CenterTheme.DEFAULT_DARK.equals(theme)) {
            return fallbackArgb;
        }
        return argb(theme.border(), 0xFF);
    }

    public static int ok(CenterTheme theme, int fallbackArgb) {
        if (theme == null || !syncEnabled() || CenterTheme.DEFAULT_DARK.equals(theme)) {
            return fallbackArgb;
        }
        return argb(theme.ok(), 0xFF);
    }

    public static int warn(CenterTheme theme, int fallbackArgb) {
        if (theme == null || !syncEnabled() || CenterTheme.DEFAULT_DARK.equals(theme)) {
            return fallbackArgb;
        }
        return argb(theme.warn(), 0xFF);
    }

    public static int danger(CenterTheme theme, int fallbackArgb) {
        if (theme == null || !syncEnabled() || CenterTheme.DEFAULT_DARK.equals(theme)) {
            return fallbackArgb;
        }
        return argb(theme.danger(), 0xFF);
    }
}
