package baritone.swarm.center.app;

import baritone.api.command.CommandPermission;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.CatalogFeed;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Ostinato: P3 inline Ctrl+K command palette, presets, dry-run preview, command history, and favorites for the
 * command-center window (design doc: Command center layout &amp; Presets, preview and permissions).
 * Never opens a dialog and never steals window focus.
 *
 * <p>Names: {@code palette}, {@code palette:input}, {@code palette:target}, {@code palette:dryrun},
 * {@code palette:run}, {@code palette:fav}, {@code palette:savePreset}, {@code palette:close},
 * {@code palette:matches}, {@code palette:preview}, {@code palette:output}, {@code palette:presets}.
 */
public final class CommandPalette {

    public static final int MAX_HISTORY = 30;

    public record Item(String label, String line, String permission, String kind) {}

    private static final Set<String> RISKY_HEADS = Set.of("despawn", "cleararea", "sel", "build", "tunnel", "stop");

    private final AppStyle style;
    private final Commands commands;
    private final Supplier<List<String>> selectedSupplier;
    private final Runnable onClose;
    private final JPanel root;
    private final JTextField input;
    private final JComboBox<String> target;
    private final JToggleButton dryRun;
    private final JLabel preview;
    private final JLabel output;
    private final JPanel matchesRow;
    private final JPanel presetsRow;
    private final List<String> history = new ArrayList<>();
    private final LinkedHashSet<String> favorites = new LinkedHashSet<>();
    private final Map<String, String> presets = new LinkedHashMap<>();
    private SwarmView view = SwarmView.EMPTY;
    private Path storeFile;

    public CommandPalette(AppStyle style, Commands commands, Supplier<List<String>> selectedSupplier, Runnable onClose) {
        this.style = style;
        this.commands = commands;
        this.selectedSupplier = selectedSupplier == null ? List::of : selectedSupplier;
        this.onClose = onClose == null ? () -> {} : onClose;
        presets.put("Go home", "home");
        presets.put("Come", "come");
        presets.put("Stop", "stop");
        presets.put("Farm", "farm");
        presets.put("Mine coal", "mine coal_ore");

        root = style.panel(new GridLayout(4, 1, 0, 2), style.surface());
        root.setName("palette");
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, style.accent()),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        root.setVisible(false);

        JPanel row1 = style.panel(new FlowLayout(FlowLayout.LEFT, 6, 1), null);
        row1.add(style.label("Ctrl+K", style.accent(), Font.BOLD, 11f));
        input = style.field(26, "command or search...", "Type a # command or search catalog, presets, history");
        input.setName("palette:input");
        input.addActionListener(e -> execute());
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshSuggestions(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshSuggestions(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshSuggestions(); }
        });
        row1.add(input);

        target = new JComboBox<>(new String[]{"Host", "Selected", "All bots"});
        target.setName("palette:target");
        dryRun = style.toggle("Dry-run");
        dryRun.setName("palette:dryrun");
        dryRun.setToolTipText("Preview exact commands without executing");
        dryRun.addActionListener(e -> refreshPreview());
        target.addActionListener(e -> {
            if ("All bots".equals(target.getSelectedItem())) {
                dryRun.setSelected(true);
            }
            refreshPreview();
        });
        row1.add(target);
        row1.add(dryRun);

        JButton run = style.button("Run");
        run.setName("palette:run");
        run.addActionListener(e -> execute());
        row1.add(run);

        JButton fav = style.button("★ Fav");
        fav.setName("palette:fav");
        fav.setToolTipText("Toggle current command as a favorite");
        fav.addActionListener(e -> toggleFavorite());
        row1.add(fav);

        JButton savePreset = style.button("+ Preset");
        savePreset.setName("palette:savePreset");
        savePreset.setToolTipText("Save current command line as a reusable preset");
        savePreset.addActionListener(e -> saveCurrentAsPreset());
        row1.add(savePreset);

        JButton close = style.button("Close");
        close.setName("palette:close");
        close.addActionListener(e -> {
            setVisible(false);
            this.onClose.run();
        });
        row1.add(close);
        root.add(row1);

        matchesRow = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 1), null);
        matchesRow.setName("palette:matches");
        root.add(matchesRow);

        presetsRow = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 1), null);
        presetsRow.setName("palette:presets");
        root.add(presetsRow);

        JPanel row4 = style.panel(new BorderLayout(10, 0), null);
        preview = style.label(" ", style.muted(), Font.PLAIN, 11f);
        preview.setName("palette:preview");
        output = style.label(" ", style.text(), Font.PLAIN, 11f);
        output.setName("palette:output");
        row4.add(preview, BorderLayout.WEST);
        row4.add(output, BorderLayout.EAST);
        root.add(row4);

        refreshSuggestions();
    }

    public JComponent component() {
        return root;
    }

    public boolean visible() {
        return root.isVisible();
    }

    public void setVisible(boolean on) {
        root.setVisible(on);
        if (on) {
            refreshSuggestions();
        }
    }

    public void setPrefsDir(Path dir) {
        storeFile = dir == null ? null : dir.resolve("prefs").resolve("command-palette.properties");
        loadStore();
        refreshSuggestions();
    }

    public void update(SwarmView v) {
        this.view = v == null ? SwarmView.EMPTY : v;
        refreshPreview();
    }

    public List<String> history() {
        return List.copyOf(history);
    }

    public List<String> favorites() {
        return List.copyOf(favorites);
    }

    public Map<String, String> presets() {
        return Map.copyOf(presets);
    }

    /** Filtered suggestions matching {@code query} across favorites, presets, history, and live catalog. */
    public List<Item> match(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("#")) {
            q = q.substring(1).trim();
        }
        List<Item> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String fav : favorites) {
            if (matches(q, fav, fav) && seen.add(fav)) {
                out.add(new Item("★ " + fav, fav, permissionOf(fav), "fav"));
            }
        }
        for (Map.Entry<String, String> e : presets.entrySet()) {
            if (matches(q, e.getKey(), e.getValue()) && seen.add(e.getValue())) {
                out.add(new Item(e.getKey(), e.getValue(), permissionOf(e.getValue()), "preset"));
            }
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            String h = history.get(i);
            if (matches(q, h, h) && seen.add(h)) {
                out.add(new Item("↺ " + h, h, permissionOf(h), "history"));
            }
        }
        CatalogFeed cat = view.catalog();
        if (cat != null) {
            for (CatalogFeed.Button b : cat.buttons()) {
                String name = b.form().prefix().isBlank() ? b.command() : b.form().prefix();
                if (matches(q, name, b.form().label()) && seen.add(name)) {
                    String perm = b.permission() == null ? "owner" : b.permission().name().toLowerCase(Locale.ROOT);
                    out.add(new Item("#" + name, name, perm, "cmd"));
                }
            }
        }
        return out;
    }

    private static boolean matches(String q, String a, String b) {
        if (q.isEmpty()) {
            return true;
        }
        return (a != null && a.toLowerCase(Locale.ROOT).contains(q))
                || (b != null && b.toLowerCase(Locale.ROOT).contains(q));
    }

    private String permissionOf(String line) {
        String head = firstWord(line);
        CatalogFeed cat = view.catalog();
        if (cat != null) {
            for (CatalogFeed.Button b : cat.buttons()) {
                if (b.command().equalsIgnoreCase(head) || b.form().prefix().equalsIgnoreCase(head)) {
                    CommandPermission p = b.permission();
                    return p == null ? "owner" : p.name().toLowerCase(Locale.ROOT);
                }
            }
        }
        return "swarm";
    }

    private static String firstWord(String line) {
        if (line == null) return "";
        String t = line.trim();
        if (t.startsWith("#")) t = t.substring(1).trim();
        int sp = t.indexOf(' ');
        return (sp < 0 ? t : t.substring(0, sp)).toLowerCase(Locale.ROOT);
    }

    /** Whether a command + target combination is considered risky (suggests dry-run preview). */
    public static boolean isRisky(String line, String targetMode) {
        if ("All bots".equals(targetMode)) {
            return true;
        }
        return RISKY_HEADS.contains(firstWord(line));
    }

    /** Resolve the exact routed command lines for the current input and target selector. */
    public List<String> resolvedLines() {
        String raw = input.getText() == null ? "" : input.getText().trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1).trim();
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        String mode = String.valueOf(target.getSelectedItem());
        String host = view.hostBot();
        if ("Host".equals(mode)) {
            return List.of(raw);
        }
        List<String> bots = new ArrayList<>();
        if ("Selected".equals(mode)) {
            bots.addAll(selectedSupplier.get());
        } else {
            for (SwarmView.BotState b : view.bots()) {
                bots.add(b.name());
            }
        }
        if (bots.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(bots.size());
        for (String bot : bots) {
            String routed = TileRoute.target(bot, host);
            out.add(routed == null ? raw : "swarm send " + routed + " " + raw);
        }
        return out;
    }

    public void execute() {
        String raw = input.getText() == null ? "" : input.getText().trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1).trim();
        }
        if (raw.isEmpty()) {
            output.setText("enter a command");
            return;
        }
        List<String> lines = resolvedLines();
        if (lines.isEmpty()) {
            output.setText("no target bots");
            return;
        }
        if (dryRun.isSelected()) {
            output.setText("DRY-RUN (" + lines.size() + "): #" + String.join(" | #", lines));
            return;
        }
        recordHistory(raw);
        if (commands != null) {
            for (String line : lines) {
                commands.line(line, "", this::showReply);
            }
        }
        output.setText("dispatched " + lines.size() + (lines.size() == 1 ? " command" : " commands"));
        refreshSuggestions();
    }

    private void showReply(CenterReply r) {
        if (r == null) {
            return;
        }
        output.setText((r.admitted() ? TileStatus.Level.OK.glyph : TileStatus.Level.WARN.glyph) + " " + r.summary());
    }

    private void toggleFavorite() {
        String raw = input.getText() == null ? "" : input.getText().trim();
        if (raw.startsWith("#")) raw = raw.substring(1).trim();
        if (raw.isEmpty()) {
            return;
        }
        if (!favorites.remove(raw)) {
            favorites.add(raw);
            output.setText("starred: #" + raw);
        } else {
            output.setText("unstarred: #" + raw);
        }
        saveStore();
        refreshSuggestions();
    }

    private void saveCurrentAsPreset() {
        String raw = input.getText() == null ? "" : input.getText().trim();
        if (raw.startsWith("#")) raw = raw.substring(1).trim();
        if (raw.isEmpty()) {
            return;
        }
        String name = raw.length() <= 18 ? raw : raw.substring(0, 18);
        presets.put(name, raw);
        saveStore();
        output.setText("saved preset '" + name + "'");
        refreshSuggestions();
    }

    private void recordHistory(String line) {
        history.remove(line);
        history.add(line);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        saveStore();
    }

    private void refreshSuggestions() {
        matchesRow.removeAll();
        matchesRow.add(style.label("Matches:", style.faint(), Font.PLAIN, 10.5f));
        List<Item> items = match(input.getText());
        int shown = 0;
        for (Item it : items) {
            if (shown >= 6) break;
            JButton b = style.button(it.label());
            b.setFont(AppStyle.font(Font.PLAIN, 10.5f));
            b.setToolTipText("#" + it.line() + " [" + it.permission() + "]");
            b.addActionListener(e -> input.setText(it.line()));
            matchesRow.add(b);
            shown++;
        }
        presetsRow.removeAll();
        presetsRow.add(style.label("Presets:", style.faint(), Font.PLAIN, 10.5f));
        int pCount = 0;
        for (Map.Entry<String, String> e : presets.entrySet()) {
            if (pCount >= 8) break;
            JButton b = style.button(e.getKey());
            b.setName("palette:preset:" + e.getKey().toLowerCase(Locale.ROOT).replace(' ', '_'));
            b.setFont(AppStyle.font(Font.PLAIN, 10.5f));
            b.setToolTipText("#" + e.getValue());
            b.addActionListener(ev -> input.setText(e.getValue()));
            presetsRow.add(b);
            pCount++;
        }
        refreshPreview();
        root.revalidate();
        root.repaint();
    }

    private void refreshPreview() {
        String raw = input.getText() == null ? "" : input.getText().trim();
        if (raw.isEmpty()) {
            preview.setText("Type a command or pick a preset/favorite");
            return;
        }
        List<String> lines = resolvedLines();
        String perm = permissionOf(raw);
        String risk = isRisky(raw, String.valueOf(target.getSelectedItem())) ? " · risky" : "";
        String dry = dryRun.isSelected() ? " [DRY-RUN]" : "";
        if (lines.isEmpty()) {
            preview.setText("[" + perm + "]" + risk + dry + " · no target bots");
        } else {
            String head = lines.get(0);
            String more = lines.size() > 1 ? " (+" + (lines.size() - 1) + " more)" : "";
            preview.setText("[" + perm + "]" + risk + dry + " → #" + head + more);
        }
    }

    private void loadStore() {
        if (storeFile == null || !Files.isRegularFile(storeFile)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(storeFile)) {
            p.load(in);
        } catch (IOException ignored) {
            return;
        }
        history.clear();
        favorites.clear();
        int hc = intProp(p, "history.count");
        for (int i = 0; i < hc && i < MAX_HISTORY; i++) {
            String v = p.getProperty("history." + i);
            if (v != null && !v.isBlank()) history.add(v);
        }
        int fc = intProp(p, "fav.count");
        for (int i = 0; i < fc; i++) {
            String v = p.getProperty("fav." + i);
            if (v != null && !v.isBlank()) favorites.add(v);
        }
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("preset.")) {
                String name = k.substring("preset.".length());
                String v = p.getProperty(k);
                if (!name.isBlank() && v != null && !v.isBlank()) {
                    presets.put(name, v);
                }
            }
        }
    }

    private void saveStore() {
        if (storeFile == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("history.count", String.valueOf(history.size()));
        for (int i = 0; i < history.size(); i++) {
            p.setProperty("history." + i, history.get(i));
        }
        int fi = 0;
        p.setProperty("fav.count", String.valueOf(favorites.size()));
        for (String f : favorites) {
            p.setProperty("fav." + (fi++), f);
        }
        for (Map.Entry<String, String> e : presets.entrySet()) {
            p.setProperty("preset." + e.getKey(), e.getValue());
        }
        try {
            Path parent = storeFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(storeFile)) {
                p.store(out, "ostinato command palette");
            }
        } catch (IOException ignored) {
        }
    }

    private static int intProp(Properties p, String k) {
        try {
            return Integer.parseInt(p.getProperty(k, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
