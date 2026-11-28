package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.rts.Blueprint;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Ostinato: command-center 3D schematic editor (Design and Live). See {@code docs/schematic-editor.md}.
 * Named components are driven by {@code --auto} the same way as the map RTS tools (no file dialogs).
 */
public final class EditorView implements LayoutView {

    public static final String[] PALETTE = {
            "cobblestone", "oak_planks", "glass", "dirt", "stone", "copper_block", "oak_log", "bricks"
    };

    private final AppStyle style;
    private final Commands commands;
    private final Supplier<List<String>> selection;
    private final IntSupplier dim;
    private final JPanel root;
    private final EditorCanvas canvas;
    private final JTextField name;
    private final JTextField block;
    private final JTextField yField;
    private final JTextField hField;
    private final JTextField origin;
    private final JTextField path;
    private final JLabel status;
    private final JLabel reply;
    private final JToggleButton design;
    private final JToggleButton liveMode;
    private final JToggleButton allLayers;
    private final Timer writeLater;
    private final Timer statusPoll;
    private Blueprint blueprint = new Blueprint("untitled");
    private Path dir;
    private SwarmView view = SwarmView.EMPTY;
    private boolean live;
    private final Set<Long> placed = new HashSet<>();
    private Blueprint.Status lastStatus;

    public EditorView(AppStyle style, Commands commands, Supplier<List<String>> selection, IntSupplier dim) {
        this.style = style;
        this.commands = commands;
        this.selection = selection == null ? List::of : selection;
        this.dim = dim == null ? () -> (int) 'o' : dim;
        root = style.panel(new BorderLayout(0, 0), style.bg());
        root.setName("editor");
        canvas = new EditorCanvas(style);
        canvas.setOnPaint((hit, erase) -> applyHit(hit, erase));
        canvas.setBlueprint(blueprint);

        JPanel bar = style.panel(new GridLayout(3, 1, 0, 0), style.surface());
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JPanel row1 = row();
        ButtonGroup modes = new ButtonGroup();
        design = style.toggle("Design");
        design.setName("ed:mode:design");
        design.setSelected(true);
        design.addActionListener(e -> setLive(false));
        liveMode = style.toggle("Live");
        liveMode.setName("ed:mode:live");
        liveMode.addActionListener(e -> setLive(true));
        modes.add(design);
        modes.add(liveMode);
        row1.add(design);
        row1.add(liveMode);
        name = field(row1, "ed:name", 8, "name", "Blueprint name (file in the feed folder's blueprints/)");
        name.setText("untitled");
        block = field(row1, "ed:block", 12, "block", "Block to paint, e.g. cobblestone");
        block.setText("cobblestone");
        yField = field(row1, "ed:y", 4, "y", "Layer height in world Y");
        yField.setText("-60");
        hField = field(row1, "ed:h", 2, "h", "Layers painted at once, from y up");
        hField.setText("1");
        JButton up = style.button("Y+");
        up.setName("ed:up");
        up.addActionListener(e -> stepY(1));
        JButton down = style.button("Y-");
        down.setName("ed:down");
        down.addActionListener(e -> stepY(-1));
        row1.add(up);
        row1.add(down);
        allLayers = style.toggle("All layers");
        allLayers.setName("ed:all");
        allLayers.setSelected(true);
        allLayers.addActionListener(e -> canvas.setShowAll(allLayers.isSelected()));
        row1.add(allLayers);
        bar.add(row1);

        JPanel row2 = row();
        for (String id : PALETTE) {
            JButton b = style.button(id);
            b.setName("ed:pal:" + id);
            b.addActionListener(e -> block.setText(id));
            row2.add(b);
        }
        bar.add(row2);

        JPanel row3 = row();
        origin = field(row3, "ed:origin", 12, "origin", "World origin for import and empty start, x,y,z");
        origin.setText("0,-60,0");
        path = field(row3, "ed:path", 22, "path", "Path to a .ost, .litematic, .schem or .nbt file");
        JButton load = style.button("Load");
        load.setName("ed:load");
        load.addActionListener(e -> load());
        JButton save = style.button("Save");
        save.setName("ed:save");
        save.addActionListener(e -> writeNow());
        JButton imp = style.button("Import");
        imp.setName("ed:import");
        imp.addActionListener(e -> importFile());
        JButton exp = style.button("Export");
        exp.setName("ed:export");
        exp.setToolTipText("Export blueprint to .schem, .litematic, .nbt or .ost at the path field");
        exp.addActionListener(e -> exportFile());
        JButton liveBtn = style.button("Build live");
        liveBtn.setName("ed:live");
        liveBtn.addActionListener(e -> buildLive());
        JButton clear = style.button("Clear");
        clear.setName("ed:clear");
        clear.addActionListener(e -> clear());
        row3.add(load);
        row3.add(save);
        row3.add(imp);
        row3.add(exp);
        row3.add(liveBtn);
        row3.add(clear);
        bar.add(row3);

        JPanel south = style.panel(new BorderLayout(8, 0), style.surface());
        south.setBorder(BorderFactory.createEmptyBorder(2, 10, 4, 10));
        status = named(style.label("empty", style.muted(), Font.PLAIN, 12f), "ed:status");
        reply = named(style.label(" ", style.muted(), Font.PLAIN, 12f), "ed:reply");
        south.add(status, BorderLayout.WEST);
        south.add(reply, BorderLayout.EAST);

        root.add(bar, BorderLayout.NORTH);
        root.add(canvas, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        writeLater = new Timer(500, e -> writeNow());
        writeLater.setRepeats(false);
        statusPoll = new Timer(1000, e -> readStatus());
        statusPoll.start();
        publish();
    }

    private JPanel row() {
        return style.panel(new FlowLayout(FlowLayout.LEFT, 6, 2), null);
    }

    private JTextField field(JPanel row, String n, int cols, String prompt, String tip) {
        JTextField f = style.field(cols, prompt, tip);
        f.setName(n);
        row.add(f);
        return f;
    }

    private static JLabel named(JLabel l, String n) {
        l.setName(n);
        return l;
    }

    @Override
    public CenterLayout layout() {
        return CenterLayout.EDITOR;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void update(SwarmView view) {
        this.view = view == null ? SwarmView.EMPTY : view;
    }

    /** {@code <feed dir>/blueprints}. */
    public void setDir(Path feedDir) {
        dir = feedDir == null ? null : feedDir.resolve("blueprints");
    }

    public Path dir() {
        return dir;
    }

    public Blueprint blueprint() {
        return blueprint;
    }

    public EditorCanvas canvas() {
        return canvas;
    }

    public boolean live() {
        return live;
    }

    public Set<Long> placed() {
        return Set.copyOf(placed);
    }

    public void setLive(boolean on) {
        live = on;
        design.setSelected(!on);
        liveMode.setSelected(on);
        canvas.setLive(on);
        if (on) {
            readStatus();
        }
        publish();
    }

    public int layerY() {
        try {
            return Integer.parseInt(yField.getText().trim());
        } catch (NumberFormatException e) {
            return -60;
        }
    }

    public int layers() {
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(hField.getText().trim())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    void stepY(int delta) {
        yField.setText(Integer.toString(layerY() + delta));
        syncLayer();
    }

    void syncLayer() {
        canvas.setLayer(layerY(), layers());
        canvas.repaint();
    }

    public void applyHit(EditorHit hit, boolean erase) {
        if (hit == null || hit.missed()) {
            return;
        }
        if (erase) {
            if (hit.kind == EditorHit.Kind.CELL) {
                paintAt(hit.x, hit.y, hit.z, true);
            } else {
                paintAt(hit.placeX, hit.placeY, hit.placeZ, true);
            }
            return;
        }
        int x = hit.kind == EditorHit.Kind.CELL ? hit.placeX : hit.placeX;
        int y = hit.kind == EditorHit.Kind.CELL ? hit.placeY : hit.placeY;
        int z = hit.kind == EditorHit.Kind.CELL ? hit.placeZ : hit.placeZ;
        // layer clicks paint h stacks from the layer; face clicks place one cell (plus h-1 up)
        paintAt(x, y, z, false);
    }

    /** Place or erase at a world cell, then {@code h-1} more above it. */
    public void paintAt(int x, int y, int z, boolean erase) {
        try {
            ensureNamed();
            boolean changed = false;
            int h = layers();
            for (int dy = 0; dy < h; dy++) {
                changed |= erase ? blueprint.erase(x, y + dy, z) : blueprint.paint(x, y + dy, z, block.getText());
            }
            if (changed) {
                changed();
            }
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
        }
    }

    /** Inclusive XZ box on the current layer (and {@code h} up). Used by tests and {@code @map|ed:paint}. */
    public void paintBox(int x1, int z1, int x2, int z2, boolean erase) {
        syncLayer();
        int y = layerY();
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                paintAt(x, y, z, erase);
            }
        }
    }

    private void ensureNamed() {
        String n = name.getText();
        if (!blueprint.name().equals(Blueprint.safeName(n))) {
            Blueprint next = new Blueprint(n);
            for (Blueprint.Cell c : blueprint.cells()) {
                next.paint(c.x(), c.y(), c.z(), c.block());
            }
            blueprint = next;
        }
    }

    private void changed() {
        canvas.setBlueprint(blueprint);
        // setBlueprint also fits the camera; keep the user's orbit after the first cell
        publish();
        if (live) {
            writeLater.restart();
        }
    }

    public void writeNow() {
        writeLater.stop();
        try {
            ensureNamed();
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            return;
        }
        if (dir == null) {
            status.setText(blueprint.size() + " blocks · v" + blueprint.version() + " · no feed folder: not saved");
            return;
        }
        try {
            Files.createDirectories(dir);
            CenterFeed.writeAtomic(dir.resolve(Blueprint.fileName(blueprint.name())), blueprint.write());
            status.setText(blueprint.size() + " blocks · v" + blueprint.version() + " · saved");
        } catch (Exception e) {
            status.setText("save failed: " + e.getMessage());
        }
        publish();
    }

    public void load() {
        if (dir == null) {
            status.setText("no feed folder");
            return;
        }
        try {
            Path f = path.getText().isBlank()
                    ? dir.resolve(Blueprint.fileName(name.getText()))
                    : Path.of(path.getText().trim());
            if (!Files.isRegularFile(f)) {
                status.setText("file not found: " + f.getFileName());
                return;
            }
            String n = name.getText();
            blueprint = Blueprint.read(n, Files.readString(f));
            name.setText(blueprint.name());
            canvas.camera.fit(blueprint.bounds());
            canvas.setBlueprint(blueprint);
            placed.clear();
            lastStatus = null;
            publish();
            status.setText("loaded " + blueprint.size() + " blocks · v" + blueprint.version());
        } catch (Exception e) {
            status.setText("load failed: " + e.getMessage());
        }
    }

    public void importFile() {
        String p = path.getText().trim();
        if (p.isEmpty()) {
            status.setText("set a .litematic, .schem or .nbt path");
            return;
        }
        try {
            OstSchematic s = SchemImport.fromFile(Path.of(p));
            int[] o = parseOrigin();
            ensureNamed();
            blueprint = SchemImport.toBlueprint(blueprint.name(), s, o);
            canvas.camera.fit(blueprint.bounds());
            canvas.setBlueprint(blueprint);
            placed.clear();
            lastStatus = null;
            if (live) {
                writeLater.restart();
            }
            publish();
            status.setText("imported " + blueprint.size() + " blocks at " + o[0] + "," + o[1] + "," + o[2]);
        } catch (Exception e) {
            status.setText("import failed: " + e.getMessage());
        }
    }

    /** Ostinato: export current blueprint to .schem, .litematic, .nbt or .ost based on path extension. */
    public void exportFile() {
        String p = path.getText().trim();
        if (p.isEmpty()) {
            status.setText("set a .schem, .litematic, .nbt or .ost export path");
            return;
        }
        try {
            ensureNamed();
            Path target = Path.of(p);
            SchemImport.exportBlueprint(blueprint, target);
            status.setText("exported " + blueprint.size() + " blocks to " + target.getFileName());
        } catch (Exception e) {
            status.setText("export failed: " + e.getMessage());
        }
    }

    int[] parseOrigin() {
        String t = origin.getText().trim();
        String[] p = t.split(",");
        if (p.length != 3) {
            return new int[]{0, -60, 0};
        }
        try {
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
        } catch (NumberFormatException e) {
            return new int[]{0, -60, 0};
        }
    }

    public void clear() {
        try {
            blueprint = new Blueprint(name.getText());
        } catch (IllegalArgumentException e) {
            blueprint = new Blueprint("untitled");
            name.setText("untitled");
        }
        placed.clear();
        lastStatus = null;
        canvas.setBlueprint(blueprint);
        canvas.camera.fit(null);
        publish();
    }

    public void buildLive() {
        setLive(true);
        writeNow();
        List<String> sel = selection.get();
        String host = host();
        List<String> bots = sel.isEmpty() ? (host.isEmpty() ? List.of() : List.of(host)) : sel;
        if (bots.isEmpty()) {
            reply.setText("no bot to build with");
            return;
        }
        if (bots.size() >= 2) {
            RtsOrders.Plan plan = RtsOrders.splitBlueprint(bots, (char) dim.getAsInt(), blueprint);
            if (plan.reason() != null) {
                reply.setText(plan.reason());
                return;
            }
            send(plan.orders());
            return;
        }
        List<RtsOrders.Order> orders = new ArrayList<>();
        for (String b : bots) {
            orders.add(RtsOrders.blueprint(b, host, blueprint.name()));
        }
        send(orders);
    }

    public void readStatus() {
        if (!live || dir == null || !Files.isDirectory(dir)) {
            return;
        }
        List<Blueprint.Status> all = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (Blueprint.isStatusOf(blueprint.name(), f.getFileName().toString())) {
                    try {
                        all.add(Blueprint.readStatus(Files.readString(f)));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            return;
        }
        if (all.isEmpty()) {
            return;
        }
        placed.clear();
        List<String> notes = new ArrayList<>();
        int total = 0;
        for (Blueprint.Status s : all) {
            for (int[] c : s.cells()) {
                placed.add(Blueprint.cellKey(c[0], c[1], c[2]));
            }
            notes.add(s.note());
            total = Math.max(total, s.total());
        }
        lastStatus = new Blueprint.Status(all.get(0).version(), placed.size(), total,
                all.size() == 1 ? notes.get(0) : all.size() + " bots: " + String.join(", ", notes), List.of());
        canvas.setPlaced(placed);
        publish();
    }

    /** Visible for tests: pretend these cells have been placed in the world. */
    public void setPlacedForTest(List<int[]> cells) {
        placed.clear();
        for (int[] c : cells) {
            placed.add(Blueprint.cellKey(c[0], c[1], c[2]));
        }
        canvas.setPlaced(placed);
        publish();
    }

    private void send(List<RtsOrders.Order> orders) {
        for (RtsOrders.Order o : orders) {
            commands.line(o.line(), "", r -> showReply(o, r));
        }
    }

    private void showReply(RtsOrders.Order o, CenterReply r) {
        reply.setText(o.bot() + ": " + (r == null ? "no reply"
                : (r.executed() ? "ran " : r.admitted() ? "sent " : "refused ") + "#" + r.line()
                + (r.reason() == null || r.reason().isBlank() ? "" : " (" + r.reason() + ")")));
    }

    private String host() {
        return view.hostBot() == null ? "" : view.hostBot();
    }

    private void publish() {
        syncLayer();
        canvas.setBlueprintKeepCamera(blueprint);
        canvas.setPlaced(placed);
        String s = blueprint.size() + " blocks · v" + blueprint.version();
        if (live && lastStatus != null) {
            s += " · bot: v" + lastStatus.version() + " " + placed.size() + "/" + blueprint.size()
                    + " placed · " + lastStatus.note();
        } else if (live) {
            s += " · live, waiting for status";
        }
        status.setText(s);
    }

    public Snapshot snapshot() {
        return new Snapshot(blueprint.write(), name.getText(), block.getText(), yField.getText(), hField.getText(),
                origin.getText(), path.getText(), live, allLayers.isSelected(), canvas.camera.copy(), Set.copyOf(placed));
    }

    public void restore(Snapshot snap) {
        if (snap == null) {
            return;
        }
        name.setText(snap.name);
        block.setText(snap.block);
        yField.setText(snap.y);
        hField.setText(snap.h);
        origin.setText(snap.origin);
        path.setText(snap.path);
        try {
            blueprint = Blueprint.read(snap.name, snap.ost);
        } catch (Exception e) {
            blueprint = new Blueprint("untitled");
        }
        canvas.camera.set(snap.camera);
        canvas.setShowAll(snap.allLayers);
        allLayers.setSelected(snap.allLayers);
        placed.clear();
        placed.addAll(snap.placed);
        setLive(snap.live);
        canvas.setBlueprintKeepCamera(blueprint);
        canvas.setPlaced(placed);
        publish();
    }

    static long cellKey(int x, int y, int z) {
        return (((long) (x & 0x3FFFFFF)) << 38) | (((long) (y & 0xFFF)) << 26) | (z & 0x3FFFFFF);
    }

    public record Snapshot(String ost, String name, String block, String y, String h, String origin, String path,
                           boolean live, boolean allLayers, EditorCamera camera, Set<Long> placed) {}
}
