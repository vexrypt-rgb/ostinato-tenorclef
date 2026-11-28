package baritone.swarm.center.app;

import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.LayoutStore;
import baritone.swarm.center.ThemeChoice;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ostinato: Phase 3 window, everything inside the frame: header (title, server, command host, alert count, theme
 * picker, layout toggle, coordinator summary, and adaptive density override toggle), one card per {@link LayoutView},
 * and a footer with the last command reply and any refused feed files. A plain panel, so tests build and click it
 * without a display; {@link CenterApp} puts it in a frame.
 *
 * <p>Names: {@code header.server}, {@code header.host}, {@code header.alerts}, {@code header.coordinator},
 * {@code header.tier}, {@code header.density.auto|cards|roster|cluster}, {@code header.theme.dark},
 * {@code header.theme.light}, {@code header.theme.high-contrast}, {@code header.theme.colorblind},
 * {@code header.layout.wall}, {@code header.layout.map}, {@code footer.reply}, {@code footer.problems}.
 */
public final class CenterShell {

    private static final List<String> THEMES = List.of("dark", "light", "high-contrast", "colorblind");

    private AppStyle style;
    private final JPanel root;
    private JLabel server, host, alerts, coordinator, tierLabel, reply, problems;
    private CardLayout cards = new CardLayout();
    private JPanel body;
    private final List<LayoutView> views = new ArrayList<>();
    private final Map<CenterLayout, JToggleButton> toggles = new EnumMap<>(CenterLayout.class);
    private final Map<String, JToggleButton> themeToggles = new LinkedHashMap<>();
    private final Map<DensityPolicy.Override, JToggleButton> densityToggles = new EnumMap<>(DensityPolicy.Override.class);
    private WallView wall;
    private MapView map;
    private EditorView editor;
    private final Commands commands;
    private CenterLayout shown;
    private Consumer<CenterLayout> onPick = l -> {};
    private Path prefsDir;
    private String layoutSeen;
    private String themeName;
    private String themeSeen;
    private SwarmView last = SwarmView.EMPTY;
    private boolean applying;
    private Hold hold;

    public CenterShell(AppStyle style, Commands commands, CenterLayout initial) {
        this.style = style == null ? new AppStyle(CenterTheme.DEFAULT_DARK) : style;
        this.commands = commands;
        this.themeName = nameOf(this.style.theme);
        root = this.style.panel(new BorderLayout(), this.style.bg());
        root.setName("shell");
        commands.onReply(this::showReply);
        assemble();
        show(initial == null ? CenterLayout.WALL : initial);
    }

    private static String nameOf(CenterTheme theme) {
        if (CenterTheme.LIGHT.equals(theme)) {
            return "light";
        }
        if (CenterTheme.HIGH_CONTRAST.equals(theme)) {
            return "high-contrast";
        }
        if (CenterTheme.COLORBLIND_SAFE.equals(theme)) {
            return "colorblind";
        }
        return ThemeChoice.DEFAULT;
    }

    private void assemble() {
        views.clear();
        toggles.clear();
        themeToggles.clear();
        densityToggles.clear();
        cards = new CardLayout();

        JPanel header = style.panel(new BorderLayout(0, 4), style.surface());
        header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, style.line()),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));

        JPanel topRow = style.panel(new BorderLayout(12, 0), null);
        JPanel left = style.panel(new FlowLayout(FlowLayout.LEFT, 12, 0), null);
        left.add(style.label("Ostinato command center", style.text(), Font.BOLD, 15f));
        server = named(style.label(" ", style.muted(), Font.PLAIN, 12f), "header.server");
        host = named(style.label(" ", style.muted(), Font.PLAIN, 12f), "header.host");
        alerts = named(style.label(" ", style.muted(), Font.BOLD, 12f), "header.alerts");
        left.add(server);
        left.add(host);
        left.add(alerts);
        topRow.add(left, BorderLayout.WEST);

        JPanel right = style.panel(new FlowLayout(FlowLayout.RIGHT, 4, 0), null);
        ButtonGroup themes = new ButtonGroup();
        for (String name : THEMES) {
            JToggleButton t = style.toggle(CenterTheme.label(name));
            t.setName("header.theme." + name);
            t.addActionListener(e -> choose(name));
            themes.add(t);
            themeToggles.put(name, t);
            right.add(t);
        }
        ButtonGroup group = new ButtonGroup();
        for (CenterLayout l : CenterLayout.values()) {
            JToggleButton t = style.toggle(l.label());
            t.setName("header.layout." + l.wire());
            t.addActionListener(e -> pick(l));
            group.add(t);
            toggles.put(l, t);
            right.add(t);
        }
        topRow.add(right, BorderLayout.EAST);
        header.add(topRow, BorderLayout.NORTH);

        JPanel subRow = style.panel(new BorderLayout(12, 0), null);
        JPanel subLeft = style.panel(new FlowLayout(FlowLayout.LEFT, 12, 0), null);
        coordinator = named(style.label("Coord none", style.muted(), Font.PLAIN, 11f), "header.coordinator");
        tierLabel = named(style.label("View: Cards (auto)", style.muted(), Font.PLAIN, 11f), "header.tier");
        subLeft.add(coordinator);
        subLeft.add(tierLabel);
        subRow.add(subLeft, BorderLayout.WEST);

        JPanel subRight = style.panel(new FlowLayout(FlowLayout.RIGHT, 4, 0), null);
        subRight.add(style.label("Density:", style.faint(), Font.PLAIN, 11f));
        ButtonGroup densityGroup = new ButtonGroup();
        for (DensityPolicy.Override ov : DensityPolicy.Override.values()) {
            JToggleButton t = style.toggle(ov.label());
            t.setName("header.density." + ov.wire());
            t.setFont(AppStyle.font(Font.PLAIN, 11f));
            t.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(style.line(), 1),
                    BorderFactory.createEmptyBorder(1, 7, 1, 7)));
            t.addActionListener(e -> setDensityOverride(ov));
            densityGroup.add(t);
            densityToggles.put(ov, t);
            subRight.add(t);
        }
        densityToggles.get(DensityPolicy.Override.AUTO).setSelected(true);
        subRow.add(subRight, BorderLayout.EAST);
        header.add(subRow, BorderLayout.SOUTH);

        root.add(header, BorderLayout.NORTH);

        body = style.panel(cards, style.bg());
        root.add(body, BorderLayout.CENTER);
        wall = new WallView(style, commands);
        map = new MapView(style, commands);
        editor = new EditorView(style, commands, map::selected, () -> (int) map.dimension());
        add(wall);
        add(map);
        add(editor);
        wall.onOpenOnMap(name -> {
            pick(CenterLayout.MAP);
            map.centerOn(name);
        });
        wall.onTierChanged(tier -> refreshTierBadge());

        JPanel footer = style.panel(new BorderLayout(12, 0), style.surface());
        footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, style.line()),
                BorderFactory.createEmptyBorder(5, 12, 5, 12)));
        reply = named(style.label("No command sent yet.", style.muted(), Font.PLAIN, 12f), "footer.reply");
        problems = named(style.label(" ", style.warn(), Font.PLAIN, 12f), "footer.problems");
        footer.add(reply, BorderLayout.WEST);
        footer.add(problems, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        JToggleButton theme = themeToggles.get(themeName);
        if (theme != null) {
            theme.setSelected(true);
        }
        refreshTierBadge();
    }

    private JLabel named(JLabel l, String n) {
        l.setName(n);
        return l;
    }

    /** Adds a layout card. A later slice that replaces {@link MapView} adds its view here instead. */
    private void add(LayoutView v) {
        views.add(v);
        body.add(v.component(), v.layout().wire());
    }

    public JPanel component() {
        return root;
    }

    public CenterLayout shown() {
        return shown;
    }

    public String themeName() {
        return themeName;
    }

    public DensityPolicy.Tier activeWallTier() {
        return wall == null ? DensityPolicy.Tier.CARDS : wall.activeTier();
    }

    public DensityPolicy.Override densityOverride() {
        return wall == null ? DensityPolicy.Override.AUTO : wall.densityOverride();
    }

    public void setDensityOverride(DensityPolicy.Override override) {
        DensityPolicy.Override next = override == null ? DensityPolicy.Override.AUTO : override;
        JToggleButton btn = densityToggles.get(next);
        if (btn != null && !btn.isSelected()) {
            btn.setSelected(true);
        }
        if (wall != null) {
            wall.setDensityOverride(next);
        }
        refreshTierBadge();
    }

    private void refreshTierBadge() {
        if (tierLabel == null || wall == null) {
            return;
        }
        DensityPolicy.Tier tier = wall.activeTier();
        DensityPolicy.Override ov = wall.densityOverride();
        String mode = ov == DensityPolicy.Override.AUTO ? "auto" : "manual";
        tierLabel.setText("View: " + tier.label() + " (" + mode + ")");
        for (Map.Entry<DensityPolicy.Override, JToggleButton> e : densityToggles.entrySet()) {
            boolean sel = e.getKey() == ov;
            e.getValue().setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(sel ? style.accent() : style.line(), sel ? 2 : 1),
                    BorderFactory.createEmptyBorder(sel ? 0 : 1, sel ? 6 : 7, sel ? 0 : 1, sel ? 6 : 7)));
        }
    }

    /** Called when the user picks a layout in the header (the layout slice saves and sends it). */
    public void onLayoutPicked(Consumer<CenterLayout> pick) {
        onPick = pick == null ? l -> {} : pick;
    }

    public void show(CenterLayout l) {
        shown = l == null ? CenterLayout.WALL : l;
        cards.show(body, shown.wire());
        JToggleButton t = toggles.get(shown);
        if (t != null && !t.isSelected()) {
            t.setSelected(true);
        }
    }

    public List<LayoutView> views() {
        return List.copyOf(views);
    }

    /** Per-user prefs (control groups, layout, and theme) live under this directory. */
    public void setPrefsDir(Path dir) {
        prefsDir = dir;
        Prefs p = readPrefs(dir);
        followTheme(p.theme());
        map.setPrefsDir(dir);
        editor.setDir(dir);
        followLayout(p.layout());
    }

    public Path prefsDir() {
        return prefsDir;
    }

    /** Ostinato: 3D schematic editor (tests and the auto driver). */
    public EditorView editor() {
        return editor;
    }

    /** The shared layout and theme files as text; null where a file is missing or unreadable. */
    public record Prefs(String layout, String theme) {
        public static final Prefs NONE = new Prefs(null, null);
    }

    /** Reads both prefs files. Call it off the Swing thread (the app's worker does, next to the feed snapshot). */
    public static Prefs readPrefs(Path dir) {
        if (dir == null) {
            return Prefs.NONE;
        }
        return new Prefs(readText(LayoutStore.sharedFile(dir)), readText(ThemeChoice.file(dir)));
    }

    private static String readText(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Header toggle and a tile opened on the map: switch now, remember it, and tell the host. */
    private void pick(CenterLayout l) {
        show(l);
        saveLayout(l);
        sendLayout(l);
        onPick.accept(l);
    }

    private void choose(String name) {
        if (applying || name == null || name.equals(themeName)) {
            return;
        }
        applying = true;
        try {
            applyTheme(CenterTheme.builtIn(name), name);
            saveTheme(name);
            sendTheme(name);
            update(last);
        } finally {
            applying = false;
        }
    }

    /**
     * Rebuilds the cards with {@code theme} and keeps the layout, selection, wall filter/multi-selection/density
     * override, camera, typed text and last reply. The text is put back at the end of {@link #update}, after the new
     * tiles exist.
     */
    private void applyTheme(CenterTheme theme, String name) {
        Hold snap = capture();
        style = new AppStyle(theme == null ? CenterTheme.DEFAULT_DARK : theme);
        themeName = name == null ? ThemeChoice.DEFAULT : name;
        root.removeAll();
        root.setBackground(style.bg());
        assemble();
        if (prefsDir != null) {
            map.setPrefsDir(prefsDir);
            editor.setDir(prefsDir);
        }
        setDensityOverride(snap.densityOverride);
        wall.setActiveFilter(snap.wallFilter);
        wall.setSearchQuery(snap.wallQuery);
        wall.setMultiSelected(snap.wallMultiSelected);
        map.restoreMap(snap.zoom, snap.cx, snap.cz, snap.dim, snap.selected, snap.groups, snap.area, snap.armed,
                snap.mine, snap.build);
        map.restoreIso(snap.isometric, snap.isoRotation, snap.rtsVisible);
        if (snap.editor != null) {
            editor.restore(snap.editor);
        }
        if (snap.reply != null) {
            reply.setText(snap.reply);
            reply.setForeground(snap.replyColor);
        }
        show(snap.layout);
        hold = snap;
        root.revalidate();
        root.repaint();
    }

    private Hold capture() {
        Hold h = new Hold();
        h.layout = shown == null ? CenterLayout.WALL : shown;
        WallView w = wall();
        h.wall = w == null ? null : w.selected();
        h.densityOverride = w == null ? DensityPolicy.Override.AUTO : w.densityOverride();
        h.wallFilter = w == null ? FleetFilterBar.Filter.ALL : w.activeFilter();
        h.wallQuery = w == null ? "" : w.searchQuery();
        h.wallMultiSelected = w == null ? List.of() : List.copyOf(w.multiSelected());
        h.reply = reply == null ? null : reply.getText();
        h.replyColor = reply == null ? style.muted() : reply.getForeground();
        h.zoom = map == null ? 4 : map.camera().zoom();
        h.cx = map == null ? 0 : map.camera().centerX();
        h.cz = map == null ? 0 : map.camera().centerZ();
        h.dim = map == null ? 'o' : map.dimension();
        h.selected = map == null ? List.of() : map.selected();
        h.groups = map == null ? "" : map.groups().toText();
        h.area = map == null ? null : map.area();
        h.armed = map != null && map.areaArmed();
        h.mine = map != null && map.areaMine();
        h.build = map != null && map.areaBuild();
        h.isometric = map != null && map.isometric();
        h.isoRotation = map == null ? 0 : map.isoRotation();
        h.rtsVisible = map != null && map.rts().visible();
        h.editor = editor == null ? null : editor.snapshot();
        collect(root, h.fields);
        return h;
    }

    private void collect(Component c, Map<String, String> fields) {
        if (c instanceof JTextComponent text && c.getName() != null) {
            fields.put(c.getName(), text.getText());
        }
        if (c instanceof Container box) {
            for (Component child : box.getComponents()) {
                collect(child, fields);
            }
        }
    }

    private void restoreFields(Hold snap) {
        for (Map.Entry<String, String> e : snap.fields.entrySet()) {
            Component c = CenterApp.find(root, e.getKey());
            if (c instanceof JTextComponent text) {
                text.setText(e.getValue());
            }
        }
    }

    WallView wall() {
        return wall;
    }

    private void saveLayout(CenterLayout l) {
        if (prefsDir == null || l == null) {
            return;
        }
        try {
            Path file = LayoutStore.sharedFile(prefsDir);
            LayoutStore.save(file, l);
            layoutSeen = Files.readString(file);
        } catch (IOException ignored) {
            // The card already switched. The next refresh follows whatever the file still says.
        }
    }

    private void saveTheme(String name) {
        if (prefsDir == null || name == null) {
            return;
        }
        try {
            Path file = ThemeChoice.file(prefsDir);
            ThemeChoice.save(file, name);
            themeSeen = Files.readString(file);
        } catch (IOException ignored) {
            // The theme already changed. The next refresh follows whatever the file still says.
        }
    }

    /** A missing file keeps the current card. Garbage in the file is the wall. */
    private void followLayout(String text) {
        if (prefsDir == null || text == null || text.equals(layoutSeen)) {
            return;
        }
        layoutSeen = text;
        CenterLayout loaded = LayoutStore.parse(text);
        if (loaded != shown) {
            show(loaded);
        }
    }

    /** A missing file keeps the current theme. An unknown name is Default dark. */
    private void followTheme(String text) {
        if (applying || prefsDir == null || text == null || text.equals(themeSeen)) {
            return;
        }
        themeSeen = text;
        String name = ThemeChoice.parse(text);
        if (name.equals(themeName)) {
            return;
        }
        applying = true;
        try {
            applyTheme(CenterTheme.builtIn(name), name);
        } finally {
            applying = false;
        }
    }

    private void sendLayout(CenterLayout l) {
        String id = l.formId();
        ButtonModel form = headerForm(id);
        if (form != null) {
            commands.press(form, Map.of(), last.hostBot(), null);
        } else {
            commands.line("center layout " + l.wire(), last.hostBot(), null);
        }
    }

    private void sendTheme(String name) {
        ButtonModel form = headerForm("theme-pick");
        if (form != null) {
            commands.press(form, Map.of("name", name), last.hostBot(), null);
        } else {
            commands.line("theme " + name, last.hostBot(), null);
        }
    }

    private ButtonModel headerForm(String id) {
        if (last.catalog() == null) {
            return null;
        }
        for (ButtonModel m : last.catalog().models(FormPlacement.HEADER)) {
            if (id.equals(m.form().id())) {
                return m;
            }
        }
        return null;
    }

    /** One refresh, on the Swing thread. Every card is updated, shown or not, so switching is instant. */
    public void update(SwarmView view) {
        update(view, readPrefs(prefsDir));
    }

    /** One refresh with prefs already read off the Swing thread ({@link #readPrefs}). */
    public void update(SwarmView view, Prefs prefs) {
        last = view == null ? SwarmView.EMPTY : view;
        view = last;
        Prefs p = prefs == null ? Prefs.NONE : prefs;
        followLayout(p.layout());
        followTheme(p.theme());
        server.setText("Server " + view.server());
        String h = view.hostBot();
        host.setText(h == null ? TileStatus.Level.WARN.glyph + " no command host" : "Host " + h);
        host.setForeground(h == null ? style.warn() : style.muted());
        int n = view.alerts().size();
        alerts.setText(n == 0 ? TileStatus.Level.IDLE.glyph + " No alerts"
                : TileStatus.Level.WARN.glyph + " " + n + (n == 1 ? " alert" : " alerts"));
        alerts.setForeground(n == 0 ? style.muted() : style.warn());
        coordinator.setText(FleetFilterBar.coordinatorSummary(view.coordinator()));
        coordinator.setForeground(view.coordinator() == null ? style.muted()
                : "refused".equalsIgnoreCase(view.coordinator().mode()) ? style.warn() : style.text());
        problems.setText(view.problems().isEmpty() ? " " : TileStatus.Level.WARN.glyph + " " + view.problems().get(0)
                + (view.problems().size() > 1 ? " (+" + (view.problems().size() - 1) + ")" : ""));
        for (LayoutView v : views) {
            v.update(view);
        }
        refreshTierBadge();
        if (hold != null) {
            restoreFields(hold);
            WallView w = wall();
            if (w != null && hold.wall != null) {
                w.select(hold.wall);
            }
            map.refreshArea();
            hold = null;
        }
    }

    private void showReply(CenterReply r) {
        reply.setText((r.admitted() ? TileStatus.Level.OK.glyph : TileStatus.Level.WARN.glyph) + " " + r.summary());
        reply.setForeground(r.admitted() ? style.ok() : style.warn());
    }

    /** Camera, selection, wall filter/density and typed text carried across a theme rebuild. */
    private static final class Hold {
        private CenterLayout layout;
        private String wall;
        private DensityPolicy.Override densityOverride = DensityPolicy.Override.AUTO;
        private FleetFilterBar.Filter wallFilter = FleetFilterBar.Filter.ALL;
        private String wallQuery = "";
        private List<String> wallMultiSelected = List.of();
        private String reply;
        private Color replyColor;
        private int zoom;
        private double cx;
        private double cz;
        private char dim;
        private List<String> selected = List.of();
        private String groups = "";
        private int[] area;
        private boolean armed;
        private boolean mine;
        private boolean build;
        private boolean isometric;
        private int isoRotation;
        private boolean rtsVisible;
        private EditorView.Snapshot editor;
        private final Map<String, String> fields = new LinkedHashMap<>();
    }
}
