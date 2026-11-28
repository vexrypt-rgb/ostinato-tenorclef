package baritone.swarm.center.app;

import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregated Shared Stash & Gear panel across the whole swarm: sums carried items across all bots
 * plus container/chest contents observed by the coordinator, and highlights low-food, low-durability,
 * and full-inventory warnings.
 *
 * <p>Names: {@code stash:panel}, {@code stash:summary}, {@code stash:totals}, {@code stash:warnings}.
 */
public final class SharedStashPanel extends JPanel {

    private final JLabel summary;
    private final JTextArea totalsArea;
    private final JTextArea warningsArea;

    SharedStashPanel(AppStyle style) {
        super(new BorderLayout(8, 6));
        setName("stash:panel");
        setBackground(style.bg());
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        summary = style.label("Shared stash: 0 items", style.text(), Font.BOLD, 12.5f);
        summary.setName("stash:summary");
        add(summary, BorderLayout.NORTH);

        JPanel split = style.panel(new GridLayout(1, 2, 10, 0), null);
        totalsArea = area(style, "stash:totals");
        warningsArea = area(style, "stash:warnings");
        split.add(wrap(style, "Swarm Inventory + Observed Chest Stash", totalsArea));
        split.add(wrap(style, "Low Supply & Gear Durability Warnings", warningsArea));
        add(split, BorderLayout.CENTER);
    }

    private static JTextArea area(AppStyle style, String name) {
        JTextArea a = new JTextArea();
        a.setName(name);
        a.setEditable(false);
        a.setFocusable(false);
        a.setFont(AppStyle.font(Font.PLAIN, 11.5f));
        a.setBackground(style.surface());
        a.setForeground(style.text());
        a.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return a;
    }

    private static JPanel wrap(AppStyle style, String heading, JTextArea area) {
        JPanel p = style.panel(new BorderLayout(0, 4), null);
        p.add(style.label(heading, style.muted(), Font.BOLD, 11f), BorderLayout.NORTH);
        JScrollPane sc = new JScrollPane(area);
        sc.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        p.add(sc, BorderLayout.CENTER);
        return p;
    }

    /**
     * Pure aggregation of all items across bots' carried inventories and confirmed coordinator chest observations.
     * Used by both this panel and the Schematic Editor bill-of-materials comparison.
     */
    public static Map<String, Integer> aggregateStash(SwarmView view) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        if (view == null) {
            return totals;
        }
        for (SwarmView.BotState b : view.bots()) {
            BotFeed.Inventory inv = b.feed().inventory();
            List<BotFeed.Item> items = inv.counts().isEmpty() ? inv.top() : inv.counts();
            for (BotFeed.Item it : items) {
                if (it.id() != null && !it.id().isBlank() && it.count() > 0) {
                    totals.merge(it.id().toLowerCase(Locale.ROOT), it.count(), Integer::sum);
                }
            }
        }
        CoordinatorFeed coord = view.coordinator();
        if (coord != null) {
            for (CoordinatorFeed.Observation obs : coord.observations()) {
                if (!"chest".equals(obs.kind()) || "gone".equals(obs.state())) {
                    continue;
                }
                parseChestDetail(obs.detail(), totals);
            }
        }
        return totals;
    }

    /** Parses container detail strings like {@code cobblestone.128,oak_planks.64} or {@code diamond.4}. */
    static void parseChestDetail(String detail, Map<String, Integer> out) {
        if (detail == null || detail.isBlank()) {
            return;
        }
        for (String tok : detail.split("[,;]")) {
            String t = tok.trim();
            int dot = t.lastIndexOf('.');
            if (dot <= 0 || dot >= t.length() - 1) {
                continue;
            }
            String id = t.substring(0, dot).trim().toLowerCase(Locale.ROOT);
            if (id.startsWith("minecraft:")) {
                id = id.substring("minecraft:".length());
            }
            try {
                int count = Integer.parseInt(t.substring(dot + 1).trim());
                if (!id.isEmpty() && count > 0) {
                    out.merge(id, count, Integer::sum);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    void update(SwarmView view) {
        Map<String, Integer> totals = aggregateStash(view);
        int sumItems = totals.values().stream().mapToInt(Integer::intValue).sum();
        int totalFood = 0;
        int freeSlots = 0;
        List<String> warnings = new ArrayList<>();
        if (view != null) {
            for (SwarmView.BotState b : view.bots()) {
                BotFeed f = b.feed();
                BotFeed.Inventory inv = f.inventory();
                if (inv.food() > 0) totalFood += inv.food();
                if (inv.freeSlots() > 0) freeSlots += inv.freeSlots();
                if (inv.food() >= 0 && inv.food() <= 4) {
                    warnings.add(f.name() + ": low food (" + inv.food() + ")");
                }
                if (f.hunger() >= 0 && f.hunger() <= Alerts.HUNGRY) {
                    warnings.add(f.name() + ": hungry (" + f.hunger() + "/20)");
                }
                if (inv.lowestToolPct() >= 0 && inv.lowestToolPct() <= 20) {
                    String tool = inv.lowestTool().isEmpty() ? "tool" : inv.lowestTool();
                    warnings.add(f.name() + ": " + tool + " durability " + inv.lowestToolPct() + "%");
                }
                if (inv.freeSlots() == 0 && inv.totalSlots() > 0) {
                    warnings.add(f.name() + ": inventory full (0/" + inv.totalSlots() + " free)");
                }
            }
        }
        summary.setText("Shared stash: " + totals.size() + " item types, " + sumItems
                + " total · food " + totalFood + " · free slots " + freeSlots);

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        if (sorted.isEmpty()) {
            totalsArea.setText("No carried items or observed chest contents yet.");
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : sorted) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(String.format("%-28s %6d", e.getKey(), e.getValue()));
            }
            totalsArea.setText(sb.toString());
        }
        warningsArea.setText(warnings.isEmpty() ? "All bots adequately supplied." : String.join("\n", warnings));
    }
}
