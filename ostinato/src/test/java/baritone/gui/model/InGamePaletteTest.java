package baritone.gui.model;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class InGamePaletteTest {

    @Before
    public void reset() {
        InGamePalette.resetSessionForTests();
    }

    @Test
    public void fuzzySearchRanksExactPrefixAndFavoritesFirst() {
        InGamePalette pal = new InGamePalette(List.of(
                new InGamePalette.Entry("cmd:goto", "goto", "Walk to coordinates", "OWNER", false),
                new InGamePalette.Entry("cmd:mine", "mine", "Mine blocks by id", "OWNER", false),
                new InGamePalette.Entry("cmd:sel", "sel", "Selection editor", "OWNER", false)
        ));

        List<InGamePalette.Entry> exact = pal.search("mine");
        assertFalse(exact.isEmpty());
        assertEquals("mine", exact.get(0).command());

        // Fuzzy subsequence match ("mnd" -> "mine diamond_ore ...")
        List<InGamePalette.Entry> fuzzy = pal.search("mnd");
        assertTrue(fuzzy.stream().anyMatch(e -> e.command().startsWith("mine diamond_ore")));

        // Favorite boost promotes entry to top when query is empty
        assertTrue(InGamePalette.toggleFavorite("sel"));
        List<InGamePalette.Entry> empty = pal.search("");
        assertEquals("sel", empty.get(0).command());
    }

    @Test
    public void routingAndHistoryWorkForSelfBotAndAll() {
        assertEquals("goto 1 2 3", InGamePalette.routeCommand("#goto 1 2 3", "self", List.of("Azalea")));
        assertEquals("swarm send Azalea goto 1 2 3", InGamePalette.routeCommand("goto 1 2 3", "Azalea", List.of("Azalea")));
        assertEquals("swarm send all stop", InGamePalette.routeCommand("stop", "all", List.of("Azalea", "Spelunker")));
        // Already-global commands are not double-wrapped
        assertEquals("theme nether", InGamePalette.routeCommand("theme nether", "Azalea", List.of("Azalea")));

        InGamePalette.recordHistory("mine iron_ore");
        assertEquals(List.of("mine iron_ore"), InGamePalette.history());
    }
}
