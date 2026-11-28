package baritone.swarm.center.app;

import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Live TenorClef Speedrun Splits panel comparing each phase's wall time and game ticks against personal bests.
 *
 * <p>Names: {@code splits:panel}, {@code splits:summary}, {@code splits:table}.
 */
final class SpeedrunSplitsPanel extends JPanel {

    private final JLabel summary;
    private final SplitsModel model = new SplitsModel();

    SpeedrunSplitsPanel(AppStyle style) {
        super(new BorderLayout(8, 6));
        setName("splits:panel");
        setBackground(style.bg());
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        summary = style.label("Speedrun Splits: no active speedrun telemetry", style.text(), Font.BOLD, 12.5f);
        summary.setName("splits:summary");
        add(summary, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setName("splits:table");
        table.setFillsViewportHeight(true);
        table.setFocusable(false);
        table.setRowHeight(24);
        table.setBackground(style.surface());
        table.setForeground(style.text());
        table.setGridColor(style.line());
        table.setFont(AppStyle.font(Font.PLAIN, 11.5f));
        table.getTableHeader().setBackground(style.raised());
        table.getTableHeader().setForeground(style.text());
        JScrollPane sc = new JScrollPane(table);
        sc.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        add(sc, BorderLayout.CENTER);
    }

    void update(SwarmView view) {
        List<Row> rows = new ArrayList<>();
        StringBuilder head = new StringBuilder();
        if (view != null) {
            for (SwarmView.BotState b : view.bots()) {
                BotFeed.Speedrun sr = b.feed().speedrun();
                if (sr.isEmpty()) {
                    continue;
                }
                if (head.length() > 0) head.append("  |  ");
                head.append(b.name()).append(": ").append(sr.phase())
                        .append(" ").append(fmtMs(sr.elapsedMs()))
                        .append(" (").append(sr.elapsedTicks()).append("t)");
                for (BotFeed.Speedrun.Split s : sr.splits()) {
                    rows.add(new Row(b.name(), s.name(), s.ms(), s.ticks(), s.pbMs()));
                }
            }
        }
        summary.setText(head.length() == 0 ? "Speedrun Splits: no active speedrun telemetry" : head.toString());
        model.setRows(rows);
    }

    static String fmtMs(long ms) {
        if (ms < 0) return "n/a";
        long min = ms / 60_000L;
        long sec = (ms / 1000L) % 60L;
        long tenths = (ms / 100L) % 10L;
        return String.format("%d:%02d.%d", min, sec, tenths);
    }

    static String fmtDelta(long ms, long pbMs) {
        if (pbMs < 0) return "--";
        long d = ms - pbMs;
        return (d >= 0 ? "+" : "-") + fmtMs(Math.abs(d));
    }

    private record Row(String bot, String split, long ms, long ticks, long pbMs) {}

    private static final class SplitsModel extends AbstractTableModel {
        private static final String[] COLS = {"Bot", "Phase Split", "Time", "Ticks", "Personal Best", "Delta"};
        private List<Row> rows = List.of();

        void setRows(List<Row> next) {
            rows = List.copyOf(next);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.bot();
                case 1 -> row.split();
                case 2 -> fmtMs(row.ms());
                case 3 -> row.ticks() + " t";
                case 4 -> fmtMs(row.pbMs());
                case 5 -> fmtDelta(row.ms(), row.pbMs());
                default -> "";
            };
        }
    }
}
