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
import java.util.Locale;

/**
 * Swarm Link Monitor panel: displays per-bot SIGIL link state, peers seen, detected whisper prefix,
 * chat budget used vs learned server limit, max line length, backoff events, and dropped/retried frames.
 *
 * <p>Names: {@code linkmon:panel}, {@code linkmon:summary}, {@code linkmon:table}.
 */
final class LinkMonitorPanel extends JPanel {

    private final JLabel summary;
    private final LinkTableModel model = new LinkTableModel();

    LinkMonitorPanel(AppStyle style) {
        super(new BorderLayout(8, 6));
        setName("linkmon:panel");
        setBackground(style.bg());
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        summary = style.label("Swarm Link Monitor", style.text(), Font.BOLD, 12.5f);
        summary.setName("linkmon:summary");
        add(summary, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setName("linkmon:table");
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
        List<BotFeed> rows = new ArrayList<>();
        int upCount = 0;
        long totalDropped = 0;
        long totalRetried = 0;
        if (view != null) {
            for (SwarmView.BotState b : view.bots()) {
                BotFeed f = b.feed();
                rows.add(f);
                if (f.link().up()) upCount++;
                if (f.perf().dropped() > 0) totalDropped += f.perf().dropped();
                if (f.perf().retried() > 0) totalRetried += f.perf().retried();
            }
        }
        summary.setText("Swarm Link Monitor · " + upCount + "/" + rows.size() + " links up · dropped "
                + totalDropped + " · retried " + totalRetried);
        model.setRows(rows);
    }

    private static final class LinkTableModel extends AbstractTableModel {
        private static final String[] COLS = {
                "Bot", "Link", "Peers", "Whisper", "Chat Budget", "Learned Limit", "Dropped", "Retried", "Backoffs", "Error"
        };
        private List<BotFeed> rows = List.of();

        void setRows(List<BotFeed> next) {
            rows = List.copyOf(next);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            BotFeed f = rows.get(r);
            BotFeed.Link l = f.link();
            BotFeed.Perf p = f.perf();
            BotFeed.LinkBudget lb = f.linkBudget();
            return switch (c) {
                case 0 -> f.name();
                case 1 -> l.up() ? "● UP" : "✖ DOWN";
                case 2 -> l.peersSeen();
                case 3 -> lb.whisperCommand().isEmpty() ? "/msg" : lb.whisperCommand();
                case 4 -> p.chatUsed() < 0 ? "n/a" : p.chatUsed() + "/" + p.chatCap() + " /min";
                case 5 -> {
                    String chars = lb.maxLineChars() > 0 ? lb.maxLineChars() + "ch" : "256ch";
                    String rate = lb.rateMilliPerSec() > 0
                            ? String.format(Locale.ROOT, "%.2f/s", lb.rateMilliPerSec() / 1000.0) : "0.50/s";
                    yield chars + " @ " + rate;
                }
                case 6 -> p.dropped() < 0 ? 0L : p.dropped();
                case 7 -> p.retried() < 0 ? 0L : p.retried();
                case 8 -> lb.backoffEvents();
                case 9 -> l.failure();
                default -> "";
            };
        }
    }
}
