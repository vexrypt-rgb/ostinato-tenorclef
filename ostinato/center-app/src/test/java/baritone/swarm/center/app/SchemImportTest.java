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

    @Test public void explicitPropertiesSurviveSchemLitematicAndStructureNbtRoundTrips() throws Exception {
        List<OstSchematic.Cell> cells = List.of(
                new OstSchematic.Cell(0, 0, 0, "oak_log[axis=x]"),
                new OstSchematic.Cell(1, 0, 0, "oak_slab[type=top,waterlogged=false]"),
                new OstSchematic.Cell(2, 0, 0, "oak_stairs[facing=east,half=top,shape=straight]"),
                new OstSchematic.Cell(3, 0, 0, "cobblestone"),
                new OstSchematic.Cell(0, 1, 0, "oak_stairs[facing=north,half=bottom,waterlogged=true]"));

        for (byte[] data : List.of(
                SchemImport.writeSchem(4, 2, 1, cells),
                SchemImport.writeLitematic(4, 2, 1, cells),
                SchemImport.writeStructureNbt(4, 2, 1, cells))) {
            OstSchematic back = SchemImport.fromBytes(data);
            assertEquals(5, back.cells().size());
            Blueprint bp = SchemImport.toBlueprint("landing", back, new int[]{10, -60, 20});
            assertEquals("oak_log[axis=x]", bp.at(10, -60, 20).spec());
            assertEquals("oak_slab[type=top,waterlogged=false]", bp.at(11, -60, 20).spec());
            assertEquals("oak_stairs[facing=east,half=top,shape=straight]", bp.at(12, -60, 20).spec());
            assertEquals("cobblestone", bp.at(13, -60, 20).spec());
            assertFalse(bp.at(13, -60, 20).hasExplicitProperties());
            // Unsupported property must be preserved, never silently dropped
            assertEquals("oak_stairs[facing=north,half=bottom,waterlogged=true]", bp.at(10, -59, 20).spec());
            Blueprint reloaded = Blueprint.read("landing", bp.write());
            assertEquals(bp.cells(), reloaded.cells());
        }
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
