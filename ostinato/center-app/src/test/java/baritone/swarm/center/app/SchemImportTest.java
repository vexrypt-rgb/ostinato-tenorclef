package baritone.swarm.center.app;

import baritone.swarm.rts.Blueprint;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: JDK-only .litematic / .schem import, and the NBT round-trip those files need. */
public class SchemImportTest {

    @Test public void nbtGzipRoundTripKeepsCompoundsAndArrays() {
        Nbt.Compound c = new Nbt.Compound();
        c.put("Version", 2);
        c.put("Name", "demo");
        c.put("BlockData", new byte[]{1, 2, 3});
        c.put("BlockStates", new long[]{7L, 9L});
        Nbt.Compound pal = new Nbt.Compound();
        pal.put("minecraft:stone", 0);
        c.put("Palette", pal);
        byte[] gz = Nbt.writeGzip(c, "Schematic");
        Nbt.Compound back = Nbt.read(gz);
        assertEquals(2, back.i("Version", -1));
        assertEquals("demo", back.s("Name"));
        assertArrayEquals(new byte[]{1, 2, 3}, back.bytes("BlockData"));
        assertArrayEquals(new long[]{7L, 9L}, back.longs("BlockStates"));
        assertEquals(0, back.child("Palette").i("minecraft:stone", -1));
    }

    @Test public void aSpongeSchemRoundTripsAsOst1IdsAndDropsAirAndProperties() throws Exception {
        List<OstSchematic.Cell> cells = List.of(
                new OstSchematic.Cell(0, 0, 0, "cobblestone"),
                new OstSchematic.Cell(1, 0, 0, "oak_stairs"),
                new OstSchematic.Cell(0, 1, 0, "glass"));
        byte[] schem = SchemImport.writeSchem(2, 2, 1, cells);
        Path f = Files.createTempFile("tiny", ".schem");
        Files.write(f, schem);
        OstSchematic s = SchemImport.fromFile(f);
        assertEquals(3, s.cells().size());
        assertEquals(2, s.width());
        assertEquals(2, s.height());
        assertEquals(1, s.length());
        assertTrue(s.cells().stream().anyMatch(c -> c.block().equals("oak_stairs") && c.x() == 1));
        assertEquals(3, (int) s.materials().values().stream().mapToInt(i -> i).sum());
        Blueprint b = SchemImport.toBlueprint("tiny", s, new int[]{10, -60, 20});
        assertEquals("cobblestone", b.at(10, -60, 20).block());
        assertEquals("glass", b.at(10, -59, 20).block());
    }

    @Test public void aLitematicRoundTripsPackedBits() throws Exception {
        List<OstSchematic.Cell> cells = List.of(
                new OstSchematic.Cell(0, 0, 0, "cobblestone"),
                new OstSchematic.Cell(2, 0, 1, "dirt"),
                new OstSchematic.Cell(1, 1, 0, "copper_block"));
        byte[] file = SchemImport.writeLitematic(3, 2, 2, cells);
        OstSchematic s = SchemImport.fromBytes(file);
        assertEquals(3, s.cells().size());
        assertEquals("cobblestone", find(s, 0, 0, 0));
        assertEquals("dirt", find(s, 2, 0, 1));
        assertEquals("copper_block", find(s, 1, 1, 0));
        assertNull(find(s, 1, 0, 0)); // air omitted
    }

    @Test public void blockIdStripsPrefixAndProperties() {
        assertEquals("oak_stairs", SchemImport.blockId("minecraft:oak_stairs[facing=east,half=bottom]"));
        assertEquals("cobblestone", SchemImport.blockId("CobbleStone"));
        assertTrue(SchemImport.skip("minecraft:air"));
        assertTrue(SchemImport.skip("cave_air"));
    }

    @Test public void classicSchematicIsRefused() {
        Nbt.Compound c = new Nbt.Compound();
        c.put("Width", 1);
        c.put("Height", 1);
        c.put("Length", 1);
        c.put("Blocks", new byte[]{1});
        byte[] gz = Nbt.writeGzip(c, "Schematic");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SchemImport.fromBytes(gz));
        assertTrue(e.getMessage(), e.getMessage().contains("numeric ids"));
    }

    private static String find(OstSchematic s, int x, int y, int z) {
        for (OstSchematic.Cell c : s.cells()) {
            if (c.x() == x && c.y() == y && c.z() == z) {
                return c.block();
            }
        }
        return null;
    }
}
