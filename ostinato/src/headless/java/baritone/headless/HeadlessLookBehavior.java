/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * Instant aim: a requested rotation is written to the player the same tick, so the movement packet for
 * this tick carries it. Vanilla's LookBehavior slews and caps the turn; headless there is no hand and
 * no visual continuity to preserve, and the aim processor reports every requested rotation as final.
 */
package baritone.headless;

import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.context.BotContext;
import baritone.api.context.BotContextScope;
import baritone.api.utils.Rotation;
import net.minecraft.util.Mth;

public final class HeadlessLookBehavior implements ILookBehavior {

    private final HeadlessPlayer player;
    private final AimProcessor aim = new AimProcessor();
    private volatile boolean updated;
    private double sensitivity = 0.5;
    private float lastTurnDegrees;
    private float tickBaseYaw = Float.NaN;
    private float tickBasePitch = Float.NaN;

    public HeadlessLookBehavior(HeadlessPlayer player) {
        this.player = player;
    }

    /**
     * Anchors the per-tick slew cap to the last-sent wire pose so multiple {@link #updateTarget}
     * calls in the same tick (e.g., TenorClef agent + Baritone executor) cannot exceed 32 deg/tick.
     */
    public void beginTick(float baseYaw, float basePitch) {
        this.tickBaseYaw = Float.isNaN(baseYaw) ? player.getYRot() : Mth.wrapDegrees(baseYaw);
        this.tickBasePitch = Float.isNaN(basePitch) ? player.getXRot() : Mth.clamp(basePitch, -90f, 90f);
    }

    public double sensitivity() {
        return sensitivity;
    }

    public void setSensitivity(double sensitivity) {
        this.sensitivity = Math.max(0.0, Math.min(1.0, sensitivity));
    }

    public float lastTurnDegrees() {
        return lastTurnDegrees;
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract) {
        if (rotation == null) {
            return;
        }
        float curYaw = Float.isNaN(tickBaseYaw) ? player.getYRot() : tickBaseYaw;
        float curPitch = Float.isNaN(tickBasePitch) ? player.getXRot() : tickBasePitch;
        float maxYaw = player.onClimbable()
                ? VanillaRotationMath.MAX_CLIMBABLE_YAW_PER_TICK
                : VanillaRotationMath.MAX_YAW_PER_TICK;
        Rotation stepped = VanillaRotationMath.stepTowards(
                curYaw, curPitch,
                rotation.getYaw(), rotation.getPitch(),
                maxYaw, VanillaRotationMath.MAX_PITCH_PER_TICK,
                sensitivity);
        player.setYRot(stepped.getYaw());
        player.setXRot(stepped.getPitch());
        float turn = Math.max(
                Math.abs(Mth.wrapDegrees(stepped.getYaw() - curYaw)),
                Math.abs(stepped.getPitch() - curPitch));
        this.lastTurnDegrees = turn;
        BotContext ctx = BotContextScope.currentOrNull();
        if (ctx != null) {
            ctx.setLastLookTurnDegrees(turn);
        }
        this.updated = true;
    }

    /** Restores an already-quantized/sent rotation snapshot without applying another angular slew step. */
    void restoreRawPose(float yaw, float pitch) {
        player.setYRot(Mth.wrapDegrees(yaw));
        player.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    public boolean isUpdated() {
        return updated;
    }

    public boolean consumeUpdated() {
        boolean was = this.updated;
        this.updated = false;
        return was;
    }

    @Override
    public IAimProcessor getAimProcessor() {
        return aim;
    }

    private final class AimProcessor implements ITickableAimProcessor {
        @Override
        public Rotation peekRotation(Rotation desired) {
            if (desired == null) {
                return null;
            }
            float maxYaw = player.onClimbable()
                    ? VanillaRotationMath.MAX_CLIMBABLE_YAW_PER_TICK
                    : VanillaRotationMath.MAX_YAW_PER_TICK;
            return VanillaRotationMath.stepTowards(
                    player.getYRot(), player.getXRot(),
                    desired.getYaw(), desired.getPitch(),
                    maxYaw, VanillaRotationMath.MAX_PITCH_PER_TICK,
                    sensitivity);
        }

        @Override
        public Rotation peekAim(Rotation desired) {
            return peekRotation(desired);
        }

        @Override
        public Rotation peekSettledRotation(Rotation desired) {
            if (desired == null) {
                return null;
            }
            return VanillaRotationMath.stepTowards(
                    player.getYRot(), player.getXRot(),
                    desired.getYaw(), desired.getPitch(),
                    360.0f, 180.0f,
                    sensitivity);
        }

        @Override
        public ITickableAimProcessor fork() {
            return this;
        }

        @Override
        public void tick() {
        }

        @Override
        public void advance(int ticks) {
        }

        @Override
        public Rotation nextRotation(Rotation rotation) {
            return rotation;
        }
    }
}
