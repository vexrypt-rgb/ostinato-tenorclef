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

import baritone.swarm.lease.ReservationKey;
import baritone.swarm.lease.ReservationResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 2c part 2. The open chest, as a canonical {@code c:} key and a contents detail.
 *
 * <p>A server menu's container is the chest block entity, so that position names the chest. A client menu is built
 * with a plain inventory ({@code ChestMenu.threeRows(id, playerInventory)}), so the block entity test does not see a
 * single chest the player has open. The position is then the one open lid in reach, or the block already remembered
 * for this menu. A double chest stays unreadable: the client menu is six rows, and two lids are open.
 */
public final class OpenChest {

    private OpenChest() {}

    /**
     * The block this menu is reading. {@code blockEntityPos} is set only when the menu container is itself a block
     * entity. {@code rows} is the menu's row count. {@code openLids} are the chest blocks in reach whose lids are
     * open. {@code remembered} is the block already chosen for this menu, or the chest the player is looking at.
     */
    public static BlockPos position(BlockPos blockEntityPos, int rows, List<BlockPos> openLids, BlockPos remembered) {
        if (blockEntityPos != null) {
            return blockEntityPos;
        }
        if (rows != 3 || openLids == null) {
            return null;
        }
        if (openLids.size() == 1) {
            return openLids.get(0);
        }
        if (!openLids.isEmpty()) {
            return null;
        }
        return remembered;
    }

    /**
     * The canonical key and the contents detail, read from {@code inv}. Null when the block cannot be named, or when
     * the named block is one half of a double chest.
     */
    public static String[] read(Container inv, BlockPos pos, char dim, ReservationResolver.World world) {
        if (inv == null || pos == null || world == null) {
            return null;
        }
        ReservationResolver.Result resolved = ReservationResolver.resolve(
                "chest:" + pos.getX() + "," + pos.getY() + "," + pos.getZ(), dim, world);
        if (resolved == null || resolved.error != null || resolved.key == null) {
            return null;
        }
        ReservationKey key = ReservationKey.parse(resolved.key);
        if (key == null || key.type != ReservationKey.Type.CHEST || key.x1 != key.x2 || key.z1 != key.z2) {
            return null;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inv.getItem(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            counts.merge(id, stack.getCount(), Integer::sum);
        }
        return new String[]{resolved.key, ContainerDetail.of(counts)};
    }
}
