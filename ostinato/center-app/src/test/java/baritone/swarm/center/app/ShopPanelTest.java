package baritone.swarm.center.app;

import baritone.swarm.center.ShoppingCatalog;
import baritone.swarm.center.channel.CenterRequest;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.JTextField;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Headless UI tests for the Command Center {@link ShopPanel} integrated into {@link CenterShell}.
 */
public class ShopPanelTest {

    @Test
    public void searchPickAmountsGatherAndStopOnHostAndPeer() {
        UiHarness ui = UiHarness.threeBots();
        ui.refresh();

        assertFalse(ui.shell.shopVisible());
        ui.click("header.shop");
        assertTrue(ui.shell.shopVisible());

        // Catalog initially contains all bundled MC 26.3 items
        JTable catalog = (JTable) ui.find("shop:catalog");
        assertNotNull(catalog);
        assertTrue("Expected >1500 items in catalog, got " + catalog.getRowCount(), catalog.getRowCount() > 1500);

        // Search for oak_log and add +64
        ui.type("shop:search", "oak_log");
        assertTrue(catalog.getRowCount() >= 1);
        int oakRow = findRowById(catalog, "oak_log");
        assertTrue("oak_log row should be present", oakRow >= 0);
        catalog.setRowSelectionInterval(oakRow, oakRow);
        ui.click("shop:add64");
        assertEquals(64, ui.shell.shopPanel().cart().countOf("oak_log"));

        // Search for diamond and add custom amount 12
        ui.type("shop:search", "diamond");
        int diaRow = findRowById(catalog, "diamond");
        assertTrue("diamond row should be present", diaRow >= 0);
        catalog.setRowSelectionInterval(diaRow, diaRow);
        ui.type("shop:amount", "12");
        ui.click("shop:addCustom");
        assertEquals(12, ui.shell.shopPanel().cart().countOf("diamond"));

        // Adjust quantity inside cart via +1 and -1 buttons
        ui.click("shop:inc:diamond");
        assertEquals(13, ui.shell.shopPanel().cart().countOf("diamond"));
        ui.click("shop:dec:oak_log");
        assertEquals(63, ui.shell.shopPanel().cart().countOf("oak_log"));

        assertTrue(ui.text("shop:preview").contains("#shop gather oak_log:63 diamond:13"));

        // Gather All on Host sends #shop gather ... directly
        ui.click("shop:gather");
        List<String> sentLines = ui.sent().stream().map(CenterRequest::line).toList();
        assertTrue("Expected shop gather on host, got " + sentLines,
                sentLines.contains("shop gather oak_log:63 diamond:13"));

        // Switch target to remote peer Azalea and send Gather All + Stop
        @SuppressWarnings("unchecked")
        JComboBox<String> target = (JComboBox<String>) ui.find("shop:target");
        target.setSelectedItem("Azalea");
        ui.click("shop:gather");
        ui.click("shop:stop");
        List<String> after = ui.sent().stream().map(CenterRequest::line).toList();
        assertTrue("Expected swarm send to Azalea, got " + after,
                after.contains("swarm send Azalea shop gather oak_log:63 diamond:13"));
        assertTrue("Expected swarm send stop to Azalea, got " + after,
                after.contains("swarm send Azalea shop stop"));

        // Theme switch preserves cart contents and shop panel state
        ui.click("header.theme.light");
        assertTrue(ui.shell.shopVisible());
        assertEquals(63, ui.shell.shopPanel().cart().countOf("oak_log"));
        assertEquals(13, ui.shell.shopPanel().cart().countOf("diamond"));

        // Clear empties cart
        ui.click("shop:clear");
        assertTrue(ui.shell.shopPanel().cart().isEmpty());

        // Close returns to previous layout
        ui.click("shop:close");
        assertFalse(ui.shell.shopVisible());
    }

    @Test
    public void categoryFilterAndDirectQuantityEditWork() throws Exception {
        UiHarness ui = UiHarness.threeBots();
        ui.refresh();
        ui.click("header.shop");

        @SuppressWarnings("unchecked")
        JComboBox<ShoppingCatalog.Category> cat = (JComboBox<ShoppingCatalog.Category>) ui.find("shop:category");
        cat.setSelectedItem(ShoppingCatalog.Category.TOOLS);
        JTable catalog = (JTable) ui.find("shop:catalog");
        int pickRow = findRowById(catalog, "iron_pickaxe");
        assertTrue(pickRow >= 0);
        catalog.setRowSelectionInterval(pickRow, pickRow);
        ui.click("shop:add1");
        assertEquals(1, ui.shell.shopPanel().cart().countOf("iron_pickaxe"));

        JTextField qty = (JTextField) ui.find("shop:qty:iron_pickaxe");
        assertNotNull(qty);
        qty.setText("4");
        qty.postActionEvent();
        assertEquals(4, ui.shell.shopPanel().cart().countOf("iron_pickaxe"));

        // Populate a realistic shopping list and render dark + light previews
        cat.setSelectedItem(ShoppingCatalog.Category.ALL);
        ui.shell.shopPanel().cart().set("oak_log", 64);
        ui.shell.shopPanel().cart().set("cobblestone", 128);
        ui.shell.shopPanel().cart().set("diamond", 12);
        ui.shell.shopPanel().cart().set("cooked_beef", 32);
        ui.shell.shopPanel().refreshCart();
        ui.refresh();
        assertNotNull(ui.screenshot("shop-panel-dark"));

        ui.click("header.theme.light");
        ui.refresh();
        assertNotNull(ui.screenshot("shop-panel-light"));
    }

    private static int findRowById(JTable table, String id) {
        for (int r = 0; r < table.getRowCount(); r++) {
            if (id.equals(table.getValueAt(r, 1))) {
                return r;
            }
        }
        return -1;
    }
}
