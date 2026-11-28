package baritone.swarm.center.app;

import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.TileStatus;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: multi-bot selection and unified batch swarm dispatch bar. Lets an operator select filtered/alerting bots
 * and dispatch {@code goto}, {@code mine}, {@code @swarm acquire}, {@code cancel}, {@code stop}, or a 3-second
 * two-click confirmed {@code despawn} through {@link Commands}.
 *
 * <p>Names: {@code batch.bar}, {@code batch.selected}, {@code batch.select.filtered}, {@code batch.select.alerts},
 * {@code batch.select.clear}, {@code batch.arg}, {@code batch.btn.goto}, {@code batch.btn.mine},
 * {@code batch.btn.acquire}, {@code batch.btn.cancel}, {@code batch.btn.stop}, {@code batch.btn.despawn},
 * {@code batch.status}.
 */
public final class BatchDispatchBar extends JPanel {

    private final AppStyle style;
    private final Commands commands;
    private final JLabel selectedLabel;
    private final JTextField argField;
    private final JButton despawnButton;
    private final JLabel statusLabel;
    private SwarmView last = SwarmView.EMPTY;
    private List<String> targets = List.of();
    private boolean armedDespawn;
    private Timer disarmTimer;
    private Runnable onSelectFiltered = () -> {};
    private Runnable onSelectAlerts = () -> {};
    private Runnable onClearSelection = () -> {};

    public BatchDispatchBar(AppStyle style, Commands commands) {
        super(new BorderLayout(8, 0));
        this.style = style;
        this.commands = commands;
        setName("batch.bar");
        setBackground(style.surface());
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, style.line()),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));

        JPanel left = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 0), null);
        selectedLabel = style.label("0 selected", style.muted(), Font.BOLD, 11f);
        selectedLabel.setName("batch.selected");
        left.add(selectedLabel);
        left.add(compactButton("Select Filtered", "batch.select.filtered", false, () -> onSelectFiltered.run()));
        left.add(compactButton("Select Alerts", "batch.select.alerts", false, () -> onSelectAlerts.run()));
        left.add(compactButton("Clear", "batch.select.clear", false, () -> onClearSelection.run()));
        add(left, BorderLayout.WEST);

        JPanel actions = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 0), null);
        argField = style.field(14, "x y z / block / item count",
                "Target coordinates (goto), [count] block (mine), or item [count] (@swarm acquire)");
        argField.setName("batch.arg");
        actions.add(argField);
        actions.add(compactButton("Goto", "batch.btn.goto", false, this::dispatchGoto));
        actions.add(compactButton("Mine", "batch.btn.mine", false, this::dispatchMine));
        actions.add(compactButton("@swarm acquire", "batch.btn.acquire", false, this::dispatchAcquire));
        actions.add(compactButton("Cancel", "batch.btn.cancel", false, this::dispatchCancel));
        actions.add(compactButton("Stop", "batch.btn.stop", false, this::dispatchStop));
        despawnButton = compactButton("Despawn", "batch.btn.despawn", true, this::dispatchDespawn);
        actions.add(despawnButton);
        add(actions, BorderLayout.CENTER);

        statusLabel = style.label(" ", style.muted(), Font.PLAIN, 11f);
        statusLabel.setName("batch.status");
        add(statusLabel, BorderLayout.EAST);
    }

    private JButton compactButton(String text, String name, boolean danger, Runnable action) {
        JButton b = danger ? style.dangerButton(text) : style.button(text);
        b.setName(name);
        b.setFont(AppStyle.font(Font.PLAIN, 11f));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(danger ? style.danger() : style.line(), 1),
                BorderFactory.createEmptyBorder(2, 7, 2, 7)));
        b.addActionListener(e -> action.run());
        return b;
    }

    public void onSelectionActions(Runnable selectFiltered, Runnable selectAlerts, Runnable clear) {
        onSelectFiltered = selectFiltered == null ? () -> {} : selectFiltered;
        onSelectAlerts = selectAlerts == null ? () -> {} : selectAlerts;
        onClearSelection = clear == null ? () -> {} : clear;
    }

    public void update(SwarmView view, Collection<String> selected) {
        last = view == null ? SwarmView.EMPTY : view;
        List<String> next = selected == null ? List.of() : new ArrayList<>(selected);
        if (!next.equals(targets)) {
            targets = List.copyOf(next);
            disarmDespawn();
        }
        int n = targets.size();
        selectedLabel.setText(n + " selected");
        selectedLabel.setForeground(n > 0 ? style.accent() : style.muted());
    }

    private void dispatchGoto() {
        String arg = argField.getText().trim();
        if (targets.isEmpty()) {
            warn("Select at least one bot");
            return;
        }
        if (arg.isEmpty()) {
            warn("Enter goto coordinates");
            return;
        }
        ButtonModel form = tileForm("goto");
        int count = 0;
        for (String bot : targets) {
            if (form != null) {
                commands.press(form, Map.of("target", arg), bot, null);
            } else {
                String line = routedLine(bot, "goto " + arg);
                commands.line(line, bot, null);
            }
            count++;
        }
        ok("Sent goto to " + count + (count == 1 ? " bot" : " bots"));
    }

    private void dispatchMine() {
        String arg = argField.getText().trim();
        if (targets.isEmpty()) {
            warn("Select at least one bot");
            return;
        }
        Map<String, String> parsed = parseMineArg(arg);
        if (parsed.isEmpty()) {
            warn("Enter block (or count block)");
            return;
        }
        ButtonModel form = tileForm("mine");
        int count = 0;
        for (String bot : targets) {
            if (form != null) {
                commands.press(form, parsed, bot, null);
            } else {
                commands.line(routedLine(bot, "mine " + arg), bot, null);
            }
            count++;
        }
        ok("Sent mine to " + count + (count == 1 ? " bot" : " bots"));
    }

    private void dispatchAcquire() {
        String arg = argField.getText().trim();
        if (arg.isEmpty()) {
            warn("Enter item [count]");
            return;
        }
        String leader = coordinatorOrHost();
        if (leader == null) {
            warn("No swarm host/coordinator");
            return;
        }
        commands.line("@swarm acquire " + arg, leader, null);
        ok("Sent @swarm acquire " + arg + " to " + leader);
    }

    private void dispatchCancel() {
        if (targets.isEmpty()) {
            warn("Select at least one bot");
            return;
        }
        ButtonModel form = tileForm("cancel");
        int count = 0;
        for (String bot : targets) {
            SwarmView.BotState state = last.bot(bot);
            String cancelId = state == null ? null : TileText.cancelTarget(state.feed().executions());
            if (form != null && cancelId != null) {
                commands.press(form, Map.of("id", cancelId), bot, null);
            } else {
                commands.line(routedLine(bot, "cancel"), bot, null);
            }
            count++;
        }
        ok("Sent cancel to " + count + (count == 1 ? " bot" : " bots"));
    }

    private void dispatchStop() {
        if (targets.isEmpty()) {
            warn("Select at least one bot");
            return;
        }
        int count = 0;
        for (String bot : targets) {
            commands.line(routedLine(bot, "stop"), bot, null);
            count++;
        }
        ok("Sent stop to " + count + (count == 1 ? " bot" : " bots"));
    }

    private void dispatchDespawn() {
        if (targets.isEmpty()) {
            warn("Select at least one bot");
            return;
        }
        // Ostinato: destructive despawn requires a second click within 3 s; inline only, never takes focus.
        if (!armedDespawn) {
            armedDespawn = true;
            despawnButton.setText("Confirm despawn (" + targets.size() + ")");
            if (disarmTimer != null) {
                disarmTimer.stop();
            }
            disarmTimer = new Timer(3000, e -> disarmDespawn());
            disarmTimer.setRepeats(false);
            disarmTimer.start();
            warn("Click again within 3 s to despawn " + targets.size());
            return;
        }
        List<String> toDespawn = List.copyOf(targets);
        disarmDespawn();
        ButtonModel form = tileForm("despawn");
        for (String bot : toDespawn) {
            if (form != null) {
                commands.press(form, Map.of("name", bot), bot, null);
            } else {
                commands.line("swarm despawn " + bot, bot, null);
            }
        }
        ok("Sent despawn to " + toDespawn.size() + (toDespawn.size() == 1 ? " bot" : " bots"));
    }

    private void disarmDespawn() {
        armedDespawn = false;
        if (disarmTimer != null) {
            disarmTimer.stop();
            disarmTimer = null;
        }
        despawnButton.setText("Despawn");
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

    private String routedLine(String bot, String commandLine) {
        String target = TileRoute.target(bot, last.hostBot());
        return target == null ? commandLine : "swarm send " + target + " " + commandLine;
    }

    private String coordinatorOrHost() {
        if (last.coordinator() != null && !last.coordinator().self().isBlank()) {
            return last.coordinator().self();
        }
        if (last.hostBot() != null && !last.hostBot().isBlank()) {
            return last.hostBot();
        }
        return targets.isEmpty() ? null : targets.get(0);
    }

    private void ok(String msg) {
        statusLabel.setText(TileStatus.Level.OK.glyph + " " + msg);
        statusLabel.setForeground(style.ok());
    }

    private void warn(String msg) {
        statusLabel.setText(TileStatus.Level.WARN.glyph + " " + msg);
        statusLabel.setForeground(style.warn());
    }

    /** Parses {@code "[count] <block>"} into form field values; empty map when no block is given. */
    static Map<String, String> parseMineArg(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String[] parts = raw.trim().split("\\s+");
        Map<String, String> out = new LinkedHashMap<>();
        if (parts.length >= 2 && parts[0].matches("\\d+")) {
            out.put("count", parts[0]);
            out.put("block", parts[1].toLowerCase(Locale.ROOT));
        } else {
            out.put("block", parts[0].toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
