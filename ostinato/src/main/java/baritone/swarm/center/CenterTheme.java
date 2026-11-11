package baritone.swarm.center;

/**
 * Ostinato: the "Default dark" token set from the design doc (Color schemes / themes).
 * Colors are 0xRRGGBB so tests can check contrast without AWT. No theme editor and no flavor themes in v1.
 */
public record CenterTheme(
        int background,
        int surface,
        int raised,
        int border,
        int text,
        int muted,
        int faint,
        int accent,
        int onAccent,
        int ok,
        int warn,
        int danger
) {
    public static final CenterTheme DEFAULT_DARK = new CenterTheme(
            0x14161B, // background: window base
            0x1C2028, // surface: header, tiles, panels
            0x262B35, // raised: buttons, inputs
            0x343B48, // border
            0xE8EAEE, // text
            0xA9B0BD, // muted
            0x7D8594, // faint: section captions
            0x5B8DEF, // accent: selection, focus panel
            0x0B0D12, // onAccent
            0x46C46A, // ok
            0xE0A93A, // warn
            0xF06A60  // danger
    );

    /** WCAG relative-luminance contrast ratio between two 0xRRGGBB colors. */
    public static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
