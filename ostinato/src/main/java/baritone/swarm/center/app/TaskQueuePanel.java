package baritone.swarm.center.app;

import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-bot Task Queue and Route Editor panel.
 * Lets the operator inspect each bot's queued tasks, reorder steps up/down,
 * load a saved route onto a chosen bot, and hand a task from one bot to another
 * over the authenticated command channel.
 *
 * <p>Names: {@code tasks:panel}, {@code tasks:bot}, {@code tasks:target}, {@code tasks:list},
 * {@code tasks:up}, {@code tasks:down}, {@code tasks:handoff}, {@code tasks:route}, {@code tasks:load},
 * {@code tasks:status}.
 */
final class TaskQueuePanel extends JPanel {

    private final Commands commands;
    private final JComboBox<String> sourceBot;
    private final JComboBox<String> handoffTarget;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> taskList;
    private final JTextField routeField;
    private final JLabel status;
    private SwarmView last = SwarmView.EMPTY;
    private List<BotFeed.TaskItem> currentTasks = List.of();
    private boolean syncingCombos;

    TaskQueuePanel(AppStyle style, Commands commands) {
        super(new BorderLayout(8, 6));
        this.commands = commands;
        setName("tasks:panel");
        setBackground(style.bg());
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel top = style.panel(new FlowLayout(FlowLayout.LEFT, 6, 2), style.surface());
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(style.line(), 1),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        top.add(style.label("Bot:", style.muted(), Font.PLAIN, 11.5f));
        sourceBot = new JComboBox<>();
        sourceBot.setName("tasks:bot");
        sourceBot.setFocusable(false);
        sourceBot.addActionListener(e -> {
            if (!syncingCombos) refreshList();
        });
        top.add(sourceBot);

        JButton up = style.button("Move Up");
        up.setName("tasks:up");
        up.addActionListener(e -> reorder(-1));
        top.add(up);

        JButton down = style.button("Move Down");
        down.setName("tasks:down");
        down.addActionListener(e -> reorder(1));
        top.add(down);

        top.add(style.label("Handoff to:", style.muted(), Font.PLAIN, 11.5f));
        handoffTarget = new JComboBox<>();
        handoffTarget.setName("tasks:target");
        handoffTarget.setFocusable(false);
        top.add(handoffTarget);

        JButton handoff = style.button("Handoff");
        handoff.setName("tasks:handoff");
        handoff.addActionListener(e -> handoffSelected());
        top.add(handoff);
        add(top, BorderLayout.NORTH);

        taskList = new JList<>(listModel);
        taskList.setName("tasks:list");
        taskList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        taskList.setBackground(style.surface());
        taskList.setForeground(style.text());
        taskList.setFont(AppStyle.font(Font.PLAIN, 12f));
        JScrollPane scroll = new JScrollPane(taskList);
        scroll.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        add(scroll, BorderLayout.CENTER);

        JPanel bottom = style.panel(new BorderLayout(8, 4), style.surface());
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(style.line(), 1),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        JPanel routeRow = style.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), null);
        routeRow.add(style.label("Load saved route:", style.muted(), Font.PLAIN, 11.5f));
        routeField = style.field(18, "route-name", "Saved task list name in tasks/<name>.json");
        routeField.setName("tasks:route");
        routeRow.add(routeField);
        JButton loadBtn = style.button("Load & Run");
        loadBtn.setName("tasks:load");
        loadBtn.addActionListener(e -> loadRoute());
        routeRow.add(loadBtn);
        bottom.add(routeRow, BorderLayout.WEST);

        status = style.label("Select a bot to view its task queue.", style.muted(), Font.PLAIN, 11f);
        status.setName("tasks:status");
        bottom.add(status, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
    }

    void update(SwarmView view) {
        last = view == null ? SwarmView.EMPTY : view;
        List<String> names = new ArrayList<>();
        for (SwarmView.BotState b : last.bots()) {
            names.add(b.name());
        }
        syncingCombos = true;
        try {
            syncCombo(sourceBot, names);
            syncCombo(handoffTarget, names);
        } finally {
            syncingCombos = false;
        }
        refreshList();
    }

    private static void syncCombo(JComboBox<String> combo, List<String> names) {
        Object prev = combo.getSelectedItem();
        boolean same = combo.getItemCount() == names.size();
        for (int i = 0; same && i < names.size(); i++) {
            if (!names.get(i).equals(combo.getItemAt(i))) same = false;
        }
        if (same) return;
        combo.removeAllItems();
        for (String n : names) combo.addItem(n);
        if (prev != null && names.contains(prev.toString())) {
            combo.setSelectedItem(prev);
        }
    }

    private void refreshList() {
        int prevIdx = taskList.getSelectedIndex();
        listModel.clear();
        String bot = (String) sourceBot.getSelectedItem();
        if (bot == null) {
            currentTasks = List.of();
            return;
        }
        SwarmView.BotState state = last.bot(bot);
        if (state == null) {
            currentTasks = List.of();
            return;
        }
        currentTasks = state.feed().tasks();
        if (currentTasks.isEmpty()) {
            listModel.addElement("(no queued tasks; active process: " + state.feed().process() + ")");
        } else {
            for (BotFeed.TaskItem t : currentTasks) {
                listModel.addElement(String.format("#%d [%s] %s — %s", t.index() + 1, t.state(), t.type(), t.label()));
            }
            if (prevIdx >= 0 && prevIdx < currentTasks.size()) {
                taskList.setSelectedIndex(prevIdx);
            }
        }
    }

    private void reorder(int delta) {
        String bot = (String) sourceBot.getSelectedItem();
        int idx = taskList.getSelectedIndex();
        if (commands == null || bot == null || idx < 0 || idx >= currentTasks.size()) {
            status.setText("Select a task step to reorder.");
            return;
        }
        int targetIdx = idx + delta;
        if (targetIdx < 0 || targetIdx >= currentTasks.size()) {
            return;
        }
        String line = routeLine(bot, "tasks move " + idx + " " + targetIdx);
        commands.line(line, bot, r -> status.setText(r.summary()));
    }

    private void handoffSelected() {
        String from = (String) sourceBot.getSelectedItem();
        String to = (String) handoffTarget.getSelectedItem();
        int idx = taskList.getSelectedIndex();
        if (commands == null || from == null || to == null || from.equals(to)) {
            status.setText("Choose a different target bot for handoff.");
            return;
        }
        if (idx < 0 || idx >= currentTasks.size()) {
            status.setText("Select a task step to hand off.");
            return;
        }
        BotFeed.TaskItem item = currentTasks.get(idx);
        String cmd = item.label().isBlank() ? item.type() : item.label();
        if (cmd.startsWith("#")) {
            cmd = cmd.substring(1).trim();
        }
        String sendLine = routeLine(to, cmd);
        String dropLine = routeLine(from, "tasks remove " + idx);
        commands.line(sendLine, to, r -> {
            status.setText("Handoff " + from + " -> " + to + ": " + r.reason());
            if (r.admitted()) {
                commands.line(dropLine, from, r2 -> {});
            }
        });
    }

    private void loadRoute() {
        String bot = (String) sourceBot.getSelectedItem();
        String route = routeField.getText().trim();
        if (commands == null || bot == null || route.isEmpty()) {
            status.setText("Enter a saved route name and choose a bot.");
            return;
        }
        String line = routeLine(bot, "tasks load " + route);
        commands.line(line, bot, r -> status.setText(r.summary()));
    }

    private String routeLine(String targetBot, String innerCommand) {
        String host = last.hostBot();
        if (host != null && host.equalsIgnoreCase(targetBot)) {
            return innerCommand;
        }
        return "swarm send " + targetBot + " " + innerCommand;
    }
}
