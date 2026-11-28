package baritone.swarm.center.app;

import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ostinato: Phase 3 window, the wall's focus panel. One bot, opened without leaving the wall. The grid/roster/cluster
 * stays; this panel sits at its right until Esc or the close button, and provides single-bot quick command controls
 * ({@code goto}, {@code mine}, {@code cancel}, 3 s two-click {@code despawn}, and {@code Open on Map}) so Roster and
 * Cluster modes retain full per-bot control without duplicating forms across rows.
 *
 * <p>Names: {@code focus}, {@code focus:title}, {@code focus:close},
 * {@code focus:position|dimension|path|executions|leases|inventory|perf|link|age|gather|alerts},
 * {@code focus:field:arg}, {@code focus:button:goto|mine|cancel|despawn|map}, {@code focus:result}.
 */
final class FocusPanel extends JPanel {

    /** Narrow enough that 3 tile columns still fit in a 1280-wide window. */
    static final int WIDTH = 210;

    private final AppStyle style;
    private final Commands commands;
    private final JLabel title;
    private final JTextArea position, dimension, path, executions, leases, inventory, perf, link, age, gather, alerts;
    private final JTextField quickArg;
    private final JButton despawnBtn;
    private final JLabel quickResult;
    private final Runnable onClose;
    private final Consumer<String> onOpenOnMap;
    private SwarmView last = SwarmView.EMPTY;
    private String currentBot;
    private String cancelTarget;
    private boolean armedDespawn;
    private Timer disarmTimer;

    FocusPanel(AppStyle style, Runnable onClose) {
        this(style, null, onClose, null);
    }

    FocusPanel(AppStyle style, Commands commands, Runnable onClose, Consumer<String> onOpenOnMap) {
        super(new BorderLayout());
        this.style = style;
        this.commands = commands;
        this.onClose = onClose == null ? () -> {} : onClose;
        this.onOpenOnMap = onOpenOnMap == null ? n -> {} : onOpenOnMap;
        setBackground(style.surface());
        setOpaque(true);
        setName("focus");
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, style.line()));

        JPanel head = style.panel(new BorderLayout(6, 0), style.surface());
        head.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        title = style.label("Focus", style.text(), Font.BOLD, 14f);
        title.setName("focus:title");
        JButton close = style.button("Close");
        close.setName("focus:close");
        close.addActionListener(e -> this.onClose.run());
        head.add(title, BorderLayout.CENTER);
        head.add(close, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        JPanel body = new Body();
        body.setBackground(style.surface());
        body.setOpaque(true);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        position = row(body, "Position", "focus:position");
        dimension = row(body, "Dimension", "focus:dimension");
        path = row(body, "Path", "focus:path");
        executions = row(body, "Commands", "focus:executions");
        leases = row(body, "Leases", "focus:leases");
        inventory = row(body, "Inventory", "focus:inventory");
        perf = row(body, "Performance", "focus:perf");
        link = row(body, "Link", "focus:link");
        age = row(body, "Feed", "focus:age");
        gather = row(body, "Gather", "focus:gather");
        alerts = row(body, "Alerts", "focus:alerts");

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setBackground(style.surface());
        scroll.getViewport().setBackground(style.surface());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);

        // Quick single-bot command controls docked at bottom of FocusPanel
        JPanel controls = style.panel(null, style.surface());
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, style.line()),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        JLabel cap = style.label("Quick commands", style.faint(), Font.PLAIN, 10f);
        cap.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(cap);
        controls.add(Box.createVerticalStrut(3));

        quickArg = style.field(12, "x y z / block", "Target coordinates for goto, or [count] block for mine");
        quickArg.setName("focus:field:arg");
        quickArg.setAlignmentX(Component.LEFT_ALIGNMENT);
        quickArg.setMaximumSize(new Dimension(Integer.MAX_VALUE, quickArg.getPreferredSize().height));
        controls.add(quickArg);
        controls.add(Box.createVerticalStrut(4));

        JPanel row1 = style.panel(new FlowLayout(FlowLayout.LEFT, 3, 2), null);
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);
        row1.add(compactBtn("Goto", "focus:button:goto", false, this::runGoto));
        row1.add(compactBtn("Mine", "focus:button:mine", false, this::runMine));
        row1.add(compactBtn("Cancel", "focus:button:cancel", false, this::runCancel));
        controls.add(row1);

        JPanel row2 = style.panel(new FlowLayout(FlowLayout.LEFT, 3, 2), null);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);
        despawnBtn = compactBtn("Despawn", "focus:button:despawn", true, this::runDespawn);
        row2.add(despawnBtn);
        row2.add(compactBtn("Open on Map", "focus:button:map", false, this::runOpenOnMap));
        controls.add(row2);

        quickResult = style.label(" ", style.muted(), Font.PLAIN, 10.5f);
        quickResult.setName("focus:result");
        quickResult.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(quickResult);

        add(controls, BorderLayout.SOUTH);
        setVisible(false);
    }

    private JButton compactBtn(String text, String name, boolean danger, Runnable action) {
        JButton b = danger ? style.dangerButton(text) : style.button(text);
        b.setName(name);
        b.setFont(AppStyle.font(Font.PLAIN, 10.5f));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(danger ? style.danger() : style.line(), 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        b.addActionListener(e -> action.run());
        return b;
    }

    private JTextArea row(JPanel body, String caption, String name) {
        JLabel cap = style.label(caption, style.faint(), Font.PLAIN, 10f);
        cap.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Ostinato: long values (path, perf, inventory) wrap inside the narrow panel instead of being cut off.
        JTextArea value = new JTextArea(" ") {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        value.setFont(style.label(" ", style.text(), Font.PLAIN, 11f).getFont());
        value.setForeground(style.text());
        value.setOpaque(false);
        value.setEditable(false);
        value.setFocusable(false);
        value.setLineWrap(true);
        value.setWrapStyleWord(true);
        value.setBorder(BorderFactory.createEmptyBorder());
        value.setName(name);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(cap);
        body.add(value);
        body.add(Box.createVerticalStrut(6));
        return value;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WIDTH, 400);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(WIDTH, 0);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(WIDTH, Integer.MAX_VALUE);
    }

    /** Shows {@code bot}, or hides the panel when the selection is empty. */
    void show(SwarmView view, String bot) {
        last = view == null ? SwarmView.EMPTY : view;
        SwarmView.BotState b = bot == null || view == null ? null : view.bot(bot);
        if (b == null) {
            currentBot = null;
            cancelTarget = null;
            disarmDespawn();
            setVisible(false);
            return;
        }
        if (!b.name().equals(currentBot)) {
            disarmDespawn();
            quickResult.setText(" ");
        }
        currentBot = b.name();
        cancelTarget = TileText.cancelTarget(b.feed().executions());
        title.setText(b.name());
        position.setText(TileText.position(b.feed()));
        dimension.setText(TileDetails.dimension(b.feed()));
        path.setText(TileDetails.path(b.feed().path()));
        executions.setText(TileDetails.executions(b.feed().executions()));
        leases.setText(TileDetails.leases(b.feed().leases()));
        inventory.setText(TileText.inventory(b.feed().inventory()));
        perf.setText(TileDetails.perf(b.feed().perf()));
        link.setText(TileDetails.link(b.feed().link()));
        age.setText(TileDetails.age(b));
        gather.setText(TileDetails.gather(b.feed()));
        alerts.setText(TileDetails.alerts(view.alertsFor(b.name())));
        setVisible(true);
        revalidate();
    }

    private void runGoto() {
        if (commands == null || currentBot == null) {
            return;
        }
        String arg = quickArg.getText().trim();
        if (arg.isEmpty()) {
            showWarn("enter x y z");
            return;
        }
        ButtonModel form = tileForm("goto");
        if (form != null) {
            commands.press(form, Map.of("target", arg), currentBot, this::showReply);
        } else {
            commands.line(routedLine(currentBot, "goto " + arg), currentBot, this::showReply);
        }
    }

    private void runMine() {
        if (commands == null || currentBot == null) {
            return;
        }
        Map<String, String> parsed = BatchDispatchBar.parseMineArg(quickArg.getText());
        if (parsed.isEmpty()) {
            showWarn("enter block");
            return;
        }
        ButtonModel form = tileForm("mine");
        if (form != null) {
            commands.press(form, parsed, currentBot, this::showReply);
        } else {
            commands.line(routedLine(currentBot, "mine " + quickArg.getText().trim()), currentBot, this::showReply);
        }
    }

    private void runCancel() {
        if (commands == null || currentBot == null) {
            return;
        }
        ButtonModel form = tileForm("cancel");
        if (form != null && cancelTarget != null) {
            commands.press(form, Map.of("id", cancelTarget), currentBot, this::showReply);
        } else {
            commands.line(routedLine(currentBot, "cancel"), currentBot, this::showReply);
        }
    }

    private void runDespawn() {
        if (commands == null || currentBot == null) {
            return;
        }
        if (!armedDespawn) {
            armedDespawn = true;
            despawnBtn.setText("Confirm despawn");
            if (disarmTimer != null) {
                disarmTimer.stop();
            }
            disarmTimer = new Timer(3000, e -> disarmDespawn());
            disarmTimer.setRepeats(false);
            disarmTimer.start();
            return;
        }
        String targetBot = currentBot;
        disarmDespawn();
        ButtonModel form = tileForm("despawn");
        if (form != null) {
            commands.press(form, Map.of("name", targetBot), targetBot, this::showReply);
        } else {
            commands.line("swarm despawn " + targetBot, targetBot, this::showReply);
        }
    }

    private void runOpenOnMap() {
        if (currentBot != null) {
            onOpenOnMap.accept(currentBot);
        }
    }

    private void disarmDespawn() {
        armedDespawn = false;
        if (disarmTimer != null) {
            disarmTimer.stop();
            disarmTimer = null;
        }
        if (despawnBtn != null) {
            despawnBtn.setText("Despawn");
        }
    }

    private ButtonModel tileForm(String formId) {
        if (last.catalog() == null) {
            return null;
        }
        for (ButtonModel m : last.catalog().models(FormPlacement.TILE)) {
            if (formId.equals(m.form().id())) {
                return m;
            }
        }
        return null;
    }

    private String routedLine(String bot, String cmd) {
        String target = TileRoute.target(bot, last.hostBot());
        return target == null ? cmd : "swarm send " + target + " " + cmd;
    }

    private void showReply(CenterReply r) {
        quickResult.setText((r.admitted() ? TileStatus.Level.OK.glyph : TileStatus.Level.WARN.glyph) + " " + r.summary());
        quickResult.setForeground(r.admitted() ? style.ok() : style.warn());
    }

    private void showWarn(String msg) {
        quickResult.setText(TileStatus.Level.WARN.glyph + " " + msg);
        quickResult.setForeground(style.warn());
    }

    /** Follows the viewport's width, so the values wrap rather than scroll sideways. */
    private static final class Body extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(16, r.height - 16); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
