package baritone.swarm.center.app;

import baritone.swarm.center.TileRoute;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.Alert;
import baritone.swarm.center.feed.CatalogFeed;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Ostinato: P3 Command Center combined log and console drawer (design doc: Command center layout —
 * "Bottom: a combined log and a console with registry autocomplete and a target preview").
 *
 * <p>Names: {@code console:root}, {@code console:log}, {@code console:target}, {@code console:preview},
 * {@code console:input}, {@code console:send}, {@code console:clear}, {@code console:hint}.
 */
public final class ConsolePanel {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_LINES = 250;
    private static final List<String> FALLBACK_CMDS = List.of(
            "goto", "mine", "follow", "stop", "cancel", "home", "sethome",
            "swarm status", "swarm ping", "swarm send", "swarm lease", "swarm gather",
            "role farmer", "role miner", "role lumberjack", "role idle",
            "blueprint live", "pov show", "pov hide", "pov status",
            "theme dark", "theme light", "theme high-contrast", "theme colorblind",
            "theme nether", "theme end", "theme ocean", "theme rust",
            "center layout wall", "center layout map"
    );

    private final AppStyle style;
    private final Commands commands;
    private final Supplier<List<String>> selection;
    private final JPanel root;
    private final JTextArea logArea;
    private final JComboBox<String> targetMode;
    private final JLabel preview;
    private final JTextField input;
    private final JLabel hint;
    private final List<String> logLines = new ArrayList<>();
    private final List<String> history = new ArrayList<>();
    private final Set<String> seenAlerts = new LinkedHashSet<>();
    private int historyIdx = -1;
    private SwarmView view = SwarmView.EMPTY;

    public ConsolePanel(AppStyle style, Commands commands, Supplier<List<String>> selection) {
        this.style = style;
        this.commands = commands;
        this.selection = selection == null ? List::of : selection;

        root = style.panel(new BorderLayout(0, 4), style.surface());
        root.setName("console:root");
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, style.line()),
                BorderFactory.createEmptyBorder(4, 10, 6, 10)));
        root.setVisible(false);

        JPanel top = style.panel(new BorderLayout(8, 0), null);
        JPanel topLeft = style.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), null);
        topLeft.add(style.label("Log & Console", style.text(), Font.BOLD, 11.5f));
        targetMode = new JComboBox<>(new String[]{"Selected", "All", "Host"});
        targetMode.setName("console:target");
        targetMode.setFont(AppStyle.font(Font.PLAIN, 11f));
        targetMode.addActionListener(e -> refreshPreview());
        topLeft.add(targetMode);
        preview = style.label("Targets: none", style.muted(), Font.PLAIN, 11f);
        preview.setName("console:preview");
        topLeft.add(preview);
        top.add(topLeft, BorderLayout.WEST);

        JButton clear = style.button("Clear");
        clear.setName("console:clear");
        clear.addActionListener(e -> clearLog());
        top.add(clear, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        logArea = new JTextArea(6, 60);
        logArea.setName("console:log");
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBackground(style.bg());
        logArea.setForeground(style.text());
        logArea.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new Dimension(600, 110));
        scroll.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        root.add(scroll, BorderLayout.CENTER);

        JPanel bottom = style.panel(new BorderLayout(6, 2), null);
        input = style.field(40, "Type a command (Tab to autocomplete, Up/Down for history)",
                "Registry command line without leading #");
        input.setName("console:input");
        input.setFocusTraversalKeysEnabled(false);
        input.addActionListener(e -> submit());
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshHint(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshHint(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshHint(); }
        });
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_TAB) {
                    e.consume();
                    autocomplete();
                } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    e.consume();
                    stepHistory(-1);
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    e.consume();
                    stepHistory(1);
                }
            }
        });
        JButton send = style.button("Send");
        send.setName("console:send");
        send.addActionListener(e -> submit());
        hint = style.label("Tab: autocomplete from registry", style.muted(), Font.PLAIN, 10.5f);
        hint.setName("console:hint");
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(send, BorderLayout.EAST);
        bottom.add(hint, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
    }

    public JComponent component() { return root; }
    public boolean visible() { return root.isVisible(); }
    public void setVisible(boolean on) {
        root.setVisible(on);
        if (on) {
            refreshPreview();
            refreshHint();
        }
        root.revalidate();
    }

    public List<String> lines() {
        return List.copyOf(logLines);
    }

    public void clearLog() {
        logLines.clear();
        logArea.setText("");
    }

    public void append(String tag, String message) {
        String ts = LocalTime.now().format(TIME_FMT);
        String line = "[" + ts + "] [" + tag + "] " + message;
        logLines.add(line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.remove(0);
        }
        logArea.setText(String.join("\n", logLines));
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public void recordReply(CenterReply r) {
        if (r == null) return;
        String glyph = r.admitted() ? TileStatus.Level.OK.glyph : TileStatus.Level.WARN.glyph;
        append("REPLY", glyph + " " + r.summary());
    }

    public void update(SwarmView view) {
        this.view = view == null ? SwarmView.EMPTY : view;
        for (Alert a : this.view.alerts()) {
            String key = a.bot() + "|" + a.code() + "|" + a.text();
            if (seenAlerts.add(key)) {
                append("ALERT", a.bot() + ": " + a.text());
            }
        }
        if (root.isVisible()) {
            refreshPreview();
        }
    }

    public List<String> resolveTargets() {
        String mode = String.valueOf(targetMode.getSelectedItem());
        if ("All".equals(mode)) {
            List<String> all = new ArrayList<>();
            for (SwarmView.BotState b : view.bots()) {
                all.add(b.name());
            }
            return all;
        }
        if ("Host".equals(mode)) {
            String h = view.hostBot();
            return h == null || h.isBlank() ? List.of() : List.of(h);
        }
        List<String> sel = selection.get();
        if (sel != null && !sel.isEmpty()) {
            return List.copyOf(sel);
        }
        String h = view.hostBot();
        return h == null || h.isBlank() ? List.of() : List.of(h);
    }

    public void submit() {
        String raw = input.getText().trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1).trim();
        }
        if (raw.isEmpty()) {
            return;
        }
        history.add(raw);
        historyIdx = history.size();
        input.setText("");

        List<String> targets = resolveTargets();
        String host = view.hostBot();
        if (targets.isEmpty()) {
            append("CMD", "#" + raw + " (host)");
            if (commands != null) {
                commands.line(raw, host == null ? "" : host, null);
            }
            return;
        }
        boolean alreadyRouted = raw.toLowerCase(Locale.ROOT).startsWith("swarm ")
                || raw.toLowerCase(Locale.ROOT).startsWith("theme ")
                || raw.toLowerCase(Locale.ROOT).startsWith("center ");
        if (alreadyRouted) {
            append("CMD", "#" + raw);
            if (commands != null) {
                commands.line(raw, host == null ? "" : host, null);
            }
            return;
        }
        for (String bot : targets) {
            String t = TileRoute.target(bot, host);
            String routed = t == null ? raw : "swarm send " + t + " " + raw;
            append("CMD", "[" + bot + "] #" + routed);
            if (commands != null) {
                commands.line(routed, bot, null);
            }
        }
    }

    /** Completes the current input text from the registry catalog + built-in command prefixes. */
    public String autocomplete() {
        String raw = input.getText().trim();
        List<String> matches = suggestions(raw);
        if (!matches.isEmpty()) {
            String pick = matches.get(0);
            input.setText(pick);
            input.setCaretPosition(pick.length());
            refreshHint();
            return pick;
        }
        return raw;
    }

    public List<String> suggestions(String prefix) {
        String p = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        if (p.startsWith("#")) {
            p = p.substring(1).trim();
        }
        Set<String> pool = new LinkedHashSet<>();
        CatalogFeed cat = view.catalog();
        if (cat != null) {
            for (CatalogFeed.Button b : cat.buttons()) {
                if (b.command() != null && !b.command().isBlank()) {
                    pool.add(b.command());
                }
                if (b.form() != null && b.form().prefix() != null && !b.form().prefix().isBlank()) {
                    pool.add(b.form().prefix());
                }
            }
        }
        pool.addAll(FALLBACK_CMDS);
        List<String> out = new ArrayList<>();
        for (String cand : pool) {
            if (p.isEmpty() || cand.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(cand);
            }
        }
        return out;
    }

    private void stepHistory(int delta) {
        if (history.isEmpty()) return;
        historyIdx = Math.max(0, Math.min(history.size(), historyIdx + delta));
        if (historyIdx == history.size()) {
            input.setText("");
        } else {
            input.setText(history.get(historyIdx));
            input.setCaretPosition(input.getText().length());
        }
    }

    private void refreshPreview() {
        List<String> targets = resolveTargets();
        preview.setText(targets.isEmpty()
                ? "Will run on: (no command host)"
                : "Will run on (" + targets.size() + "): " + String.join(", ", targets));
    }

    private void refreshHint() {
        List<String> m = suggestions(input.getText());
        if (m.isEmpty()) {
            hint.setText("No matching registry commands");
        } else {
            int show = Math.min(5, m.size());
            hint.setText("Tab completes: " + String.join("  ·  ", m.subList(0, show))
                    + (m.size() > show ? "  (+" + (m.size() - show) + ")" : ""));
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(visible(), String.valueOf(targetMode.getSelectedItem()), List.copyOf(logLines),
                List.copyOf(history), input.getText());
    }

    public void restore(Snapshot s) {
        if (s == null) return;
        logLines.clear();
        logLines.addAll(s.logLines);
        logArea.setText(String.join("\n", logLines));
        history.clear();
        history.addAll(s.history);
        historyIdx = history.size();
        targetMode.setSelectedItem(s.targetMode);
        input.setText(s.draft == null ? "" : s.draft);
        setVisible(s.visible);
    }

    public record Snapshot(boolean visible, String targetMode, List<String> logLines, List<String> history, String draft) {}
}
