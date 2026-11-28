/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.swarm.center.app.OstSchematic;
import baritone.swarm.center.app.SchemImport;
import baritone.swarm.rts.Blueprint;
import baritone.swarm.rts.BlueprintRepairSession;
import baritone.utils.schematic.format.DefaultSchematicFormats;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Ostinato: Verifies Minecraft 26.3 block-state prediction, registry validation, legacy name-only matching,
 * direct Sponge/Litematica state preservation, and scoped repair schematic construction.
 */
public class BlueprintStateHelperTest {

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void predictPlacementStateMatchesVanillaLogSlabAndStraightStairRules() {
        // Log axis comes from clicked face axis
        assertEquals(Direction.Axis.X, BlueprintStateHelper.predictPlacementState(
                Blocks.OAK_LOG, Direction.EAST, 0.5, 0f).getValue(RotatedPillarBlock.AXIS));
        assertEquals(Direction.Axis.Y, BlueprintStateHelper.predictPlacementState(
                Blocks.OAK_LOG, Direction.UP, 0.0, 0f).getValue(RotatedPillarBlock.AXIS));
        assertEquals(Direction.Axis.Z, BlueprintStateHelper.predictPlacementState(
                Blocks.OAK_LOG, Direction.NORTH, 0.5, 0f).getValue(RotatedPillarBlock.AXIS));

        // Slab half comes from UP/DOWN face or hitFracY on horizontal face
        assertEquals(SlabType.BOTTOM, BlueprintStateHelper.predictPlacementState(
                Blocks.OAK_SLAB, Direction.UP, 0.0, 0f).getValue(SlabBlock.TYPE));
        assertEquals(SlabType.TOP, BlueprintStateHelper.predictPlacementState(
                Blocks.OAK_SLAB, Direction.DOWN, 1.0, 0f).getValue(SlabBlock.TYPE));
        assertEquals(SlabType.BOTTOM, BlueprintStateHelper.predictPlacementState(
                Blocks.OAK_SLAB, Direction.EAST, 0.25, 0f).getValue(SlabBlock.TYPE));
        assertEquals(SlabType.TOP, BlueprintStateHelper.predictPlacementState(
                Blocks.OAK_SLAB, Direction.EAST, 0.75, 0f).getValue(SlabBlock.TYPE));

        // Straight stair facing comes from player yaw, half from clicked face / hitFracY
        BlockState eastTop = BlueprintStateHelper.predictPlacementState(
                Blocks.OAK_STAIRS, Direction.WEST, 0.75, -90f);
        assertEquals(Direction.EAST, eastTop.getValue(StairBlock.FACING));
        assertEquals(Half.TOP, eastTop.getValue(StairBlock.HALF));
        assertEquals(StairsShape.STRAIGHT, eastTop.getValue(StairBlock.SHAPE));

        BlockState northBottom = BlueprintStateHelper.predictPlacementState(
                Blocks.OAK_STAIRS, Direction.UP, 0.0, 180f);
        assertEquals(Direction.NORTH, northBottom.getValue(StairBlock.FACING));
        assertEquals(Half.BOTTOM, northBottom.getValue(StairBlock.HALF));
    }

    @Test
    public void inventoryItemAvailabilityAllowsOrientedLogSlabAndStairPlacement() {
        assertTrue(BlueprintStateHelper.canPlaceBlockItemAs(
                Blocks.OAK_LOG, Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X)));
        assertTrue(BlueprintStateHelper.canPlaceBlockItemAs(
                Blocks.OAK_SLAB, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)));
        assertFalse(BlueprintStateHelper.canPlaceBlockItemAs(
                Blocks.OAK_SLAB, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE)));
        assertTrue(BlueprintStateHelper.canPlaceBlockItemAs(
                Blocks.OAK_STAIRS, Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.TOP)));
        assertFalse(BlueprintStateHelper.canPlaceBlockItemAs(
                Blocks.OAK_STAIRS, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.WATERLOGGED, true)));
    }

    @Test
    public void schematicPreservesLegacyNameOnlyEnforcesExplicitStatesAndSkipsUnsupported() {
        Blueprint bp = new Blueprint("mix");
        bp.paint(0, 64, 0, "oak_log");                                          // legacy name-only
        bp.paint(1, 64, 0, "oak_log[axis=x]");                                  // supported explicit
        bp.paint(2, 64, 0, "oak_slab[type=top]");                               // supported explicit
        bp.paint(3, 64, 0, "oak_stairs[facing=east,half=top]");                 // supported explicit
        bp.paint(4, 64, 0, "oak_stairs[facing=east,half=top,waterlogged=true]");// unsupported

        assertNull(BlueprintStateHelper.validateAgainstRegistry(bp.at(0, 64, 0)));
        assertNull(BlueprintStateHelper.validateAgainstRegistry(bp.at(1, 64, 0)));
        assertNull(BlueprintStateHelper.validateAgainstRegistry(bp.at(2, 64, 0)));
        assertNull(BlueprintStateHelper.validateAgainstRegistry(bp.at(3, 64, 0)));
        assertNotNull(BlueprintStateHelper.validateAgainstRegistry(bp.at(4, 64, 0)));

        ISchematic schem = BlueprintStateHelper.toSchematic(bp, null);
        // Legacy name-only preserves any existing orientation of oak_log
        BlockState existingLogZ = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        assertEquals(existingLogZ, schem.desiredState(0, 0, 0, existingLogZ, List.of()));

        // Explicit log axis enforces axis=x
        assertEquals(Direction.Axis.X,
                schem.desiredState(1, 0, 0, Blocks.AIR.defaultBlockState(), List.of()).getValue(RotatedPillarBlock.AXIS));

        // Explicit slab type enforces top
        assertEquals(SlabType.TOP,
                schem.desiredState(2, 0, 0, Blocks.AIR.defaultBlockState(), List.of()).getValue(SlabBlock.TYPE));

        // Explicit stair enforces facing=east, half=top
        BlockState stairWant = schem.desiredState(3, 0, 0, Blocks.AIR.defaultBlockState(), List.of());
        assertEquals(Direction.EAST, stairWant.getValue(StairBlock.FACING));
        assertEquals(Half.TOP, stairWant.getValue(StairBlock.HALF));

        // Unsupported cell is excluded from schematic so builder cannot falsely complete it
        assertFalse(schem.inSchematic(4, 0, 0, Blocks.AIR.defaultBlockState()));

        // Scoped repair schematic includes ONLY requested cell keys
        ISchematic scoped = BlueprintStateHelper.toSchematic(bp, Set.of(Blueprint.cellKey(2, 64, 0)));
        assertFalse(scoped.inSchematic(1, 0, 0, Blocks.AIR.defaultBlockState()));
        assertTrue(scoped.inSchematic(2, 0, 0, Blocks.AIR.defaultBlockState()));
        assertFalse(scoped.inSchematic(3, 0, 0, Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void directSpongeAndLitematicaReadersPreserveExplicitBlockStates() throws Exception {
        List<OstSchematic.Cell> cells = List.of(
                new OstSchematic.Cell(0, 0, 0, "oak_log[axis=x]"),
                new OstSchematic.Cell(1, 0, 0, "oak_slab[type=top]"),
                new OstSchematic.Cell(2, 0, 0, "oak_stairs[facing=east,half=top,shape=straight]"));

        byte[] schemBytes = SchemImport.writeSchem(3, 1, 1, cells);
        IStaticSchematic sponge = DefaultSchematicFormats.SPONGE.parse(new ByteArrayInputStream(schemBytes));
        assertEquals(Direction.Axis.X, sponge.getDirect(0, 0, 0).getValue(RotatedPillarBlock.AXIS));
        assertEquals(SlabType.TOP, sponge.getDirect(1, 0, 0).getValue(SlabBlock.TYPE));
        assertEquals(Direction.EAST, sponge.getDirect(2, 0, 0).getValue(StairBlock.FACING));
        assertEquals(Half.TOP, sponge.getDirect(2, 0, 0).getValue(StairBlock.HALF));

        byte[] liteBytes = SchemImport.writeLitematic(3, 1, 1, cells);
        IStaticSchematic lite = DefaultSchematicFormats.LITEMATICA.parse(new ByteArrayInputStream(liteBytes));
        assertEquals(Direction.Axis.X, lite.getDirect(0, 0, 0).getValue(RotatedPillarBlock.AXIS));
        assertEquals(SlabType.TOP, lite.getDirect(1, 0, 0).getValue(SlabBlock.TYPE));
        assertEquals(Direction.EAST, lite.getDirect(2, 0, 0).getValue(StairBlock.FACING));
        assertEquals(Half.TOP, lite.getDirect(2, 0, 0).getValue(StairBlock.HALF));

        // Observing MC BlockStates feeds directly into BlueprintRepairSession.compare
        Blueprint bp = SchemImport.toBlueprint("direct", OstSchematic.of(cells), new int[]{0, 64, 0});
        BlueprintRepairSession.Comparison cmp = BlueprintRepairSession.compare(bp,
                (x, y, z) -> BlueprintStateHelper.observeState(sponge.getDirect(x, y - 64, z)),
                BlueprintStateHelper::validateAgainstRegistry);
        assertTrue(cmp.allMatched());
        assertEquals(3, cmp.matched().size());
    }
}
