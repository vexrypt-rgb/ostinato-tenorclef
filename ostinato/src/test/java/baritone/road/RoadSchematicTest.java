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

package baritone.road;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Verifies {@link RoadSchematic} exact-cell fidelity and defense-in-depth refusal of protected-zone edits using
 * real Minecraft 26.3 {@link BlockState}s.
 */
public class RoadSchematicTest {

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void schematicContainsOnlyExactPlannedClearAndPlaceCells() {
        RoadCorridorFixtures.Fixture f = RoadCorridorFixtures.fixture1HallToVillageNarrowFord();
        RoadPlanner.PlanResult res = RoadPlanner.plan(f.spec(), f.world());
        assertTrue(res.feasible());

        List<BuilderAccessPlan.BuildOp> stage = res.chosen().accessPlan().stages().get(0);
        BlockState deck = Blocks.COBBLESTONE.defaultBlockState();
        RoadSchematic schem = RoadSchematic.ofStage(f.spec(), stage, deck);

        for (BuilderAccessPlan.BuildOp op : stage) {
            int rx = op.x() - schem.origin.getX();
            int ry = op.y() - schem.origin.getY();
            int rz = op.z() - schem.origin.getZ();
            assertTrue("Planned op must be in schematic", schem.inSchematic(rx, ry, rz, Blocks.AIR.defaultBlockState()));
            BlockState want = schem.desiredState(rx, ry, rz, Blocks.AIR.defaultBlockState(), List.of(deck));
            if (op.kind() == BuilderAccessPlan.OpKind.CLEAR) {
                assertTrue("CLEAR op must want AIR", want.isAir());
            } else {
                assertEquals("PLACE op must want chosen deck block", deck, want);
            }
        }
    }

    @Test(expected = IllegalStateException.class)
    public void schematicRefusesProtectedZoneCell() {
        ProtectedZone zone = ProtectedZone.columns("field", 10, -4, 20, 4);
        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, 64, 0, "village", 44, 64, 0, 48, List.of(zone));
        BuilderAccessPlan.BuildOp illegal = new BuilderAccessPlan.BuildOp(
                BuilderAccessPlan.OpKind.PLACE, 12, 63, 0, 11, 64, 0, 11, 63, 0);
        RoadSchematic.ofStage(spec, List.of(illegal), Blocks.COBBLESTONE.defaultBlockState());
    }
}
