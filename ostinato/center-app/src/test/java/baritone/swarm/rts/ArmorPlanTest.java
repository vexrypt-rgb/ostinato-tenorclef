package baritone.swarm.rts;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Ostinato: the RTS armor maker's next step. */
public class ArmorPlanTest {

    @Test
    public void recipesMatchVanillaShapes() {
        assertEquals(5, ArmorPlan.Piece.HELMET.ingots());
        assertEquals(8, ArmorPlan.Piece.CHESTPLATE.ingots());
        assertEquals(7, ArmorPlan.Piece.LEGGINGS.ingots());
        assertEquals(4, ArmorPlan.Piece.BOOTS.ingots());
        assertArrayEquals(new int[]{0, 2, 3, 4, 5, 6, 7, 8}, ArmorPlan.Piece.CHESTPLATE.grid);
        assertEquals(1, ArmorPlan.craftSlot(0));
        assertEquals(9, ArmorPlan.craftSlot(8));
        assertEquals("copper_ingot", ArmorPlan.ingot("copper"));
        assertEquals("copper_boots", ArmorPlan.Piece.BOOTS.id("copper"));
    }

    @Test
    public void gathersThenCraftsThenEquipsInOrder() {
        ArmorPlan.Step s = ArmorPlan.next("copper", Set.of(), List.of(), 0, false, 0, true);
        assertEquals(ArmorPlan.Kind.GATHER, s.kind());
        assertEquals(5, s.need());
        s = ArmorPlan.next("copper", Set.of(), List.of(), 24, false, 0, true);
        assertEquals(ArmorPlan.Kind.CRAFT, s.kind());
        assertEquals(ArmorPlan.Piece.HELMET, s.piece());
        s = ArmorPlan.next("copper", Set.of(), List.of("copper_helmet"), 19, false, 0, true);
        assertEquals(ArmorPlan.Kind.EQUIP, s.kind());
        s = ArmorPlan.next("copper", Set.of("copper_helmet"), List.of(), 19, false, 0, true);
        assertEquals(ArmorPlan.Piece.CHESTPLATE, s.piece());
    }

    @Test
    public void waitsWhenTheSupplyIsDry() {
        ArmorPlan.Step s = ArmorPlan.next("copper", Set.of("copper_helmet"), List.of(), 3, false, 0, false);
        assertEquals(ArmorPlan.Kind.WAIT, s.kind());
        assertEquals(5, s.need());
    }

    @Test
    public void wornSetIsDoneOrMakesOneToDeliver() {
        Set<String> all = Set.of("copper_helmet", "copper_chestplate", "copper_leggings", "copper_boots");
        assertEquals(ArmorPlan.Kind.DONE, ArmorPlan.next("copper", all, List.of(), 30, false, 0, true).kind());
        ArmorPlan.Step s = ArmorPlan.next("copper", all, List.of(), 30, true, 0, true);
        assertEquals(ArmorPlan.Kind.CRAFT, s.kind());
        assertEquals(ArmorPlan.Piece.HELMET, s.piece());
        s = ArmorPlan.next("copper", all, List.of("copper_helmet"), 25, true, 0, true);
        assertEquals(ArmorPlan.Kind.DELIVER, s.kind());
        assertEquals(ArmorPlan.Kind.DONE, ArmorPlan.next("copper", all, List.of(), 30, true, 4, true).kind());
    }
}
