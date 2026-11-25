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

package baritone.swarm.protocol;

import baritone.api.IBaritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Ostinato: the live {@link EffectCheck}. A block counts only when {@code ClientChunkCache.hasChunk} says the client
 * holds it. 26.3's {@code ClientLevel.hasChunkAt} is always true and reads a missing chunk as air, which would call a
 * mine "already done" from across the map.
 */
public final class GameEffectCheck implements EffectCheck {

    private final IBaritone baritone;

    public GameEffectCheck(IBaritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public String capture(UUID id, String name, String args) {
        try {
            return EffectNote.capture(name, args, view());
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public Verdict evaluate(UUID id, String name, String args, String note) {
        try {
            return EffectNote.evaluate(name, args, note, view());
        } catch (RuntimeException e) {
            return Verdict.UNKNOWN;
        }
    }

    private EffectNote.View view() {
        return new EffectNote.View() {
            @Override
            public String blockAt(int x, int y, int z) {
                try {
                    IPlayerContext ctx = baritone.getPlayerContext();
                    Level level = ctx == null ? null : ctx.world();
                    if (!(level instanceof ClientLevel client)) {
                        return null;
                    }
                    if (!client.getChunkSource().hasChunk(x >> 4, z >> 4)) {
                        return null;
                    }
                    return BuiltInRegistries.BLOCK.getKey(client.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
                } catch (RuntimeException e) {
                    return null;
                }
            }

            @Override
            public int itemCount(String itemId) {
                try {
                    IPlayerContext ctx = baritone.getPlayerContext();
                    if (ctx == null || ctx.player() == null) {
                        return -1;
                    }
                    Inventory inventory = ctx.player().getInventory();
                    int n = 0;
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        ItemStack stack = inventory.getItem(i);
                        if (stack == null || stack.isEmpty()) {
                            continue;
                        }
                        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        if (itemId == null || itemId.isEmpty() || EffectNote.sameId(id, itemId)) {
                            n += stack.getCount();
                        }
                    }
                    return n;
                } catch (RuntimeException e) {
                    return -1;
                }
            }

            @Override
            public int[] feet() {
                try {
                    IPlayerContext ctx = baritone.getPlayerContext();
                    if (ctx == null || ctx.player() == null) {
                        return null;
                    }
                    BetterBlockPos feet = ctx.playerFeet();
                    return feet == null ? null : new int[]{feet.x, feet.y, feet.z};
                } catch (RuntimeException e) {
                    return null;
                }
            }
        };
    }
}
