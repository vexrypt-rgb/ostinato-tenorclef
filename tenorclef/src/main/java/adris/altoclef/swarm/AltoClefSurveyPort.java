package adris.altoclef.swarm;

import adris.altoclef.AltoClef;
import adris.altoclef.swarm.survey.SurveyScout;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.util.math.BlockPos;

/**
 * The live world for {@link SurveyScout}: what this client has actually loaded, where the player is, and walking through
 * Baritone's public goal process (baritone.api only). A chunk counts as observed only when the client has it loaded in the
 * dimension the survey names; nothing is read from the server or from other clients.
 */
final class AltoClefSurveyPort implements SurveyScout.Port {
    private static final int MAX_PROBE = 32;
    private final AltoClef mod;
    private int cachedRadius = 2;
    private long radiusAt;

    AltoClefSurveyPort(AltoClef mod) { this.mod = mod; }

    @Override
    public String dimension() { return WorldHelper.getCurrentDimension().name(); }

    @Override
    public double[] position() {
        return mod.getPlayer() == null ? null : new double[]{mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ()};
    }

    @Override
    public boolean isLoaded(int cx, int cz) {
        return mod.getChunkTracker().isChunkLoaded(new BlockPos(cx * 16 + 8, 64, cz * 16 + 8));
    }

    @Override
    public void goTo(double x, double z) {
        mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ((int) Math.floor(x), (int) Math.floor(z)));
    }

    @Override
    public boolean moving() {
        return mod.getClientBaritone().getCustomGoalProcess().isActive() || mod.getClientBaritone().getPathingBehavior().isPathing();
    }

    @Override
    public void stopMoving() {
        if (mod.getClientBaritone() == null) return;
        mod.getClientBaritone().getPathingBehavior().cancelEverything();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    /** Measured, not read from options, so it is right for any version and any server-side cap: how far loaded chunks reach. */
    @Override
    public int viewRadius() {
        long now = System.currentTimeMillis();
        if (now - radiusAt < 5_000 || mod.getPlayer() == null) return cachedRadius;
        radiusAt = now;
        int pcx = (int) Math.floor(mod.getPlayer().getX() / 16.0), pcz = (int) Math.floor(mod.getPlayer().getZ() / 16.0);
        int best = 1;
        for (int k = 1; k <= MAX_PROBE; k++) {
            if (isLoaded(pcx + k, pcz) || isLoaded(pcx - k, pcz) || isLoaded(pcx, pcz + k) || isLoaded(pcx, pcz - k)) best = k;
        }
        cachedRadius = best;
        return best;
    }

    @Override
    public long nowMs() { return System.currentTimeMillis(); }
}
