/*
 * Ostinato: SeenOnly.View backed by HeadlessChunkStore for legitMine line-of-sight filtering.
 */
package baritone.headless;

import baritone.api.legit.SeenOnly;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class HeadlessSeenView implements SeenOnly.View {

    private final HeadlessChunkStore chunks;

    public HeadlessSeenView(HeadlessChunkStore chunks) {
        this.chunks = chunks;
    }

    @Override
    public SeenOnly.Cell at(int x, int y, int z) {
        if (chunks == null || !chunks.isChunkLoaded(x >> 4, z >> 4)) {
            return SeenOnly.Cell.UNKNOWN;
        }
        BlockState state = chunks.getBlockState(x, y, z);
        if (state == null || state.is(Blocks.VOID_AIR)) {
            return SeenOnly.Cell.UNKNOWN;
        }
        if (!state.getFluidState().isEmpty() && state.getBlock() instanceof LiquidBlock) {
            return SeenOnly.Cell.FLUID;
        }
        if (state.isAir()) {
            return SeenOnly.Cell.AIR;
        }
        return SeenOnly.Cell.OPAQUE;
    }

    @Override
    public int light(int x, int y, int z) {
        SeenOnly.Cell cell = at(x, y, z);
        if (cell == SeenOnly.Cell.UNKNOWN) {
            return -1;
        }
        // HeadlessChunkStore strips client light nibble arrays to cut heap usage.
        // Treat loaded open cells (AIR/FLUID) as lit so exposed faces pass LOS checks,
        // while solid/opaque interior cells return 0 so cave-only light checks stay strict.
        return (cell == SeenOnly.Cell.AIR || cell == SeenOnly.Cell.FLUID) ? 15 : 0;
    }
}
