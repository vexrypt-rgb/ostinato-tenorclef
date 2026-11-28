package baritone.swarm.center.app;

import baritone.swarm.center.ShoppingCatalog;
import baritone.swarm.center.ShoppingListModel;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Ostinato: Command Center Shopping List panel (pure Swing, zero Minecraft classes).
 * Displays every item in Minecraft from {@link ShoppingCatalog}, lets the operator search, filter by category,
 * click items and pick amounts into a shared {@link ShoppingListModel}, and dispatch {@code #shop gather ...}
 * to the host bot, selected bot(s), a specific bot, or the whole swarm.
 *
 * <p>Named widgets: {@code shop:panel}, {@code shop:search}, {@code shop:category}, {@code shop:target},
 * {@code shop:close}, {@code shop:catalog}, {@code shop:add1}, {@code shop:add16}, {@code shop:add64},
 * {@code shop:amount}, {@code shop:addCustom}, {@code shop:cart}, {@code shop:summary}, {@code shop:clear},
 * {@code shop:preview}, {@code shop:stop}, {@code shop:gather}.
 */
public final class ShopPanel extends JPanel {

    public static final String TARGET_HOST = "Host";
    public static final String TARGET_SELECTED = "Selected";
    public static final String TARGET_ALL = "All bots";

    private final AppStyle style;
    private final Commands commands;
    private final ShoppingListModel cart;
    private final Supplier<List<String>> selectionSupplier;
    private final Runnable onClose;

    private final JTextField searchField;
    private final JComboBox<ShoppingCatalog.Category> categoryCombo;
    private final JComboBox<String> targetCombo;
    private final JLabel countLabel;
    private final CatalogTableModel tableModel;
    private final JTable catalogTable;
    private final JTextField customAmountField;
    private final JPanel cartListPanel;
    private final JLabel summaryLabel;
    private final JLabel previewLabel;
    private final JButton clearBtn;
    private final JButton gatherBtn;
    private final JButton stopBtn;

    private SwarmView lastView = SwarmView.EMPTY;
    private boolean updatingTargets;

    public ShopPanel(AppStyle style, Commands commands, ShoppingListModel cart,
                     Supplier<List<String>> selectionSupplier, Runnable onClose) {
        super(new BorderLayout(0, 6));
        this.style = style;
        this.commands = commands;
        this.cart = cart == null ? new ShoppingListModel() : cart;
        this.selectionSupplier = selectionSupplier == null ? List::of : selectionSupplier;
        this.onClose = onClose == null ? () -> {} : onClose;

        setName("shop:panel");
        setBackground(style.bg());
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        // --- Header toolbar ---
        JPanel toolbar = style.panel(new BorderLayout(8, 0), style.surface());
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(style.line(), 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JPanel tbLeft = style.panel(new FlowLayout(FlowLayout.LEFT, 8, 0), null);
        tbLeft.add(style.label("Shopping List", style.text(), Font.BOLD, 14f));
        countLabel = style.label(ShoppingCatalog.all().size() + " items", style.muted(), Font.PLAIN, 11f);
        countLabel.setName("shop:count");
        tbLeft.add(countLabel);

        searchField = style.field(22, "Search all items...", "Filter by item name or Minecraft ID");
        searchField.setName("shop:search");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshCatalog(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshCatalog(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshCatalog(); }
        });
        tbLeft.add(searchField);

        categoryCombo = new JComboBox<>(ShoppingCatalog.Category.values());
        categoryCombo.setName("shop:category");
        categoryCombo.setFont(AppStyle.font(Font.PLAIN, 12f));
        categoryCombo.setBackground(style.raised());
        categoryCombo.setForeground(style.text());
        categoryCombo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel l = new JLabel(value == null ? "All" : value.label);
            l.setOpaque(true);
            l.setFont(AppStyle.font(Font.PLAIN, 12f));
            l.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            l.setBackground(isSelected ? style.accent() : style.raised());
            l.setForeground(isSelected ? style.onAccent() : style.text());
            return l;
        });
        categoryCombo.addActionListener(e -> refreshCatalog());
        tbLeft.add(categoryCombo);
        toolbar.add(tbLeft, BorderLayout.WEST);

        JPanel tbRight = style.panel(new FlowLayout(FlowLayout.RIGHT, 6, 0), null);
        tbRight.add(style.label("Target:", style.muted(), Font.PLAIN, 11f));
        targetCombo = new JComboBox<>(new String[]{TARGET_HOST, TARGET_SELECTED, TARGET_ALL});
        targetCombo.setName("shop:target");
        targetCombo.setFont(AppStyle.font(Font.PLAIN, 12f));
        targetCombo.setBackground(style.raised());
        targetCombo.setForeground(style.text());
        targetCombo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel l = new JLabel(value == null ? TARGET_HOST : value);
            l.setOpaque(true);
            l.setFont(AppStyle.font(Font.PLAIN, 12f));
            l.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            l.setBackground(isSelected ? style.accent() : style.raised());
            l.setForeground(isSelected ? style.onAccent() : style.text());
            return l;
        });
        targetCombo.addActionListener(e -> {
            if (!updatingTargets) {
                refreshPreview();
            }
        });
        tbRight.add(targetCombo);

        JButton closeBtn = style.button("Close");
        closeBtn.setName("shop:close");
        closeBtn.addActionListener(e -> this.onClose.run());
        tbRight.add(closeBtn);
        toolbar.add(tbRight, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);

        // --- Left pane: All-items catalog ---
        tableModel = new CatalogTableModel();
        catalogTable = new JTable(tableModel);
        catalogTable.setName("shop:catalog");
        catalogTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        catalogTable.setRowHeight(24);
        catalogTable.setShowGrid(false);
        catalogTable.setIntercellSpacing(new Dimension(0, 1));
        catalogTable.setBackground(style.surface());
        catalogTable.setForeground(style.text());
        catalogTable.setSelectionBackground(style.accent());
        catalogTable.setSelectionForeground(style.onAccent());
        catalogTable.setFont(AppStyle.font(Font.PLAIN, 12f));
        catalogTable.getTableHeader().setBackground(style.raised());
        catalogTable.getTableHeader().setForeground(style.muted());
        catalogTable.getTableHeader().setFont(AppStyle.font(Font.BOLD, 11f));
        catalogTable.getTableHeader().setReorderingAllowed(false);

        DefaultTableCellRenderer baseRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                JLabel c = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
                c.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                if (!isSelected) {
                    c.setBackground((row % 2 == 0) ? style.surface() : style.bg());
                    if (column == 1 || column == 2) {
                        c.setForeground(style.muted());
                    } else if (column == 3 && value != null && !value.toString().isEmpty()) {
                        c.setForeground(style.accent());
                    } else {
                        c.setForeground(style.text());
                    }
                }
                c.setHorizontalAlignment(column == 3 ? SwingConstants.RIGHT : SwingConstants.LEFT);
                return c;
            }
        };
        for (int i = 0; i < catalogTable.getColumnCount(); i++) {
            catalogTable.getColumnModel().getColumn(i).setCellRenderer(baseRenderer);
        }
        catalogTable.getColumnModel().getColumn(0).setPreferredWidth(185);
        catalogTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        catalogTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        catalogTable.getColumnModel().getColumn(3).setPreferredWidth(64);

        catalogTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                    int delta = e.isShiftDown() ? 64 : (e.isControlDown() ? 16 : 1);
                    addSelectedFromCatalog(delta);
                } else if (e.getButton() == MouseEvent.BUTTON3) {
                    int row = catalogTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < tableModel.entries.size()) {
                        ShoppingCatalog.Entry entry = tableModel.entries.get(row);
                        cart.add(entry.id(), e.isShiftDown() ? -64 : -1);
                        refreshCart();
                    }
                }
            }
        });

        JScrollPane catalogScroll = new JScrollPane(catalogTable);
        catalogScroll.setColumnHeaderView(catalogTable.getTableHeader());
        catalogScroll.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        catalogScroll.getViewport().setBackground(style.surface());

        JPanel addBar = style.panel(new FlowLayout(FlowLayout.LEFT, 6, 4), style.surface());
        addBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, style.line()));
        addBar.add(style.label("Add selected:", style.muted(), Font.PLAIN, 11f));

        JButton add1 = style.button("+1");
        add1.setName("shop:add1");
        add1.addActionListener(e -> addSelectedFromCatalog(1));
        addBar.add(add1);

        JButton add16 = style.button("+16");
        add16.setName("shop:add16");
        add16.addActionListener(e -> addSelectedFromCatalog(16));
        addBar.add(add16);

        JButton add64 = style.button("+64");
        add64.setName("shop:add64");
        add64.addActionListener(e -> addSelectedFromCatalog(64));
        addBar.add(add64);

        customAmountField = style.field(5, "Qty", "Custom amount to add");
        customAmountField.setName("shop:amount");
        customAmountField.setText("32");
        addBar.add(customAmountField);

        JButton addCustom = style.button("Add");
        addCustom.setName("shop:addCustom");
        addCustom.addActionListener(e -> {
            int qty = parsePositiveInt(customAmountField.getText(), 1);
            addSelectedFromCatalog(qty);
        });
        addBar.add(addCustom);

        JPanel leftPane = style.panel(new BorderLayout(), style.surface());
        leftPane.add(catalogScroll, BorderLayout.CENTER);
        leftPane.add(addBar, BorderLayout.SOUTH);

        // --- Right pane: Shopping Cart ---
        JPanel rightPane = style.panel(new BorderLayout(), style.surface());
        rightPane.setBorder(BorderFactory.createLineBorder(style.line(), 1));

        JPanel cartHeader = style.panel(new BorderLayout(8, 0), style.raised());
        cartHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, style.line()),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        summaryLabel = style.label("Cart (0 items)", style.text(), Font.BOLD, 12f);
        summaryLabel.setName("shop:summary");
        cartHeader.add(summaryLabel, BorderLayout.WEST);

        clearBtn = style.dangerButton("Clear");
        clearBtn.setName("shop:clear");
        clearBtn.addActionListener(e -> {
            cart.clear();
            refreshCart();
        });
        cartHeader.add(clearBtn, BorderLayout.EAST);
        rightPane.add(cartHeader, BorderLayout.NORTH);

        cartListPanel = style.panel(null, style.surface());
        cartListPanel.setName("shop:cart");
        cartListPanel.setLayout(new BoxLayout(cartListPanel, BoxLayout.Y_AXIS));
        JScrollPane cartScroll = new JScrollPane(cartListPanel);
        cartScroll.setBorder(BorderFactory.createEmptyBorder());
        cartScroll.getViewport().setBackground(style.surface());
        cartScroll.getVerticalScrollBar().setUnitIncrement(16);
        rightPane.add(cartScroll, BorderLayout.CENTER);

        JPanel cartFooter = style.panel(new BorderLayout(0, 6), style.raised());
        cartFooter.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, style.line()),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        previewLabel = style.label("Add items from the catalog to build a shopping list.", style.muted(), Font.PLAIN, 11f);
        previewLabel.setName("shop:preview");
        cartFooter.add(previewLabel, BorderLayout.NORTH);

        JPanel actionRow = style.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0), null);
        stopBtn = style.dangerButton("Stop");
        stopBtn.setName("shop:stop");
        stopBtn.setToolTipText("Stop active shopping list gather on the selected target(s)");
        stopBtn.addActionListener(e -> dispatchStop());
        actionRow.add(stopBtn);

        gatherBtn = style.button("Gather All");
        gatherBtn.setName("shop:gather");
        style.flat(gatherBtn, style.accent(), style.onAccent(), style.accent());
        gatherBtn.setFont(AppStyle.font(Font.BOLD, 12f));
        gatherBtn.setToolTipText("Send #shop gather to the target bot(s) so TenorClef collects everything");
        gatherBtn.addActionListener(e -> dispatchGather());
        actionRow.add(gatherBtn);
        cartFooter.add(actionRow, BorderLayout.SOUTH);
        rightPane.add(cartFooter, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightPane);
        split.setResizeWeight(0.58);
        split.setDividerSize(6);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(style.bg());
        add(split, BorderLayout.CENTER);

        refreshCatalog();
        refreshCart();
    }

    public ShoppingListModel cart() {
        return cart;
    }

    public String searchQuery() {
        return searchField.getText();
    }

    public ShoppingCatalog.Category selectedCategory() {
        Object sel = categoryCombo.getSelectedItem();
        return sel instanceof ShoppingCatalog.Category c ? c : ShoppingCatalog.Category.ALL;
    }

    public String selectedTarget() {
        Object sel = targetCombo.getSelectedItem();
        return sel instanceof String s ? s : TARGET_HOST;
    }

    public void restoreState(String query, ShoppingCatalog.Category category, String target) {
        if (query != null) {
            searchField.setText(query);
        }
        if (category != null) {
            categoryCombo.setSelectedItem(category);
        }
        if (target != null) {
            for (int i = 0; i < targetCombo.getItemCount(); i++) {
                if (target.equals(targetCombo.getItemAt(i))) {
                    targetCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        refreshCatalog();
        refreshCart();
    }

    public void update(SwarmView view) {
        lastView = view == null ? SwarmView.EMPTY : view;
        Set<String> options = new LinkedHashSet<>();
        options.add(TARGET_HOST);
        options.add(TARGET_SELECTED);
        options.add(TARGET_ALL);
        for (SwarmView.BotState b : lastView.bots()) {
            if (b.name() != null && !b.name().isBlank()) {
                options.add(b.name());
            }
        }
        List<String> next = new ArrayList<>(options);
        if (!sameItems(targetCombo, next)) {
            String prev = selectedTarget();
            updatingTargets = true;
            try {
                DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(next.toArray(new String[0]));
                targetCombo.setModel(model);
                if (options.contains(prev)) {
                    targetCombo.setSelectedItem(prev);
                } else {
                    targetCombo.setSelectedItem(TARGET_HOST);
                }
            } finally {
                updatingTargets = false;
            }
        }
        refreshPreview();
    }

    private static boolean sameItems(JComboBox<String> combo, List<String> next) {
        if (combo.getItemCount() != next.size()) {
            return false;
        }
        for (int i = 0; i < next.size(); i++) {
            if (!next.get(i).equals(combo.getItemAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void refreshCatalog() {
        List<ShoppingCatalog.Entry> rows = ShoppingCatalog.filter(searchField.getText(), selectedCategory());
        tableModel.setEntries(rows);
        countLabel.setText(rows.size() + " / " + ShoppingCatalog.all().size() + " items");
    }

    private void addSelectedFromCatalog(int delta) {
        int[] rows = catalogTable.getSelectedRows();
        if (rows.length == 0 && tableModel.getRowCount() > 0) {
            rows = new int[]{0};
        }
        for (int viewRow : rows) {
            int modelRow = catalogTable.convertRowIndexToModel(viewRow);
            if (modelRow >= 0 && modelRow < tableModel.entries.size()) {
                cart.add(tableModel.entries.get(modelRow).id(), delta);
            }
        }
        refreshCart();
    }

    public void refreshCart() {
        List<ShoppingListModel.LineItem> items = cart.entries();
        summaryLabel.setText("Cart (" + cart.distinctCount() + " item" + (cart.distinctCount() == 1 ? "" : "s")
                + " · " + cart.totalCount() + " total)");
        clearBtn.setEnabled(!items.isEmpty());
        gatherBtn.setEnabled(!items.isEmpty());

        cartListPanel.removeAll();
        if (items.isEmpty()) {
            JPanel empty = style.panel(new FlowLayout(FlowLayout.LEFT, 10, 12), null);
            empty.add(style.label("Double-click items on the left or click +1 / +16 / +64.", style.muted(), Font.PLAIN, 12f));
            cartListPanel.add(empty);
        } else {
            for (ShoppingListModel.LineItem li : items) {
                cartListPanel.add(buildCartRow(li));
            }
            cartListPanel.add(Box.createVerticalGlue());
        }
        cartListPanel.revalidate();
        cartListPanel.repaint();
        tableModel.fireTableDataChanged();
        refreshPreview();
    }

    private JPanel buildCartRow(ShoppingListModel.LineItem li) {
        JPanel row = style.panel(new BorderLayout(6, 0), style.surface());
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, style.line()),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        JPanel labels = style.panel(new FlowLayout(FlowLayout.LEFT, 6, 2), null);
        labels.add(style.label(ShoppingCatalog.displayName(li.id()), style.text(), Font.BOLD, 12f));
        labels.add(style.label(li.id(), style.faint(), Font.PLAIN, 10.5f));
        row.add(labels, BorderLayout.CENTER);

        JPanel controls = style.panel(new FlowLayout(FlowLayout.RIGHT, 4, 0), null);

        JButton dec = compactButton("-", style.raised(), style.text(), style.line());
        dec.setName("shop:dec:" + li.id());
        dec.addActionListener(e -> {
            cart.add(li.id(), -1);
            refreshCart();
        });
        controls.add(dec);

        JTextField qtyField = style.field(4, "1", "Set exact quantity");
        qtyField.setName("shop:qty:" + li.id());
        qtyField.setHorizontalAlignment(SwingConstants.CENTER);
        qtyField.setText(String.valueOf(li.count()));
        Runnable commit = () -> {
            String txt = qtyField.getText().trim();
            try {
                int v = Integer.parseInt(txt);
                if (v != li.count()) {
                    cart.set(li.id(), v);
                    refreshCart();
                }
            } catch (NumberFormatException ignored) {
                qtyField.setText(String.valueOf(li.count()));
            }
        };
        qtyField.addActionListener(e -> commit.run());
        qtyField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commit.run();
            }
        });
        controls.add(qtyField);

        JButton inc = compactButton("+", style.raised(), style.text(), style.line());
        inc.setName("shop:inc:" + li.id());
        inc.addActionListener(e -> {
            cart.add(li.id(), 1);
            refreshCart();
        });
        controls.add(inc);

        JButton inc64 = compactButton("+64", style.raised(), style.accent(), style.line());
        inc64.setName("shop:inc64:" + li.id());
        inc64.addActionListener(e -> {
            cart.add(li.id(), 64);
            refreshCart();
        });
        controls.add(inc64);

        JButton rm = compactButton("x", style.raised(), style.danger(), style.danger());
        rm.setName("shop:rm:" + li.id());
        rm.addActionListener(e -> {
            cart.remove(li.id());
            refreshCart();
        });
        controls.add(rm);

        row.add(controls, BorderLayout.EAST);
        return row;
    }

    private JButton compactButton(String text, Color bg, Color fg, Color edge) {
        JButton b = new JButton(text);
        style.flat(b, bg, fg, edge);
        b.setFont(AppStyle.font(Font.BOLD, 11f));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(edge, 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        return b;
    }

    private void refreshPreview() {
        String cmd = cart.buildShopCommand();
        if (cmd.isEmpty()) {
            previewLabel.setText("Add items from the catalog to build a shopping list.");
            previewLabel.setForeground(style.muted());
            return;
        }
        List<String> lines = buildDispatchLines(cmd);
        String preview = lines.isEmpty() ? "#" + cmd : "#" + lines.get(0) + (lines.size() > 1 ? "  (+" + (lines.size() - 1) + " more)" : "");
        if (preview.length() > 92) {
            preview = preview.substring(0, 89) + "...";
        }
        previewLabel.setText(preview);
        previewLabel.setForeground(style.accent());
    }

    private List<String> buildDispatchLines(String baseCmd) {
        String mode = selectedTarget();
        String host = lastView.hostBot();
        List<String> lines = new ArrayList<>();
        if (TARGET_HOST.equals(mode)) {
            lines.add(baseCmd);
            return lines;
        }
        if (TARGET_ALL.equals(mode)) {
            for (SwarmView.BotState b : lastView.bots()) {
                String routed = TileRoute.target(b.name(), host);
                lines.add(routed == null ? baseCmd : "swarm send " + routed + " " + baseCmd);
            }
            if (lines.isEmpty()) {
                lines.add(baseCmd);
            }
            return lines;
        }
        if (TARGET_SELECTED.equals(mode)) {
            List<String> sel = selectionSupplier.get();
            if (sel == null || sel.isEmpty()) {
                lines.add(baseCmd);
            } else {
                for (String b : sel) {
                    String routed = TileRoute.target(b, host);
                    lines.add(routed == null ? baseCmd : "swarm send " + routed + " " + baseCmd);
                }
            }
            return lines;
        }
        String routed = TileRoute.target(mode, host);
        lines.add(routed == null ? baseCmd : "swarm send " + routed + " " + baseCmd);
        return lines;
    }

    private void dispatchGather() {
        if (commands == null || cart.isEmpty()) {
            return;
        }
        String baseCmd = cart.buildShopCommand();
        dispatch(baseCmd);
    }

    private void dispatchStop() {
        if (commands == null) {
            return;
        }
        dispatch("shop stop");
    }

    private void dispatch(String baseCmd) {
        String host = lastView.hostBot();
        String botContext = host == null ? "" : host;
        String mode = selectedTarget();
        if (!TARGET_HOST.equals(mode) && !TARGET_ALL.equals(mode) && !TARGET_SELECTED.equals(mode)) {
            botContext = mode;
        }
        for (String line : buildDispatchLines(baseCmd)) {
            commands.line(line, botContext, null);
        }
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private final class CatalogTableModel extends AbstractTableModel {
        private final String[] cols = {"Item", "ID", "Category", "In Cart"};
        private List<ShoppingCatalog.Entry> entries = List.of();

        void setEntries(List<ShoppingCatalog.Entry> next) {
            this.entries = next == null ? List.of() : next;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int column) { return cols[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ShoppingCatalog.Entry e = entries.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> e.name();
                case 1 -> e.id();
                case 2 -> e.category().label;
                case 3 -> {
                    int n = cart.countOf(e.id());
                    yield n > 0 ? "x" + n : "";
                }
                default -> "";
            };
        }
    }
}
