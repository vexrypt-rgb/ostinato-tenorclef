package baritone.swarm.center.app;

import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeChoice;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Ostinato: P3 live custom theme editor drawer (design doc: In-game custom theme editor / Color schemes).
 * Start from any built-in theme, edit any of the 12 color tokens via hex input or interactive hue/saturation
 * picker + brightness slider, inspect live WCAG AA contrast warnings, live-preview onto the command center,
 * reset to base, save to {@code <prefsDir>/themes/<name>.json}, or import/export JSON files.
 *
 * <p>Names: {@code theme:ed}, {@code theme:ed:base}, {@code theme:ed:token}, {@code theme:ed:hex},
 * {@code theme:ed:wheel}, {@code theme:ed:val}, {@code theme:ed:name}, {@code theme:ed:path},
 * {@code theme:ed:save}, {@code theme:ed:reset}, {@code theme:ed:import}, {@code theme:ed:export},
 * {@code theme:ed:close}, {@code theme:ed:aa}, {@code theme:ed:status}.
 */
public final class ThemeEditorPanel {

    private final AppStyle style;
    private final Consumer<CenterTheme> onPreview;
    private final BiConsumer<String, CenterTheme> onSaved;
    private final Runnable onClose;
    private final JPanel root;
    private final JComboBox<String> baseBox;
    private final JComboBox<String> tokenBox;
    private final JTextField hexField;
    private final JTextField nameField;
    private final JTextField pathField;
    private final JSlider valSlider;
    private final HueSatPicker picker;
    private final JPanel swatch;
    private final JLabel aaLabel;
    private final JLabel statusLabel;
    private Path prefsDir;
    private String baseName = "dark";
    private CenterTheme draft;
    private boolean syncing;

    public ThemeEditorPanel(AppStyle style, CenterTheme initial, Consumer<CenterTheme> onPreview,
                            BiConsumer<String, CenterTheme> onSaved, Runnable onClose) {
        this.style = style;
        this.draft = initial == null ? CenterTheme.DEFAULT_DARK : initial;
        this.onPreview = onPreview == null ? t -> {} : onPreview;
        this.onSaved = onSaved == null ? (n, t) -> {} : onSaved;
        this.onClose = onClose == null ? () -> {} : onClose;

        root = style.panel(new BorderLayout(8, 4), style.surface());
        root.setName("theme:ed");
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, style.accent()),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        root.setVisible(false);

        picker = new HueSatPicker();
        picker.setName("theme:ed:wheel");
        root.add(picker, BorderLayout.WEST);

        JPanel controls = style.panel(new GridLayout(3, 1, 0, 2), null);

        JPanel r1 = row();
        r1.add(style.label("Theme editor · Base", style.muted(), Font.BOLD, 11f));
        baseBox = new JComboBox<>(CenterTheme.BUILT_IN_NAMES.toArray(new String[0]));
        baseBox.setName("theme:ed:base");
        baseBox.addActionListener(e -> {
            if (syncing) return;
            baseName = String.valueOf(baseBox.getSelectedItem());
            draft = CenterTheme.builtIn(baseName);
            syncTokenUi();
            notifyPreview();
        });
        r1.add(baseBox);

        r1.add(style.label("Token", style.muted(), Font.PLAIN, 11f));
        tokenBox = new JComboBox<>(CenterTheme.TOKENS.toArray(new String[0]));
        tokenBox.setName("theme:ed:token");
        tokenBox.addActionListener(e -> syncTokenUi());
        r1.add(tokenBox);

        hexField = style.field(8, "#RRGGBB", "Hex color for selected token");
        hexField.setName("theme:ed:hex");
        hexField.addActionListener(e -> applyHexField());
        r1.add(hexField);

        swatch = new JPanel();
        swatch.setPreferredSize(new Dimension(20, 20));
        swatch.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        r1.add(swatch);

        r1.add(style.label("V", style.faint(), Font.PLAIN, 10.5f));
        valSlider = new JSlider(0, 100, 80);
        valSlider.setName("theme:ed:val");
        valSlider.setOpaque(false);
        valSlider.setPreferredSize(new Dimension(90, 20));
        valSlider.addChangeListener(e -> {
            if (!syncing) {
                applyHsb(picker.hue, picker.sat, valSlider.getValue() / 100f);
            }
        });
        r1.add(valSlider);

        JButton applyHex = style.button("Apply");
        applyHex.setName("theme:ed:apply");
        applyHex.addActionListener(e -> applyHexField());
        r1.add(applyHex);
        controls.add(r1);

        JPanel r2 = row();
        nameField = style.field(10, "custom-name", "Name for saved custom theme");
        nameField.setName("theme:ed:name");
        nameField.setText("my-theme");
        r2.add(nameField);

        JButton save = style.button("Save custom");
        save.setName("theme:ed:save");
        save.addActionListener(e -> saveCustom());
        r2.add(save);

        JButton reset = style.button("Reset to base");
        reset.setName("theme:ed:reset");
        reset.addActionListener(e -> resetToBase());
        r2.add(reset);

        pathField = style.field(16, "path/to/theme.json", "JSON path for import or export");
        pathField.setName("theme:ed:path");
        r2.add(pathField);

        JButton imp = style.button("Import");
        imp.setName("theme:ed:import");
        imp.addActionListener(e -> importJson());
        r2.add(imp);

        JButton exp = style.button("Export");
        exp.setName("theme:ed:export");
        exp.addActionListener(e -> exportJson());
        r2.add(exp);

        JButton close = style.button("Close");
        close.setName("theme:ed:close");
        close.addActionListener(e -> {
            setVisible(false);
            this.onClose.run();
        });
        r2.add(close);
        controls.add(r2);

        JPanel r3 = style.panel(new BorderLayout(8, 0), null);
        aaLabel = style.label(" ", style.ok(), Font.PLAIN, 11f);
        aaLabel.setName("theme:ed:aa");
        statusLabel = style.label(" ", style.muted(), Font.PLAIN, 11f);
        statusLabel.setName("theme:ed:status");
        r3.add(aaLabel, BorderLayout.WEST);
        r3.add(statusLabel, BorderLayout.EAST);
        controls.add(r3);

        root.add(controls, BorderLayout.CENTER);
        syncTokenUi();
        refreshAa();
    }

    private JPanel row() {
        return style.panel(new FlowLayout(FlowLayout.LEFT, 5, 1), null);
    }

    public JComponent component() { return root; }
    public boolean visible() { return root.isVisible(); }
    public CenterTheme draft() { return draft; }

    public void setVisible(boolean on) {
        root.setVisible(on);
    }

    public void setPrefsDir(Path dir) {
        this.prefsDir = dir;
    }

    public void loadDraft(String currentName, CenterTheme theme) {
        this.draft = theme == null ? CenterTheme.DEFAULT_DARK : theme;
        String canonical = ThemeChoice.canonical(currentName);
        if (canonical != null) {
            baseName = canonical;
            syncing = true;
            baseBox.setSelectedItem(canonical);
            syncing = false;
        }
        syncTokenUi();
        refreshAa();
    }

    public void setTokenColor(String token, int rgb) {
        if (!CenterTheme.TOKENS.contains(token)) {
            return;
        }
        draft = draft.with(Map.of(token, rgb & 0xFFFFFF));
        syncTokenUi();
        refreshAa();
        notifyPreview();
    }

    private String selectedToken() {
        return String.valueOf(tokenBox.getSelectedItem());
    }

    private void syncTokenUi() {
        syncing = true;
        try {
            int rgb = draft.token(selectedToken());
            hexField.setText(CustomThemeStore.hex(rgb));
            swatch.setBackground(new Color(rgb));
            float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
            picker.setHS(hsb[0], hsb[1], hsb[2]);
            valSlider.setValue(Math.round(hsb[2] * 100f));
        } finally {
            syncing = false;
        }
    }

    private void applyHexField() {
        try {
            int rgb = CustomThemeStore.parseHex(hexField.getText());
            setTokenColor(selectedToken(), rgb);
            statusLabel.setText("updated " + selectedToken() + " = " + CustomThemeStore.hex(rgb));
        } catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
        }
    }

    private void applyHsb(float h, float s, float v) {
        int rgb = Color.HSBtoRGB(h, s, v) & 0xFFFFFF;
        setTokenColor(selectedToken(), rgb);
    }

    public void resetToBase() {
        baseName = String.valueOf(baseBox.getSelectedItem());
        draft = CenterTheme.builtIn(baseName);
        syncTokenUi();
        refreshAa();
        notifyPreview();
        statusLabel.setText("reset to " + CenterTheme.label(baseName));
    }

    public void saveCustom() {
        try {
            String n = CustomThemeStore.normalizeName(nameField.getText());
            Path saved = CustomThemeStore.save(prefsDir, n, baseName, draft);
            statusLabel.setText("saved " + saved.getFileName());
            onSaved.accept(n, draft);
        } catch (Exception e) {
            statusLabel.setText("save failed: " + e.getMessage());
        }
    }

    public void exportJson() {
        String p = pathField.getText().trim();
        if (p.isEmpty()) {
            statusLabel.setText("enter an export .json path");
            return;
        }
        try {
            Path target = Path.of(p);
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, CustomThemeStore.toJson(baseName, draft));
            statusLabel.setText("exported " + target.getFileName());
        } catch (Exception e) {
            statusLabel.setText("export failed: " + e.getMessage());
        }
    }

    public void importJson() {
        String p = pathField.getText().trim();
        if (p.isEmpty()) {
            statusLabel.setText("enter an import .json path");
            return;
        }
        try {
            draft = CustomThemeStore.parse(Files.readString(Path.of(p)));
            syncTokenUi();
            refreshAa();
            notifyPreview();
            statusLabel.setText("imported " + Path.of(p).getFileName());
        } catch (Exception e) {
            statusLabel.setText("import failed: " + e.getMessage());
        }
    }

    private void refreshAa() {
        List<String> problems = draft.aaProblems();
        if (problems.isEmpty()) {
            aaLabel.setText("✓ WCAG AA contrast pass");
            aaLabel.setForeground(style.ok());
        } else {
            String first = problems.get(0);
            String extra = problems.size() > 1 ? " (+" + (problems.size() - 1) + " more)" : "";
            aaLabel.setText("⚠ WCAG AA warning: " + first + extra);
            aaLabel.setForeground(style.warn());
        }
    }

    private void notifyPreview() {
        onPreview.accept(draft);
    }

    /** Interactive Hue (X) / Saturation (Y) picker pad. */
    private final class HueSatPicker extends JComponent {
        float hue = 0.6f;
        float sat = 0.7f;
        float val = 0.9f;

        HueSatPicker() {
            setPreferredSize(new Dimension(96, 76));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { pick(e.getX(), e.getY()); }
                @Override public void mouseDragged(MouseEvent e) { pick(e.getX(), e.getY()); }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        void setHS(float h, float s, float v) {
            this.hue = h;
            this.sat = s;
            this.val = v;
            repaint();
        }

        private void pick(int x, int y) {
            int w = Math.max(1, getWidth() - 1);
            int h = Math.max(1, getHeight() - 1);
            hue = Math.max(0f, Math.min(1f, x / (float) w));
            sat = Math.max(0f, Math.min(1f, 1f - y / (float) h));
            applyHsb(hue, sat, valSlider.getValue() / 100f);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            Graphics2D g2 = (Graphics2D) g;
            int step = 3;
            for (int py = 0; py < h; py += step) {
                float s = 1f - py / (float) Math.max(1, h - 1);
                for (int px = 0; px < w; px += step) {
                    float hu = px / (float) Math.max(1, w - 1);
                    g2.setColor(Color.getHSBColor(hu, s, Math.max(0.25f, val)));
                    g2.fillRect(px, py, step, step);
                }
            }
            int cx = Math.round(hue * (w - 1));
            int cy = Math.round((1f - sat) * (h - 1));
            g2.setColor(Color.BLACK);
            g2.drawOval(cx - 4, cy - 4, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawOval(cx - 3, cy - 3, 6, 6);
        }
    }
}
