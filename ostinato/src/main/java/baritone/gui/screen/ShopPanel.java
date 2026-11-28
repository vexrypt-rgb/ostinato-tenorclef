package baritone.gui.screen;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.shop.IShoppingRunner;
import baritone.api.shop.ShoppingRunners;
import baritone.gui.model.TextInput;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Icons;
import baritone.gui.render.Skin;
import baritone.swarm.center.ShoppingCatalog;
import baritone.swarm.center.ShoppingListModel;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: in-game Shopping List desk inside {@link OstinatoScreen}.
 * Shows every item registered in the game ({@link BuiltInRegistries#ITEM}) with live Minecraft icons,
 * instant search and category filters, click-to-add quantities, editable cart amounts, target-bot picker,
 * and one-click {@code #shop gather ...} dispatch to TenorClef.
 */
final class ShopPanel {

    private static final float TILE = 24f;
    private static final float GAP = 3f;
    private static final float CART_ROW_H = 21f;

    /** Preserved across menu close/reopen during the client session until cleared. */
    private static final ShoppingListModel CART = new ShoppingListModel();

    private record ItemRecord(String id, String name, ShoppingCatalog.Category category, ItemStack stack, String searchKey) {}

    private final OstinatoScreen screen;
    private final List<ItemRecord> allItems = new ArrayList<>();
    private final Map<String, ItemRecord> byId = new LinkedHashMap<>();
    private List<ItemRecord> filtered = List.of();
    private final TextInput search = new TextInput(48);
    private final TextInput amountEdit = new TextInput(8);
    private final List<String> targets = new ArrayList<>();

    private ShoppingCatalog.Category category = ShoppingCatalog.Category.ALL;
    private boolean searchFocused = false;
    private String editingItemId = null;
    private int targetIdx = 0;
    private float gridScroll = 0f;
    private float cartScroll = 0f;

    private float px0, px1, top, bottom;

    ShopPanel(OstinatoScreen screen) {
        this.screen = screen;
        reloadItems();
    }

    int cartItemTypes() {
        return CART.distinctCount();
    }

    boolean editing() {
        return searchFocused || editingItemId != null;
    }

    void reloadItems() {
        allItems.clear();
        byId.clear();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null || item == Items.AIR) {
                continue;
            }
            Identifier key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) {
                continue;
            }
            String id = "minecraft".equals(key.getNamespace()) ? key.getPath() : key.toString();
            ItemStack stack = new ItemStack(item);
            String name = stack.getHoverName().getString();
            if (name == null || name.isBlank()) {
                name = ShoppingCatalog.displayName(id);
            }
            ShoppingCatalog.Category cat = ShoppingCatalog.classifyId(id);
            ItemRecord rec = new ItemRecord(id, name, cat, stack,
                    (id + " " + name).toLowerCase(Locale.ROOT));
            allItems.add(rec);
            byId.put(id, rec);
        }
        allItems.sort(Comparator.comparing(ItemRecord::name, String.CASE_INSENSITIVE_ORDER));
        discoverTargets();
        rebuildFilter();
    }

    private void discoverTargets() {
        Set<String> set = new LinkedHashSet<>();
        set.add("self");
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.level != null) {
            String self = mc.player == null ? "" : mc.player.getName().getString();
            for (Player p : mc.level.players()) {
                String n = p.getName().getString();
                if (n != null && !n.isBlank() && !n.equalsIgnoreCase(self)) {
                    set.add(n);
                }
            }
        }
        targets.clear();
        targets.addAll(set);
        if (targetIdx >= targets.size()) {
            targetIdx = 0;
        }
    }

    private void rebuildFilter() {
        String q = search.get().trim().toLowerCase(Locale.ROOT);
        String[] terms = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<ItemRecord> out = new ArrayList<>();
        for (ItemRecord r : allItems) {
            if (category != ShoppingCatalog.Category.ALL && r.category() != category) {
                continue;
            }
            if (terms.length > 0) {
                boolean ok = true;
                for (String t : terms) {
                    if (!r.searchKey().contains(t)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
            }
            out.add(r);
        }
        filtered = out;
        gridScroll = 0f;
    }

    private String currentTarget() {
        return targets.isEmpty() ? "self" : targets.get(Math.floorMod(targetIdx, targets.size()));
    }

    void layout(float px0, float px1, float top, float bottom) {
        this.px0 = px0;
        this.px1 = px1;
        this.top = top;
        this.bottom = bottom;
    }

    private float splitX() {
        return px0 + Math.max(210f, (px1 - px0) * 0.59f);
    }

    private float searchBoxX0() {
        return px0 + 82f;
    }

    private float searchBoxX1() {
        return Math.min(px1, splitX() - 6f);
    }

    private float bodyY0() {
        return top + 40f;
    }

    private float bodyY1() {
        return bottom - 6f;
    }

    void draw(GuiGraphicsExtractor ms, int mx, int my, int accent, long now) {
        // Row 1: title + inline search + target cycler
        GuiDraw.text(ms, "Shopping List", px0, top + 8, 1f, Skin.text(), true, true);
        float sx0 = searchBoxX0(), sx1 = searchBoxX1(), sy0 = top + 5, sy1 = top + 19;
        boolean sHov = OstinatoScreen.in(mx, my, sx0, sy0, sx1, sy1);
        GuiDraw.roundBorder(ms, sx0, sy0, sx1, sy1, 3,
                searchFocused ? accent : (sHov ? 0xFF3A4458 : Theme.FIELD_BORDER), Theme.FIELD);
        GuiDraw.icon(ms, Icons.SEARCH, sx0 + 5, sy0 + 3.5f, searchFocused ? accent : Skin.muted(), 0.85f);
        float stx = sx0 + 15;
        if (search.isEmpty() && !searchFocused) {
            GuiDraw.text(ms, GuiDraw.trim("Search " + allItems.size() + " items...", sx1 - stx - 5, 0.9f),
                    stx, sy0 + 3.5f, 0.9f, Skin.dim(), false, false);
        } else {
            screen.drawInput(ms, search, stx, sy0 + 3.5f, sx1 - stx - 5, searchFocused, accent, now);
        }

        // Right header: count badge + target bot cycler
        String tgtLabel = "Target: " + currentTarget();
        float tw = GuiDraw.width(tgtLabel) + 12;
        float tx0 = px1 - tw;
        boolean tHov = OstinatoScreen.in(mx, my, tx0, sy0, px1, sy1);
        GuiDraw.roundBorder(ms, tx0, sy0, px1, sy1, 2.5f, tHov ? accent : 0xFF2E3647, 0xCC111622);
        GuiDraw.text(ms, tgtLabel, tx0 + 6, sy0 + 3.5f, accent, false);

        String shownText = filtered.size() + " items";
        float sw = GuiDraw.width(shownText, 0.75f, false);
        if (tx0 - sw - 8 > sx1 + 4) {
            GuiDraw.text(ms, shownText, tx0 - sw - 8, sy0 + 4.5f, 0.75f, Skin.muted(), false, false);
        }

        // Row 2: category filter chips
        float chipY0 = top + 23f, chipY1 = top + 36f;
        float cx = px0;
        for (ShoppingCatalog.Category c : ShoppingCatalog.Category.values()) {
            float cw = GuiDraw.width(c.label, 0.8f, false) + 8;
            if (cx + cw > px1) break;
            boolean on = c == category;
            boolean hov = OstinatoScreen.in(mx, my, cx, chipY0, cx + cw, chipY1);
            GuiDraw.round(ms, cx, chipY0, cx + cw, chipY1, 2.5f,
                    on ? GuiDraw.alphaOf(accent, 0x30) : (hov ? 0x1AFFFFFF : 0x0FFFFFFF));
            GuiDraw.text(ms, c.label, cx + 4, chipY0 + 3f, 0.8f,
                    on ? accent : (hov ? Skin.text() : Skin.muted()), false, on);
            cx += cw + 3;
        }

        float split = splitX();
        float by0 = bodyY0();
        float by1 = bodyY1();

        // Left pane: scrollable visual item grid
        float gx0 = px0, gx1 = split - 5;
        GuiDraw.roundBorder(ms, gx0, by0, gx1, by1, 3, 0xFF232A38, 0xAA0C1018);

        float innerW = (gx1 - gx0) - 14f;
        int cols = Math.max(1, (int) Math.floor((innerW + GAP) / (TILE + GAP)));
        int totalRows = (filtered.size() + cols - 1) / cols;
        float contentH = totalRows * (TILE + GAP) + 6f;
        float viewH = by1 - by0 - 4f;
        float maxGridScroll = Math.max(0f, contentH - viewH);
        gridScroll = Math.max(0f, Math.min(maxGridScroll, gridScroll));

        ItemRecord hoveredItem = null;
        GuiDraw.scissor(gx0 + 1, by0 + 1, gx1 - 1, by1 - 1);
        boolean inGrid = OstinatoScreen.in(mx, my, gx0 + 2, by0 + 2, gx1 - 6, by1 - 2);
        int firstRow = Math.max(0, (int) Math.floor((gridScroll - 4f) / (TILE + GAP)));
        int lastRow = Math.min(totalRows - 1, (int) Math.ceil((gridScroll + viewH) / (TILE + GAP)));
        for (int r = firstRow; r <= lastRow; r++) {
            float ty = by0 + 4f + r * (TILE + GAP) - gridScroll;
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx >= filtered.size()) break;
                ItemRecord rec = filtered.get(idx);
                float tx = gx0 + 5f + c * (TILE + GAP);
                int qty = CART.countOf(rec.id());
                boolean hov = inGrid && OstinatoScreen.in(mx, my, tx, ty, tx + TILE, ty + TILE);
                if (hov) hoveredItem = rec;
                int border = qty > 0 ? accent : (hov ? 0xFF4A5670 : 0xFF1F2633);
                int fill = qty > 0 ? GuiDraw.alphaOf(accent, 0x26) : (hov ? 0x22FFFFFF : 0x10FFFFFF);
                GuiDraw.roundBorder(ms, tx, ty, tx + TILE, ty + TILE, 2.5f, border, fill);
                GuiDraw.item(rec.stack(), tx + 4f, ty + 4f, 16f);
                if (qty > 0) {
                    String badge = qty >= 1000 ? (qty / 1000) + "k" : String.valueOf(qty);
                    float bw = GuiDraw.width(badge, 0.55f, true) + 4f;
                    GuiDraw.round(ms, tx + TILE - bw - 1f, ty + TILE - 8f, tx + TILE - 1f, ty + TILE - 1f, 1.5f, 0xDD0B0E14);
                    GuiDraw.text(ms, badge, tx + TILE - bw + 1f, ty + TILE - 6.5f, 0.55f, accent, false, true);
                }
            }
        }
        if (filtered.isEmpty()) {
            String msg = "No items match \"" + search.get().trim() + "\"";
            GuiDraw.text(ms, msg, (gx0 + gx1) / 2f - GuiDraw.width(msg) / 2f, by0 + 28f, Skin.dim(), false);
        }
        GuiDraw.endScissor();
        if (maxGridScroll > 0f) {
            float trkH = by1 - by0 - 8f;
            float thumbH = Math.max(14f, trkH * (viewH / contentH));
            float thumbY = by0 + 4f + (trkH - thumbH) * (gridScroll / maxGridScroll);
            GuiDraw.round(ms, gx1 - 4f, thumbY, gx1 - 2f, thumbY + thumbH, 1f, 0x66FFFFFF);
        }

        // Right pane: Shopping Cart
        float cx0 = split + 1, cx1 = px1;
        GuiDraw.roundBorder(ms, cx0, by0, cx1, by1, 3, 0xFF232A38, 0xAA0C1018);

        List<ShoppingListModel.LineItem> cartItems = CART.entries();
        String cartTitle = "Cart (" + CART.distinctCount() + " · " + CART.totalCount() + ")";
        GuiDraw.text(ms, cartTitle, cx0 + 7f, by0 + 6f, 0.9f, Skin.text(), true, true);

        float clrW = GuiDraw.width("Clear", 0.8f, false) + 10f;
        float clrX0 = cx1 - 6f - clrW;
        boolean clrHov = !cartItems.isEmpty() && OstinatoScreen.in(mx, my, clrX0, by0 + 4f, cx1 - 6f, by0 + 16f);
        GuiDraw.roundBorder(ms, clrX0, by0 + 4f, cx1 - 6f, by0 + 16f, 2f,
                clrHov ? Theme.DANGER : 0xFF2B3344, clrHov ? 0x30E5534B : 0x10FFFFFF);
        GuiDraw.text(ms, "Clear", clrX0 + 5f, by0 + 6.5f, 0.8f,
                cartItems.isEmpty() ? Skin.dim() : (clrHov ? Theme.DANGER : Skin.muted()), false, false);

        float listY0 = by0 + 20f;
        float footY0 = by1 - 30f;
        float cartViewH = Math.max(10f, footY0 - listY0 - 2f);
        float cartContentH = cartItems.size() * CART_ROW_H + 4f;
        float maxCartScroll = Math.max(0f, cartContentH - cartViewH);
        cartScroll = Math.max(0f, Math.min(maxCartScroll, cartScroll));

        GuiDraw.scissor(cx0 + 1, listY0, cx1 - 1, footY0 - 2f);
        if (cartItems.isEmpty()) {
            GuiDraw.text(ms, "Click items on the left", cx0 + 8f, listY0 + 14f, 0.85f, Skin.muted(), false, false);
            GuiDraw.text(ms, "L-click: +1   Shift: +64", cx0 + 8f, listY0 + 26f, 0.75f, Skin.dim(), false, false);
            GuiDraw.text(ms, "Ctrl: +16   R-click: -1", cx0 + 8f, listY0 + 36f, 0.75f, Skin.dim(), false, false);
        } else {
            for (int i = 0; i < cartItems.size(); i++) {
                float ry = listY0 + 2f + i * CART_ROW_H - cartScroll;
                if (ry + CART_ROW_H < listY0 || ry > footY0) continue;
                ShoppingListModel.LineItem li = cartItems.get(i);
                ItemRecord rec = byId.get(li.id());
                float rx0 = cx0 + 4f, rx1 = cx1 - 6f;
                GuiDraw.round(ms, rx0, ry, rx1, ry + CART_ROW_H - 2f, 2.5f, 0x14FFFFFF);
                if (rec != null) {
                    GuiDraw.item(rec.stack(), rx0 + 3f, ry + 3.5f, 12f);
                }

                // Right-aligned controls: [-] [qty] [+] [+64] [x]
                float delX0 = rx1 - 13f;
                float p64X0 = delX0 - 21f;
                float plusX0 = p64X0 - 13f;
                float qtyX0 = plusX0 - 28f;
                float minusX0 = qtyX0 - 13f;

                float nameMax = Math.max(24f, minusX0 - (rx0 + 18f) - 3f);
                String disp = rec != null ? rec.name() : ShoppingCatalog.displayName(li.id());
                GuiDraw.text(ms, GuiDraw.trim(disp, nameMax, 0.8f), rx0 + 18f, ry + 6f, 0.8f, Skin.text(), false, false);

                miniBtn(ms, minusX0, ry + 3f, minusX0 + 11f, ry + 16f, "-", mx, my, accent, false);

                boolean editingThis = li.id().equals(editingItemId);
                GuiDraw.roundBorder(ms, qtyX0, ry + 3f, plusX0 - 2f, ry + 16f, 2f,
                        editingThis ? accent : Theme.FIELD_BORDER, Theme.FIELD);
                if (editingThis) {
                    screen.drawInput(ms, amountEdit, qtyX0 + 3f, ry + 5.5f, (plusX0 - 2f) - qtyX0 - 6f, true, accent, now);
                } else {
                    String qStr = String.valueOf(li.count());
                    float qw = GuiDraw.width(qStr, 0.8f, false);
                    GuiDraw.text(ms, qStr, qtyX0 + ((plusX0 - 2f - qtyX0) - qw) * 0.5f, ry + 6f, 0.8f, accent, false, true);
                }

                miniBtn(ms, plusX0, ry + 3f, plusX0 + 11f, ry + 16f, "+", mx, my, accent, false);
                miniBtn(ms, p64X0, ry + 3f, p64X0 + 19f, ry + 16f, "+64", mx, my, accent, false);
                miniBtn(ms, delX0, ry + 3f, delX0 + 11f, ry + 16f, "x", mx, my, Theme.DANGER, true);
            }
        }
        GuiDraw.endScissor();

        // Cart footer: Stop + Gather All
        GuiDraw.rect(ms, cx0 + 4f, footY0 - 1f, cx1 - 4f, footY0, 0x16FFFFFF);
        float stopW = 42f;
        float stopX0 = cx0 + 6f;
        boolean stopHov = OstinatoScreen.in(mx, my, stopX0, footY0 + 5f, stopX0 + stopW, by1 - 5f);
        GuiDraw.roundBorder(ms, stopX0, footY0 + 5f, stopX0 + stopW, by1 - 5f, 2.5f,
                stopHov ? Theme.DANGER : 0xFF3A2830, stopHov ? 0x35E5534B : 0x1CE5534B);
        float stw = GuiDraw.width("Stop", 0.85f, false);
        GuiDraw.text(ms, "Stop", stopX0 + (stopW - stw) * 0.5f, footY0 + 9.5f, 0.85f,
                stopHov ? 0xFFFFFFFF : Theme.DANGER, false, false);

        float goX0 = stopX0 + stopW + 5f;
        float goX1 = cx1 - 6f;
        boolean canGo = !cartItems.isEmpty();
        boolean goHov = canGo && OstinatoScreen.in(mx, my, goX0, footY0 + 5f, goX1, by1 - 5f);
        GuiDraw.roundBorder(ms, goX0, footY0 + 5f, goX1, by1 - 5f, 2.5f,
                canGo ? accent : Theme.DIM,
                canGo ? GuiDraw.alphaOf(accent, goHov ? 0x55 : 0x33) : 0x14FFFFFF);
        String goLbl = canGo ? "Gather All (" + CART.distinctCount() + ")" : "Gather All";
        float glw = GuiDraw.width(goLbl, 0.9f, true);
        GuiDraw.text(ms, goLbl, goX0 + ((goX1 - goX0) - glw) * 0.5f, footY0 + 9f, 0.9f,
                canGo ? 0xFFFFFFFF : Skin.dim(), false, true);

        // Hover tooltip on item grid (drawn last, on top of panes)
        if (hoveredItem != null) {
            drawItemTooltip(ms, hoveredItem, mx, my, accent);
        }
    }

    private static void miniBtn(GuiGraphicsExtractor ms, float x0, float y0, float x1, float y1,
                                String lbl, int mx, int my, int col, boolean danger) {
        boolean hov = OstinatoScreen.in(mx, my, x0, y0, x1, y1);
        GuiDraw.round(ms, x0, y0, x1, y1, 2f,
                hov ? (danger ? 0x40E5534B : GuiDraw.alphaOf(col, 0x35)) : 0x16FFFFFF);
        float lw = GuiDraw.width(lbl, 0.7f, false);
        GuiDraw.text(ms, lbl, x0 + ((x1 - x0) - lw) * 0.5f, y0 + 3.5f, 0.7f,
                hov ? (danger ? Theme.DANGER : col) : Skin.text(), false, false);
    }

    private void drawItemTooltip(GuiGraphicsExtractor ms, ItemRecord rec, int mx, int my, int accent) {
        IShoppingRunner runner = ShoppingRunners.get();
        boolean gatherable = runner == null || runner.isGatherable(rec.id());
        int qty = CART.countOf(rec.id());
        String line1 = rec.name() + (qty > 0 ? "  (in cart: " + qty + ")" : "");
        String line2 = "minecraft:" + rec.id() + "  ·  " + rec.category().label
                + (gatherable ? "  ·  Gatherable" : "  ·  Uncatalogued");
        String line3 = "L-click +1 · Ctrl +16 · Shift +64 · R-click -1";

        float w = Math.max(GuiDraw.width(line1, 0.9f, true),
                Math.max(GuiDraw.width(line2, 0.7f, false), GuiDraw.width(line3, 0.65f, false))) + 14f;
        float h = 36f;
        float tx = Math.min(px1 - w - 2f, mx + 10f);
        float ty = my + 12f;
        if (ty + h > bottom) {
            ty = my - h - 6f;
        }
        GuiDraw.shadow(ms, tx, ty, tx + w, ty + h, 3f, 4, 0x90);
        GuiDraw.roundBorder(ms, tx, ty, tx + w, ty + h, 3f, accent, 0xF4121620);
        GuiDraw.text(ms, line1, tx + 7f, ty + 5f, 0.9f, Skin.text(), true, true);
        GuiDraw.text(ms, line2, tx + 7f, ty + 16f, 0.7f, gatherable ? accent : Theme.AMBER, false, false);
        GuiDraw.text(ms, line3, tx + 7f, ty + 25f, 0.65f, Skin.muted(), false, false);
    }

    boolean mouseClicked(double mx, double my, int button) {
        commitAmountEdit();

        // Inline search focus
        float sx0 = searchBoxX0(), sx1 = searchBoxX1(), sy0 = top + 5, sy1 = top + 19;
        if (button == 0 && OstinatoScreen.in(mx, my, sx0, sy0, sx1, sy1)) {
            searchFocused = true;
            return true;
        }
        searchFocused = false;

        // Target bot cycler
        String tgtLabel = "Target: " + currentTarget();
        float tw = GuiDraw.width(tgtLabel) + 12;
        if (button == 0 && OstinatoScreen.in(mx, my, px1 - tw, sy0, px1, sy1)) {
            discoverTargets();
            if (targets.size() > 1) {
                targetIdx = Math.floorMod(targetIdx + 1, targets.size());
            }
            return true;
        }

        // Category chips
        float chipY0 = top + 23f, chipY1 = top + 36f;
        float cx = px0;
        for (ShoppingCatalog.Category c : ShoppingCatalog.Category.values()) {
            float cw = GuiDraw.width(c.label, 0.8f, false) + 8;
            if (cx + cw > px1) break;
            if (button == 0 && OstinatoScreen.in(mx, my, cx, chipY0, cx + cw, chipY1)) {
                category = c;
                rebuildFilter();
                return true;
            }
            cx += cw + 3;
        }

        float split = splitX();
        float by0 = bodyY0(), by1 = bodyY1();
        float gx0 = px0, gx1 = split - 5;

        // Left item grid click
        if (OstinatoScreen.in(mx, my, gx0 + 2, by0 + 2, gx1 - 6, by1 - 2)) {
            float innerW = (gx1 - gx0) - 14f;
            int cols = Math.max(1, (int) Math.floor((innerW + GAP) / (TILE + GAP)));
            int col = (int) Math.floor((mx - (gx0 + 5f)) / (TILE + GAP));
            int row = (int) Math.floor((my - (by0 + 4f) + gridScroll) / (TILE + GAP));
            if (col >= 0 && col < cols && row >= 0) {
                int idx = row * cols + col;
                if (idx >= 0 && idx < filtered.size()) {
                    ItemRecord rec = filtered.get(idx);
                    if (button == 0) {
                        int delta = Keys.shift() ? 64 : (Keys.ctrl() ? 16 : 1);
                        int next = CART.add(rec.id(), delta);
                        screen.flash("Added " + rec.name() + " (x" + next + ")", Theme.accent());
                        return true;
                    } else if (button == 1) {
                        if (Keys.shift()) {
                            CART.remove(rec.id());
                            screen.flash("Removed " + rec.name(), Theme.MUTED);
                        } else {
                            int next = CART.add(rec.id(), -1);
                            screen.flash(next > 0 ? rec.name() + " (x" + next + ")" : "Removed " + rec.name(), Theme.MUTED);
                        }
                        return true;
                    }
                }
            }
        }

        // Right cart pane clicks
        float cx0 = split + 1, cx1 = px1;
        List<ShoppingListModel.LineItem> cartItems = CART.entries();
        float clrW = GuiDraw.width("Clear", 0.8f, false) + 10f;
        float clrX0 = cx1 - 6f - clrW;
        if (button == 0 && !cartItems.isEmpty() && OstinatoScreen.in(mx, my, clrX0, by0 + 4f, cx1 - 6f, by0 + 16f)) {
            CART.clear();
            cartScroll = 0f;
            screen.flash("Cleared shopping list", Theme.MUTED);
            return true;
        }

        float listY0 = by0 + 20f;
        float footY0 = by1 - 30f;
        if (button == 0 && OstinatoScreen.in(mx, my, cx0 + 2, listY0, cx1 - 2, footY0 - 2f)) {
            for (int i = 0; i < cartItems.size(); i++) {
                float ry = listY0 + 2f + i * CART_ROW_H - cartScroll;
                if (my < ry || my > ry + CART_ROW_H - 2f) continue;
                ShoppingListModel.LineItem li = cartItems.get(i);
                float rx1 = cx1 - 6f;
                float delX0 = rx1 - 13f;
                float p64X0 = delX0 - 21f;
                float plusX0 = p64X0 - 13f;
                float qtyX0 = plusX0 - 28f;
                float minusX0 = qtyX0 - 13f;

                if (OstinatoScreen.in(mx, my, minusX0, ry + 3f, minusX0 + 11f, ry + 16f)) {
                    CART.add(li.id(), Keys.shift() ? -16 : -1);
                    return true;
                }
                if (OstinatoScreen.in(mx, my, qtyX0, ry + 3f, plusX0 - 2f, ry + 16f)) {
                    editingItemId = li.id();
                    amountEdit.set(String.valueOf(li.count()));
                    amountEdit.selectAll();
                    return true;
                }
                if (OstinatoScreen.in(mx, my, plusX0, ry + 3f, plusX0 + 11f, ry + 16f)) {
                    CART.add(li.id(), Keys.shift() ? 16 : 1);
                    return true;
                }
                if (OstinatoScreen.in(mx, my, p64X0, ry + 3f, p64X0 + 19f, ry + 16f)) {
                    CART.add(li.id(), 64);
                    return true;
                }
                if (OstinatoScreen.in(mx, my, delX0, ry + 3f, delX0 + 11f, ry + 16f)) {
                    CART.remove(li.id());
                    return true;
                }
            }
        }

        // Footer buttons: Stop + Gather All
        float stopW = 42f;
        float stopX0 = cx0 + 6f;
        if (button == 0 && OstinatoScreen.in(mx, my, stopX0, footY0 + 5f, stopX0 + stopW, by1 - 5f)) {
            dispatchStop();
            return true;
        }
        float goX0 = stopX0 + stopW + 5f;
        float goX1 = cx1 - 6f;
        if (button == 0 && !cartItems.isEmpty() && OstinatoScreen.in(mx, my, goX0, footY0 + 5f, goX1, by1 - 5f)) {
            dispatchGather();
            return true;
        }
        return false;
    }

    private void dispatchGather() {
        String cmd = CART.buildShopCommand();
        if (cmd.isEmpty()) {
            screen.flash("Shopping list is empty", Theme.AMBER);
            return;
        }
        String tgt = currentTarget();
        String finalCmd = "self".equalsIgnoreCase(tgt) ? cmd : "swarm send " + tgt + " " + cmd;
        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (primary != null) {
            primary.getCommandManager().execute(finalCmd);
            screen.flash("Gathering " + CART.distinctCount() + " item type(s) (" + CART.totalCount() + " total)", Theme.accent());
        } else {
            screen.flash("No active Baritone instance", Theme.DANGER);
        }
    }

    private void dispatchStop() {
        String tgt = currentTarget();
        String cmd = "self".equalsIgnoreCase(tgt) ? "shop stop" : "swarm send " + tgt + " shop stop";
        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (primary != null) {
            primary.getCommandManager().execute(cmd);
            screen.flash("Stopped shopping list gather", Theme.MUTED);
        }
    }

    private void commitAmountEdit() {
        if (editingItemId == null) return;
        String raw = amountEdit.get().trim();
        try {
            int val = Integer.parseInt(raw);
            CART.set(editingItemId, val);
        } catch (NumberFormatException ignored) {
        }
        editingItemId = null;
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        float split = splitX();
        float by0 = bodyY0(), by1 = bodyY1();
        if (mx < split) {
            float innerW = (split - 5f - px0) - 14f;
            int cols = Math.max(1, (int) Math.floor((innerW + GAP) / (TILE + GAP)));
            int totalRows = (filtered.size() + cols - 1) / cols;
            float max = Math.max(0f, totalRows * (TILE + GAP) + 6f - (by1 - by0 - 4f));
            gridScroll = Math.max(0f, Math.min(max, gridScroll - (float) delta * (TILE + GAP) * 2f));
            return true;
        } else {
            float max = Math.max(0f, CART.distinctCount() * CART_ROW_H + 4f - Math.max(10f, (by1 - 30f) - (by0 + 20f) - 2f));
            cartScroll = Math.max(0f, Math.min(max, cartScroll - (float) delta * CART_ROW_H * 2f));
            return true;
        }
    }

    boolean keyPressed(int key) {
        if (editingItemId != null) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                commitAmountEdit();
                return true;
            }
            if (key == InputConstants.KEY_ESCAPE) {
                editingItemId = null;
                return true;
            }
            return screen.editKey(amountEdit, key);
        }
        if (searchFocused) {
            if (key == InputConstants.KEY_ESCAPE) {
                if (!search.isEmpty()) {
                    search.set("");
                    rebuildFilter();
                } else {
                    searchFocused = false;
                }
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                searchFocused = false;
                return true;
            }
            String before = search.get();
            if (screen.editKey(search, key)) {
                if (!before.equals(search.get())) {
                    rebuildFilter();
                }
                return true;
            }
        }
        return false;
    }

    boolean charTyped(char c) {
        if (editingItemId != null) {
            if (Character.isDigit(c)) {
                amountEdit.insert(String.valueOf(c));
            }
            return true;
        }
        // Typing while on the Shop desk naturally filters the item catalog.
        searchFocused = true;
        search.insert(String.valueOf(c));
        rebuildFilter();
        return true;
    }
}
