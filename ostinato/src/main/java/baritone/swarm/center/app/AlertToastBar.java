package baritone.swarm.center.app;

import baritone.swarm.center.TileStatus;
import baritone.swarm.center.feed.Alert;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Non-focus-stealing, non-modal in-window alert toast strip.
 * Surfaces newly raised death, kick, stuck, failure, and nearby-player alerts
 * with an inline dismiss button without ever opening a dialog, beeping, or taking focus.
 *
 * <p>Names: {@code toast:bar}, {@code toast:text}, {@code toast:dismiss}.
 */
final class AlertToastBar extends JPanel {

    private final AppStyle style;
    private final JLabel textLabel;
    private final Set<String> seenKeys = new HashSet<>();
    private final Set<String> dismissedKeys = new HashSet<>();
    private final List<String> activeKeys = new ArrayList<>();

    AlertToastBar(AppStyle style) {
        super(new BorderLayout(8, 0));
        this.style = style;
        setName("toast:bar");
        setBackground(style.raised());
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, style.warn()),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));

        textLabel = style.label(" ", style.text(), Font.BOLD, 11.5f);
        textLabel.setName("toast:text");
        add(textLabel, BorderLayout.CENTER);

        JPanel right = style.panel(new FlowLayout(FlowLayout.RIGHT, 4, 0), null);
        JButton dismiss = style.button("Dismiss");
        dismiss.setName("toast:dismiss");
        dismiss.addActionListener(e -> dismissAll());
        right.add(dismiss);
        add(right, BorderLayout.EAST);
        setVisible(false);
    }

    void update(List<Alert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            seenKeys.clear();
            dismissedKeys.clear();
            activeKeys.clear();
            setVisible(false);
            return;
        }
        Set<String> currentKeys = new HashSet<>();
        List<Alert> visible = new ArrayList<>();
        TileStatus.Level worst = TileStatus.Level.OK;
        for (Alert a : alerts) {
            String key = a.bot() + "|" + a.code().name() + "|" + a.text();
            currentKeys.add(key);
            seenKeys.add(key);
            if (!dismissedKeys.contains(key)) {
                visible.add(a);
                if (a.level().ordinal() > worst.ordinal()) {
                    worst = a.level();
                }
            }
        }
        dismissedKeys.retainAll(currentKeys);
        seenKeys.retainAll(currentKeys);
        activeKeys.clear();
        for (Alert a : visible) {
            activeKeys.add(a.bot() + "|" + a.code().name() + "|" + a.text());
        }
        if (visible.isEmpty()) {
            setVisible(false);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(3, visible.size());
        for (int i = 0; i < shown; i++) {
            Alert a = visible.get(i);
            if (i > 0) sb.append("   ·   ");
            sb.append(a.bot()).append(": ").append(a.badge());
        }
        if (visible.size() > shown) {
            sb.append("   (+").append(visible.size() - shown).append(" more)");
        }
        textLabel.setText(sb.toString());
        textLabel.setForeground(style.level(worst));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, style.level(worst)),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        setVisible(true);
    }

    void dismissAll() {
        dismissedKeys.addAll(activeKeys);
        activeKeys.clear();
        setVisible(false);
    }
}
