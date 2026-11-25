package baritone.swarm.center.app;

import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.rts.Blueprint;
import baritone.swarm.rts.RoleOrder;

import javax.swing.JButton;
import javax.swing.JComboBox;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ostinato: RTS mode panel under the war-room map ({@code docs/rts-mode.md} section 3). Role assignment for the
 * selection, the blueprint paint tool and live build, and the economy line. Every action is an existing registry
 * line ({@code role}, {@code goto}, {@code blueprint}) sent through {@link Commands}; the blueprint itself is a file
 * in the feed directory that same-PC bots read.
 *
 * <p>Names: {@code rts}, {@code rts:role}, {@code rts:chest}, {@code rts:ore}, {@code rts:radius}, {@code rts:batch},
 * {@code rts:assign}, {@code rts:idle}, {@code rts:preview}, {@code rts:bp:name}, {@code rts:bp:block},
 * {@code rts:bp:y}, {@code rts:bp:paint}, {@code rts:bp:live}, {@code rts:bp:clear}, {@code rts:bp:status},
 * {@code rts:economy}, {@code rts:reply}.
 */
public final class RtsPanel {

    private final AppStyle style;
    private final Commands commands;
    private final Supplier<List<String>> selection;
    private final Consumer<Boolean> paintArmed;
    private final Consumer<List<int[]>> ghostOut;
    private final JPanel root;
    private final JComboBox<String> role;
    private final JTextField chest, ore, radius, batch, bpName, bpBlock, bpY;
    private final JLabel preview, bpStatus, economy, reply;
    private final JToggleButton paint;
    private final Timer writeLater;
    private final Timer statusPoll;
    private SwarmView view = SwarmView.EMPTY;
    private Path dir;
    private Blueprint blueprint = new Blueprint("base");
    private Set<Long> placed = new HashSet<>();
    private Blueprint.Status status;

    public RtsPanel(AppStyle style, Commands commands, Supplier<List<String>> selection, Consumer<Boolean> paintArmed,
                    Consumer<List<int[]>> ghostOut) {
        this.style = style;
        this.commands = commands;
        this.selection = selection;
        this.paintArmed = paintArmed;
        this.ghostOut = ghostOut;
        root = style.panel(new GridLayout(4, 1), style.surface());
        root.setName("rts");
        root.setVisible(false);

        JPanel roles = row();
        roles.add(style.label("Role", style.muted(), Font.BOLD, 12f));
        role = new JComboBox<>(new String[]{"farmer", "miner", "storage", "armorer", "idle"});
        role.setName("rts:role");
        role.addActionListener(e -> refreshPreview());
        roles.add(role);
        chest = field(roles, "rts:chest", 10, "chest x,y,z", "Storage chest the role deposits into");
        ore = field(roles, "rts:ore", 12, "ore a,b", "Miner: blocks to mine, e.g. iron_ore,coal_ore");
        radius = field(roles, "rts:radius", 3, "r", "Farmer: radius around the bot's post");
        batch = field(roles, "rts:batch", 4, "batch", "Items before a deposit (default 64)");
        JButton assign = style.button("Assign to selected");
        assign.setName("rts:assign");
        assign.addActionListener(e -> assign(false));
        roles.add(assign);
        JButton idle = style.button("Idle");
        idle.setName("rts:idle");
        idle.addActionListener(e -> assign(true));
        roles.add(idle);
        preview = style.label(" ", style.muted(), Font.PLAIN, 11f);
        preview.setName("rts:preview");
        roles.add(preview);
        root.add(roles);

        JPanel bp = row();
        bp.add(style.label("Blueprint", style.muted(), Font.BOLD, 12f));
        bpName = field(bp, "rts:bp:name", 8, "name", "Blueprint name (file in the feed folder's blueprints/)");
        bpName.setText("base");
        bpBlock = field(bp, "rts:bp:block", 12, "block", "Block to paint, e.g. oak_planks");
        bpBlock.setText("oak_planks");
        bpY = field(bp, "rts:bp:y", 4, "y", "Layer height in world Y");
        paint = style.toggle("Paint");
        paint.setName("rts:bp:paint");
        paint.setToolTipText("Left click/drag paints, right click erases");
        paint.addActionListener(e -> paintArmed.accept(paint.isSelected()));
        bp.add(paint);
        JButton live = style.button("Build live");
        live.setName("rts:bp:live");
        live.addActionListener(e -> buildLive());
        bp.add(live);
        JButton clear = style.button("Clear");
        clear.setName("rts:bp:clear");
        clear.addActionListener(e -> {
            blueprint = new Blueprint(blueprint.name());
            changed();
        });
        bp.add(clear);
        bpStatus = style.label("0 blocks", style.muted(), Font.PLAIN, 11f);
        bpStatus.setName("rts:bp:status");
        bp.add(bpStatus);
        root.add(bp);

        economy = style.label(" ", style.text(), Font.PLAIN, 12f);
        economy.setName("rts:economy");
        JPanel e1 = row();
        e1.add(economy);
        root.add(e1);
        reply = style.label(" ", style.muted(), Font.PLAIN, 11f);
        reply.setName("rts:reply");
        JPanel e2 = row();
        e2.add(reply);
        root.add(e2);

        writeLater = new Timer(500, e -> writeNow());
        writeLater.setRepeats(false);
        statusPoll = new Timer(1000, e -> readStatus());
        statusPoll.start();
        refreshPreview();
    }

    private JPanel row() {
        return style.panel(new FlowLayout(FlowLayout.LEFT, 6, 2), null);
    }

    private JTextField field(JPanel row, String name, int cols, String prompt, String tip) {
        JTextField f = style.field(cols, prompt, tip);
        f.setName(name);
        f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshPreview(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshPreview(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshPreview(); }
        });
        row.add(f);
        return f;
    }

    public JComponent component() {
        return root;
    }

    public void setVisible(boolean on) {
        root.setVisible(on);
        if (!on && paint.isSelected()) {
            paint.setSelected(false);
            paintArmed.accept(false);
        }
    }

    public boolean visible() {
        return root.isVisible();
    }

    /** {@code <feed dir>/blueprints}. Loads the named blueprint when it already exists. */
    public void setDir(Path feedDir) {
        dir = feedDir == null ? null : feedDir.resolve("blueprints");
        load();
    }

    private void load() {
        if (dir == null) {
            return;
        }
        try {
            Path f = dir.resolve(Blueprint.fileName(bpName.getText()));
            if (Files.isRegularFile(f)) {
                blueprint = Blueprint.read(bpName.getText(), Files.readString(f));
                publish();
            }
        } catch (Exception ignored) {
            // a bad or missing file starts empty
        }
    }

    /** The role line the Assign button sends, or the reason it cannot. */
    String roleLine() {
        StringBuilder sb = new StringBuilder("role ").append(role.getSelectedItem());
        if (!"idle".equals(role.getSelectedItem())) {
            String c = chest.getText().trim();
            if (!c.isEmpty()) {
                sb.append(" ").append("storage".equals(role.getSelectedItem()) ? "chests=" : "chest=").append(c);
            }
            if (!ore.getText().isBlank() && "miner".equals(role.getSelectedItem())) {
                sb.append(" ore=").append(ore.getText().trim());
            }
            if (!radius.getText().isBlank() && "farmer".equals(role.getSelectedItem())) {
                sb.append(" r=").append(radius.getText().trim());
            }
            if (!batch.getText().isBlank()) {
                sb.append(" batch=").append(batch.getText().trim());
            }
        }
        return RoleOrder.parse(sb.toString()).line();
    }

    private void refreshPreview() {
        try {
            String line = roleLine();
            List<String> sel = selection.get();
            preview.setText(sel.isEmpty() ? "select bots, then #" + line : "#" + line + " → " + String.join(", ", sel));
        } catch (IllegalArgumentException e) {
            preview.setText(e.getMessage());
        }
    }

    void assign(boolean idle) {
        List<String> sel = selection.get();
        if (sel.isEmpty()) {
            reply.setText("select bots first");
            return;
        }
        RoleOrder order;
        try {
            order = idle ? RoleOrder.idle() : RoleOrder.parse(roleLine());
        } catch (IllegalArgumentException e) {
            reply.setText(e.getMessage());
            return;
        }
        send(RtsOrders.role(sel, host(), order));
    }

    /** Right-click order from the map. */
    public void move(int x, int z) {
        List<String> sel = selection.get();
        if (sel.isEmpty()) {
            return;
        }
        send(RtsOrders.move(sel, host(), x, z));
    }

    private String host() {
        return view.hostBot() == null ? "" : view.hostBot();
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

    int layerY() {
        try {
            return Integer.parseInt(bpY.getText().trim());
        } catch (NumberFormatException e) {
            for (String n : selection.get()) {
                SwarmView.BotState b = view.bot(n);
                if (b != null && b.feed().pos() != null) {
                    return b.feed().pos().blockY();
                }
            }
            return 64;
        }
    }

    /** From the map while Paint is on. */
    public void paint(int x, int z, boolean erase) {
        try {
            Blueprint target = blueprint.name().equals(Blueprint.safeName(bpName.getText())) ? blueprint
                    : new Blueprint(bpName.getText());
            blueprint = target;
            int y = layerY();
            boolean changed = erase ? blueprint.erase(x, y, z) : blueprint.paint(x, y, z, bpBlock.getText());
            if (changed) {
                changed();
            }
        } catch (IllegalArgumentException e) {
            bpStatus.setText(e.getMessage());
        }
    }

    public Blueprint blueprint() {
        return blueprint;
    }

    private void changed() {
        publish();
        writeLater.restart();
    }

    /** Write the blueprint file now (the debounce calls this; tests call it directly). */
    public void writeNow() {
        writeLater.stop();
        if (dir == null) {
            bpStatus.setText("no feed folder: blueprint not saved");
            return;
        }
        try {
            CenterFeed.writeAtomic(dir.resolve(Blueprint.fileName(blueprint.name())), blueprint.write());
        } catch (Exception e) {
            bpStatus.setText("save failed: " + e.getMessage());
        }
    }

    void buildLive() {
        writeNow();
        List<String> sel = selection.get();
        List<String> bots = sel.isEmpty() ? (host().isEmpty() ? List.of() : List.of(host())) : sel;
        if (bots.isEmpty()) {
            reply.setText("no bot to build with");
            return;
        }
        List<RtsOrders.Order> orders = new ArrayList<>();
        for (String b : bots) {
            orders.add(RtsOrders.blueprint(b, host(), blueprint.name()));
        }
        send(orders);
    }

    void readStatus() {
        if (dir == null) {
            return;
        }
        try {
            Path f = dir.resolve(Blueprint.statusName(blueprint.name()));
            if (!Files.isRegularFile(f)) {
                return;
            }
            Blueprint.Status s = Blueprint.readStatus(Files.readString(f));
            Set<Long> p = new HashSet<>();
            for (int[] c : s.cells()) {
                p.add(key(c[0], c[1], c[2]));
            }
            status = s;
            placed = p;
            publish();
        } catch (Exception ignored) {
            // the bot is mid-rename; next second
        }
    }

    private static long key(int x, int y, int z) {
        return (((long) (x & 0x3FFFFFF)) << 38) | (((long) (y & 0xFFF)) << 26) | (z & 0x3FFFFFF);
    }

    private void publish() {
        List<int[]> cells = new ArrayList<>();
        int done = 0;
        for (Blueprint.Cell c : blueprint.cells()) {
            boolean in = placed.contains(key(c.x(), c.y(), c.z()));
            if (in) {
                done++;
            }
            cells.add(new int[]{c.x(), c.z(), BlockColors.color(c.block()), in ? 1 : 0});
        }
        ghostOut.accept(cells);
        String s = blueprint.size() + " blocks · v" + blueprint.version();
        if (status != null) {
            s += " · bot: v" + status.version() + " " + done + "/" + blueprint.size() + " placed · " + status.note();
        }
        bpStatus.setText(s);
    }

    public void update(SwarmView v) {
        view = v == null ? SwarmView.EMPTY : v;
        economy.setText(RtsOrders.economy(view));
        refreshPreview();
    }
}
