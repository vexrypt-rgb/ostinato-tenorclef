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

package baritone.swarm.world;

import baritone.swarm.lease.ReservationResolver;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Ostinato: Phase 2c part 2. A client chest menu is not the block entity. The old reader returned null for that
 * menu, so an open single chest never reached the on-open check. These fail if that rule comes back.
 */
public class OpenChestTest {

    private static final BlockPos CHEST = new BlockPos(302, 150, 300);

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // A unit test never starts a world, so the item holder has no component map until one is bound.
        if (!Items.DIAMOND.builtInRegistryHolder().areComponentsBound()) {
            Items.DIAMOND.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        }
    }

    @Test
    public void aClientMenuNamesTheOneOpenChest() {
        assertEquals(CHEST, OpenChest.position(null, 3, List.of(CHEST), null));
    }

    @Test
    public void aClientDoubleChestStaysUnreadable() {
        assertNull(OpenChest.position(null, 6, List.of(CHEST), null));
        assertNull(OpenChest.position(null, 3, List.of(CHEST, new BlockPos(303, 150, 300)), null));
    }

    @Test
    public void theRememberedBlockIsUsedUntilALidOpens() {
        assertEquals(CHEST, OpenChest.position(null, 3, List.of(), CHEST));
    }

    @Test
    public void aBlockEntityContainerKeepsItsOwnPosition() {
        assertEquals(CHEST, OpenChest.position(CHEST, 3, List.of(), null));
    }

    @Test
    public void countsComeFromTheMenuInventory() {
        SimpleContainer inv = new SimpleContainer(27);
        inv.setItem(0, new ItemStack(Items.DIAMOND, 1));
        assertArrayEquals(new String[]{"c:o:302,150,300", "diamond.1"},
                OpenChest.read(inv, CHEST, 'o', single()));
    }

    @Test
    public void aDoubleChestWorldStaysUnreadable() {
        SimpleContainer inv = new SimpleContainer(27);
        inv.setItem(0, new ItemStack(Items.DIAMOND, 1));
        assertNull(OpenChest.read(inv, CHEST, 'o', bothHalves()));
    }

    private static ReservationResolver.World single() {
        return new ReservationResolver.World() {
            @Override
            public String block(int x, int y, int z) {
                return "minecraft:chest";
            }

            @Override
            public int[] otherChestHalf(int x, int y, int z) {
                return null;
            }
        };
    }

    private static ReservationResolver.World bothHalves() {
        return new ReservationResolver.World() {
            @Override
            public String block(int x, int y, int z) {
                return "minecraft:chest";
            }

            @Override
            public int[] otherChestHalf(int x, int y, int z) {
                return new int[]{303, 150, 300};
            }
        };
    }
}
