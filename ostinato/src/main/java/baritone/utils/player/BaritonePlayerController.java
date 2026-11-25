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

package baritone.utils.player;

import baritone.api.utils.IPlayerController;
import baritone.swarm.lease.LeaseFence;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;


/**
 * Implementation of {@link IPlayerController} that chains to the primary player controller's methods
 *
 * @author Brady
 * @since 12/14/2018
 */
public final class BaritonePlayerController implements IPlayerController {

    private final Minecraft mc;

    public BaritonePlayerController(Minecraft mc) {
        this.mc = mc;
    }

    @Override
    public void syncHeldItem() {
        // Ostinato: queue player controls; vanilla owns the action and its packets.

    }

    @Override
    public boolean hasBrokenBlock() {
        return !((IPlayerControllerMP) mc.gameMode).isHittingBlock();
    }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        // Ostinato: no break on an invalid lease, nor outside the active reservation (Phase 2b)
        if (LeaseFence.denied(LeaseFence.Kind.BREAK, pos.getX(), pos.getY(), pos.getZ())) {
            return false;
        }
        // Ostinato: asynchronous break; observe the block next tick.
        return ControlInput.breakBlock(pos);
    }

    @Override
    public void resetBlockRemoving() {
        // Ostinato: vanilla stops destroying when the attack key is released.
        ControlInput.releaseAttack();
    }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ContainerInput type, Player player) {
        // Ostinato: shared containers (windowId != 0) are fenced; the player's own inventory (0) never is.
        if (windowId != 0 && (LeaseFence.denied() || LeaseFence.containerBlocked())) {
            return;
        }
        // Ostinato: screen transaction waits until gameplay keys are released.
        ControlInput.windowClick(windowId, slotId, mouseButton, type);
    }

    @Override
    public GameType getGameType() {
        return mc.gameMode.getPlayerMode();
    }

    @Override
    public InteractionResult processRightClickBlock(LocalPlayer player, Level world, InteractionHand hand, BlockHitResult result) {
        // primaryplayercontroller is always in a ClientWorld so this is ok
        // Ostinato: no place/use-on-block on an invalid lease. Phase 2b: opening a container is checked at the
        // container, a placement at the block it lands in.
        BlockPos hit = result.getBlockPos();
        boolean container = world.getBlockEntity(hit) instanceof net.minecraft.world.Container;
        BlockPos at = container ? hit : hit.relative(result.getDirection());
        if (LeaseFence.denied(container ? LeaseFence.Kind.CONTAINER : LeaseFence.Kind.PLACE,
                at.getX(), at.getY(), at.getZ())) {
            return InteractionResult.PASS;
        }
        // Ostinato: vanilla decides hand/fallback after the queued click.
        return ControlInput.useBlock(result);
    }

    @Override
    public InteractionResult processRightClick(LocalPlayer player, Level world, InteractionHand hand) {
        // Ostinato: no synchronous result for a queued input.
        return ControlInput.useItem(player, hand);
    }

    @Override
    public boolean clickBlock(BlockPos loc, Direction face) {
        // Ostinato: no break start on an invalid lease, nor outside the active reservation (Phase 2b)
        if (LeaseFence.denied(LeaseFence.Kind.BREAK, loc.getX(), loc.getY(), loc.getZ())) {
            return false;
        }
        // Ostinato: actual crosshair must match when input is delivered.
        return ControlInput.breakBlock(loc);
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {
        // Ostinato: queue player controls; vanilla owns the action and its packets.

    }
}
