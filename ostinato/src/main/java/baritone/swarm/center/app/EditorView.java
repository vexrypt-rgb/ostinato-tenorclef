package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.rts.BlockStateSpec;
import baritone.swarm.rts.Blueprint;
import baritone.swarm.rts.BlueprintRepairSession;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
    private final JToggleButton bomToggle; // Ostinato: P3 Materials BOM & partition sidebar
    private final JPanel bomPanel;
    private final JLabel bomSummary;
    private final JTextArea bomText;
    private final JLabel diffSummary;
    private final JTextArea diffText;
    private final JComboBox<BlueprintSplitter.Strategy> splitStrategy;
    private Consumer<Blueprint> onShowOnMap = bp -> {};
    private final Timer writeLater;
    private final Timer statusPoll;
    private Blueprint blueprint = new Blueprint("untitled");
    private Path dir;
    private SwarmView view = SwarmView.EMPTY;
    private boolean live;
    private final Set<Long> placed = new HashSet<>();
    private final List<Blueprint.Discrepancy> botDiscrepancies = new ArrayList<>();
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
        bomToggle = style.toggle("Materials & Split");
        bomToggle.setName("ed:bom:toggle");
        bomToggle.setSelected(true);
        bomToggle.setToolTipText("Compare blueprint bill of materials against swarm inventory and chests, and split regions");
        row1.add(bomToggle);
        JButton ghostBtn = style.button("Show on Map");
        ghostBtn.setName("ed:ghost");
        ghostBtn.setToolTipText("Display this blueprint on the War Room map and switch to Map view");
        ghostBtn.addActionListener(e -> onShowOnMap.accept(blueprint));
        row1.add(ghostBtn);
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
        JButton verifyBtn = style.button("Verify");
        verifyBtn.setName("ed:verify");
        verifyBtn.setToolTipText("Compare blueprint block types and explicit states against the live world");
        verifyBtn.addActionListener(e -> verifyLive());
        JButton repairBtn = style.button("Repair");
        repairBtn.setName("ed:repair");
        repairBtn.setToolTipText("Repair highlighted misoriented/wrong-state cells in the live world");
        repairBtn.addActionListener(e -> repairMisoriented());
        JButton clear = style.button("Clear");
        clear.setName("ed:clear");
        clear.addActionListener(e -> clear());
        row3.add(load);
        row3.add(save);
        row3.add(imp);
        row3.add(exp);
        row3.add(liveBtn);
        row3.add(verifyBtn);
        row3.add(repairBtn);
        row3.add(clear);
        bar.add(row3);

        JPanel south = style.panel(new BorderLayout(8, 0), style.surface());
        south.setBorder(BorderFactory.createEmptyBorder(2, 10, 4, 10));
        status = named(style.label("empty", style.muted(), Font.PLAIN, 12f), "ed:status");
        reply = named(style.label(" ", style.muted(), Font.PLAIN, 12f), "ed:reply");
        south.add(status, BorderLayout.WEST);
        south.add(reply, BorderLayout.EAST);

        bomPanel = style.panel(new BorderLayout(0, 6), style.surface());
        bomPanel.setName("ed:bom:panel");
        bomPanel.setPreferredSize(new Dimension(265, 300));
        bomPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, style.line()),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        JPanel bomHead = style.panel(new GridLayout(2, 1, 0, 2), null);
        bomHead.add(style.label("Materials vs Swarm", style.text(), Font.BOLD, 12f));
        bomSummary = named(style.label("No blocks yet", style.muted(), Font.PLAIN, 11f), "ed:bom:summary");
        bomHead.add(bomSummary);
        bomPanel.add(bomHead, BorderLayout.NORTH);

        bomText = new JTextArea();
        bomText.setName("ed:bom:text");
        bomText.setEditable(false);
        bomText.setLineWrap(true);
        bomText.setWrapStyleWord(true);
        bomText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        bomText.setBackground(style.bg());
        bomText.setForeground(style.text());
        bomText.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane bomScroll = new JScrollPane(bomText);
        bomScroll.setBorder(BorderFactory.createLineBorder(style.line(), 1));

        JPanel diffSection = style.panel(new BorderLayout(0, 4), style.surface());
        JPanel diffHead = style.panel(new GridLayout(2, 1, 0, 2), null);
        diffHead.add(style.label("Discrepancies", style.text(), Font.BOLD, 12f));
        diffSummary = named(style.label("No discrepancies", style.muted(), Font.PLAIN, 11f), "ed:diff:summary");
        diffHead.add(diffSummary);
        diffSection.add(diffHead, BorderLayout.NORTH);
        diffText = new JTextArea();
        diffText.setName("ed:diff:text");
        diffText.setEditable(false);
        diffText.setLineWrap(true);
        diffText.setWrapStyleWord(true);
        diffText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        diffText.setBackground(style.bg());
        diffText.setForeground(style.text());
        diffText.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane diffScroll = new JScrollPane(diffText);
        diffScroll.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        diffSection.add(diffScroll, BorderLayout.CENTER);

        JPanel sideCenter = style.panel(new GridLayout(2, 1, 0, 6), null);
        sideCenter.add(bomScroll);
        sideCenter.add(diffSection);
        bomPanel.add(sideCenter, BorderLayout.CENTER);

        JPanel bomFoot = style.panel(new GridLayout(2, 1, 0, 4), null);
        JPanel splitRow = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 0), null);
        splitStrategy = new JComboBox<>(BlueprintSplitter.Strategy.values());
        splitStrategy.setName("ed:split:strategy");
        splitStrategy.setFont(AppStyle.font(Font.PLAIN, 11f));
        JButton splitBtn = style.button("Split build");
        splitBtn.setName("ed:split:send");
        splitBtn.setToolTipText("Partition blueprint across selected bots using the chosen strategy");
        splitBtn.addActionListener(e -> splitAndBuild());
        splitRow.add(splitStrategy);
        splitRow.add(splitBtn);
        JPanel gatherRow = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 0), null);
        JButton gatherBtn = style.button("Gather missing");
        gatherBtn.setName("ed:bom:gather");
        gatherBtn.setToolTipText("Ask the swarm to gather every missing material from the BOM");
        gatherBtn.addActionListener(e -> gatherMissing());
        gatherRow.add(gatherBtn);
        bomFoot.add(splitRow);
        bomFoot.add(gatherRow);
        bomPanel.add(bomFoot, BorderLayout.SOUTH);

        bomToggle.addActionListener(e -> {
            bomPanel.setVisible(bomToggle.isSelected());
            root.revalidate();
        });

        root.add(bar, BorderLayout.NORTH);
        root.add(canvas, BorderLayout.CENTER);
        root.add(bomPanel, BorderLayout.EAST);
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
        refreshBom();
    }

    /** Ostinato: P3 show current blueprint on the War Room map. */
    public void onShowOnMap(Consumer<Blueprint> show) {
        this.onShowOnMap = show == null ? bp -> {} : show;
    }

    public MaterialCompare.Report materialReport() {
        return MaterialCompare.compare(blueprint, placed, view);
    }

    public BlueprintSplitter.Strategy splitStrategy() {
        BlueprintSplitter.Strategy s = (BlueprintSplitter.Strategy) splitStrategy.getSelectedItem();
        return s == null ? BlueprintSplitter.Strategy.STRIPS : s;
    }

    public void setSplitStrategy(BlueprintSplitter.Strategy s) {
        if (s != null) {
            splitStrategy.setSelectedItem(s);
        }
    }

    /** Ostinato: partition the blueprint across selected bots (or all swarm bots) via STRIPS/GRID/LAYERS. */
    public void splitAndBuild() {
        setLive(true);
        writeNow();
        List<String> sel = selection.get();
        List<String> bots = new ArrayList<>();
        if (sel != null && !sel.isEmpty()) {
            bots.addAll(sel);
        } else {
            for (SwarmView.BotState b : view.bots()) {
                bots.add(b.name());
            }
        }
        RtsOrders.Plan plan = BlueprintSplitter.split(bots, (char) dim.getAsInt(), blueprint, splitStrategy());
        if (plan.reason() != null) {
            reply.setText(plan.reason());
            return;
        }
        send(plan.orders());
    }

    /** Ostinato: dispatch swarm gather orders for every missing block in the current BOM. */
    public void gatherMissing() {
        MaterialCompare.Report rep = materialReport();
        if (rep.totalMissing() <= 0) {
            reply.setText("all materials available in swarm inventory and chests");
            return;
        }
        String host = host();
        int count = 0;
        for (MaterialCompare.Row row : rep.rows()) {
            if (row.missing() > 0) {
                commands.line("swarm gather " + row.block() + " " + row.missing(), host, null);
                count++;
            }
        }
        reply.setText("requested gather for " + count + " missing block type" + (count == 1 ? "" : "s"));
    }

    private void refreshBom() {
        if (bomSummary == null || bomText == null) {
            return;
        }
        MaterialCompare.Report rep = materialReport();
        if (rep.totalBlocks() == 0) {
            bomSummary.setText("No blocks in blueprint");
            bomSummary.setForeground(style.muted());
            bomText.setText("Paint or import blocks to compare\nrequired materials against swarm\ninventory and observed chests.");
            return;
        }
        if (rep.allReady()) {
            bomSummary.setText(baritone.swarm.center.TileStatus.Level.OK.glyph + " Ready · "
                    + rep.totalRemaining() + " to place (all stocked)");
            bomSummary.setForeground(style.ok());
        } else {
            bomSummary.setText(baritone.swarm.center.TileStatus.Level.WARN.glyph + " Short "
                    + rep.totalMissing() + " of " + rep.totalRemaining() + " remaining");
            bomSummary.setForeground(style.warn());
        }
        StringBuilder sb = new StringBuilder();
        for (MaterialCompare.Row r : rep.rows()) {
            String glyph = r.ready() ? "●" : "▲";
            sb.append(glyph).append(' ').append(r.block());
            if (r.missing() > 0) {
                sb.append(" (-").append(r.missing()).append(')');
            }
            sb.append('\n')
                    .append("  need ").append(r.remaining())
                    .append(" · bots ").append(r.inBots())
                    .append(" · chests ").append(r.inChests())
                    .append('\n');
        }
        bomText.setText(sb.toString().trim());
        bomText.setCaretPosition(0);
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
                next.paint(c.x(), c.y(), c.z(), c.block(), c.properties());
            }
            blueprint = next;
        }
    }

    private void changed() {
        pruneStaleTracking();
        canvas.setBlueprint(blueprint);
        // setBlueprint also fits the camera; keep the user's orbit after the first cell
        publish();
        if (live) {
            writeLater.restart();
        }
    }

    private void pruneStaleTracking() {
        Set<Long> validKeys = new HashSet<>();
        for (Blueprint.Cell c : blueprint.cells()) {
            long k = Blueprint.cellKey(c.x(), c.y(), c.z());
            validKeys.add(k);
            if (!BlockStateSpec.isSupported(c.block(), c.properties())) {
                placed.remove(k);
            }
        }
        placed.retainAll(validKeys);
        botDiscrepancies.removeIf(d -> !validKeys.contains(Blueprint.cellKey(d.x(), d.y(), d.z())));
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
            botDiscrepancies.clear();
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
            botDiscrepancies.clear();
            lastStatus = null;
            if (live) {
                writeLater.restart();
            }
            publish();
            int unsup = unsupportedDiscrepancies().size();
            status.setText("imported " + blueprint.size() + " blocks at " + o[0] + "," + o[1] + "," + o[2]
                    + (unsup > 0 ? " · " + unsup + " unsupported" : ""));
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
        botDiscrepancies.clear();
        lastStatus = null;
        canvas.setBlueprint(blueprint);
        canvas.camera.fit(null);
        publish();
    }

    /** Merged discrepancy list: bot-reported discrepancies + static unsupported explicit-state checks. */
    public List<Blueprint.Discrepancy> discrepancies() {
        Map<Long, Blueprint.Discrepancy> merged = new LinkedHashMap<>();
        for (Blueprint.Discrepancy d : botDiscrepancies) {
            if (blueprint.at(d.x(), d.y(), d.z()) != null) {
                merged.put(Blueprint.cellKey(d.x(), d.y(), d.z()), d);
            }
        }
        for (Blueprint.Discrepancy u : BlueprintRepairSession.unsupportedCells(blueprint)) {
            // Static unsupported requirements always take precedence and can never be marked complete.
            merged.put(Blueprint.cellKey(u.x(), u.y(), u.z()), u);
        }
        return List.copyOf(merged.values());
    }

    public List<Blueprint.Discrepancy> wrongStateDiscrepancies() {
        List<Blueprint.Discrepancy> out = new ArrayList<>();
        for (Blueprint.Discrepancy d : discrepancies()) {
            if (d.kind() == Blueprint.DiscrepancyKind.WRONG_STATE) {
                out.add(d);
            }
        }
        return List.copyOf(out);
    }

    public List<Blueprint.Discrepancy> unsupportedDiscrepancies() {
        List<Blueprint.Discrepancy> out = new ArrayList<>();
        for (Blueprint.Discrepancy d : discrepancies()) {
            if (d.kind() == Blueprint.DiscrepancyKind.UNSUPPORTED) {
                out.add(d);
            }
        }
        return List.copyOf(out);
    }

    public void verifyLive() {
        setLive(true);
        writeNow();
        List<String> bots = targetBots();
        if (bots.isEmpty()) {
            reply.setText("no bot to verify with");
            return;
        }
        send(List.of(RtsOrders.blueprintVerify(bots.get(0), host(), blueprint.name())));
    }

    /** Repairs all currently highlighted WRONG_STATE cells (or requests wrong-state repair if none read yet). */
    public void repairMisoriented() {
        List<int[]> cells = new ArrayList<>();
        for (Blueprint.Discrepancy d : wrongStateDiscrepancies()) {
            cells.add(new int[]{d.x(), d.y(), d.z()});
        }
        repairCells(cells);
    }

    /** Requests a scoped repair for the given cells (ignoring unsupported cells). */
    public void repairCells(List<int[]> requested) {
        setLive(true);
        writeNow();
        List<String> bots = targetBots();
        if (bots.isEmpty()) {
            reply.setText("no bot to repair with");
            return;
        }
        List<int[]> scoped = new ArrayList<>();
        int skippedUnsupported = 0;
        if (requested != null) {
            for (int[] c : requested) {
                if (c == null || c.length < 3) {
                    continue;
                }
                Blueprint.Cell cell = blueprint.at(c[0], c[1], c[2]);
                if (cell == null) {
                    continue;
                }
                if (!BlockStateSpec.isSupported(cell.block(), cell.properties())) {
                    skippedUnsupported++;
                    continue;
                }
                scoped.add(new int[]{c[0], c[1], c[2]});
            }
        }
        if (requested != null && !requested.isEmpty() && scoped.isEmpty()) {
            reply.setText(skippedUnsupported > 0
                    ? "cannot repair " + skippedUnsupported + " unsupported cell(s)"
                    : "no matching blueprint cells to repair");
            return;
        }
        send(List.of(RtsOrders.blueprintRepair(bots.get(0), host(), blueprint.name(), scoped)));
    }

    private List<String> targetBots() {
        List<String> sel = selection.get();
        String host = host();
        return sel.isEmpty() ? (host.isEmpty() ? List.of() : List.of(host)) : sel;
    }

    public void buildLive() {
        setLive(true);
        writeNow();
        List<String> bots = targetBots();
        String host = host();
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
        botDiscrepancies.clear();
        Map<Long, Blueprint.Discrepancy> diffMap = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int total = 0;
        for (Blueprint.Status s : all) {
            for (int[] c : s.cells()) {
                Blueprint.Cell cell = blueprint.at(c[0], c[1], c[2]);
                if (cell != null && BlockStateSpec.isSupported(cell.block(), cell.properties())) {
                    placed.add(Blueprint.cellKey(c[0], c[1], c[2]));
                }
            }
            for (Blueprint.Discrepancy d : s.discrepancies()) {
                long k = Blueprint.cellKey(d.x(), d.y(), d.z());
                if (blueprint.at(d.x(), d.y(), d.z()) != null) {
                    diffMap.put(k, d);
                    placed.remove(k);
                }
            }
            notes.add(s.note());
            total = Math.max(total, s.total());
        }
        botDiscrepancies.addAll(diffMap.values());
        List<Blueprint.Discrepancy> merged = discrepancies();
        String note = all.size() == 1 ? notes.get(0) : all.size() + " bots: " + String.join(", ", notes);
        if (!merged.isEmpty() && "done".equalsIgnoreCase(note)) {
            note = merged.size() + " unresolved";
        }
        lastStatus = new Blueprint.Status(all.get(0).version(), placed.size(), total, note, List.of(), merged);
        canvas.setPlaced(placed);
        canvas.setDiscrepancies(merged);
        publish();
    }

    /** Visible for tests: pretend these cells have been placed in the world. */
    public void setPlacedForTest(List<int[]> cells) {
        placed.clear();
        for (int[] c : cells) {
            Blueprint.Cell cell = blueprint.at(c[0], c[1], c[2]);
            if (cell != null && BlockStateSpec.isSupported(cell.block(), cell.properties())) {
                placed.add(Blueprint.cellKey(c[0], c[1], c[2]));
            }
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
        pruneStaleTracking();
        List<Blueprint.Discrepancy> diffs = discrepancies();
        canvas.setBlueprintKeepCamera(blueprint);
        canvas.setPlaced(placed);
        canvas.setDiscrepancies(diffs);
        int wrongState = 0, unsupported = 0;
        for (Blueprint.Discrepancy d : diffs) {
            if (d.kind() == Blueprint.DiscrepancyKind.WRONG_STATE) {
                wrongState++;
            } else if (d.kind() == Blueprint.DiscrepancyKind.UNSUPPORTED) {
                unsupported++;
            }
        }
        String s = blueprint.size() + " blocks · v" + blueprint.version();
        if (live && lastStatus != null) {
            String note = lastStatus.note();
            if (!diffs.isEmpty() && "done".equalsIgnoreCase(note)) {
                note = diffs.size() + " unresolved";
            }
            s += " · bot: v" + lastStatus.version() + " " + placed.size() + "/" + blueprint.size()
                    + " placed · " + note;
        } else if (live) {
            s += " · live, waiting for status";
        }
        if (wrongState > 0) {
            s += " · " + wrongState + " wrong-state";
        }
        if (unsupported > 0) {
            s += " · " + unsupported + " unsupported";
        }
        status.setText(s);
        refreshBom();
        refreshDiscrepancies(diffs);
    }

    private void refreshDiscrepancies(List<Blueprint.Discrepancy> diffs) {
        if (diffSummary == null || diffText == null) {
            return;
        }
        if (diffs.isEmpty()) {
            diffSummary.setText(live && lastStatus != null ? "All matched" : "No discrepancies");
            diffSummary.setForeground(live && lastStatus != null ? style.ok() : style.muted());
            diffText.setText(live ? "World matches every blueprint cell." : "Verify in Live mode to compare states.");
            return;
        }
        int wrongState = 0, unsup = 0, other = 0;
        StringBuilder sb = new StringBuilder();
        for (Blueprint.Discrepancy d : diffs) {
            if (d.kind() == Blueprint.DiscrepancyKind.WRONG_STATE) {
                wrongState++;
            } else if (d.kind() == Blueprint.DiscrepancyKind.UNSUPPORTED) {
                unsup++;
            } else {
                other++;
            }
            sb.append(d.kind()).append(' ')
                    .append(d.x()).append(',').append(d.y()).append(',').append(d.z())
                    .append(' ').append(d.expected());
            if (!"-".equals(d.actual()) && !d.actual().isEmpty()) {
                sb.append(" (was ").append(d.actual()).append(')');
            }
            if (!d.reason().isEmpty()) {
                sb.append(" — ").append(d.reason());
            }
            sb.append('\n');
        }
        List<String> summaryParts = new ArrayList<>();
        if (wrongState > 0) summaryParts.add(wrongState + " wrong-state");
        if (unsup > 0) summaryParts.add(unsup + " unsupported");
        if (other > 0) summaryParts.add(other + " other");
        diffSummary.setText(baritone.swarm.center.TileStatus.Level.WARN.glyph + " " + String.join(" · ", summaryParts));
        diffSummary.setForeground(style.warn());
        diffText.setText(sb.toString().trim());
        diffText.setCaretPosition(0);
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
