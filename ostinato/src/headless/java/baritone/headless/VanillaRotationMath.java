/*
 * Ostinato: vanilla mouse-sensitivity GCD quantization and smooth angular step caps
 * for headless look updates.
 */
package baritone.headless;

import baritone.api.utils.Rotation;
import net.minecraft.util.Mth;

public final class VanillaRotationMath {

    /** Normal per-tick angular slew cap (degrees/tick), matching client LookBehavior. */
    public static final float MAX_YAW_PER_TICK = 32.0f;
    public static final float MAX_PITCH_PER_TICK = 32.0f;
    /** Slower yaw cap when on climbables (ladders/vines), matching LookBehavior. */
    public static final float MAX_CLIMBABLE_YAW_PER_TICK = 8.0f;

    private VanillaRotationMath() {
    }

    /** Exact vanilla degrees per mouse count: {@code f = sensitivity * 0.6 + 0.2; f^3 * 8 * 0.15}. */
    public static float gcdStep(double sensitivity) {
        double clamped = Math.max(0.0, Math.min(1.0, sensitivity));
        double f = clamped * 0.6 + 0.2;
        return (float) (f * f * f * 8.0 * 0.15);
    }

    /** Quantize an angular delta to an integer multiple of the vanilla mouse GCD step. */
    public static float quantizeDelta(float delta, float gcd) {
        if (gcd <= 0f || !Float.isFinite(gcd)) {
            return delta;
        }
        return Math.round(delta / gcd) * gcd;
    }

    /**
     * Advances from {@code (curYaw, curPitch)} toward {@code (targetYaw, targetPitch)} while enforcing
     * per-tick angular caps and exact vanilla GCD quantization.
     */
    public static Rotation stepTowards(float curYaw, float curPitch, float targetYaw, float targetPitch,
                                       float maxYawDeg, float maxPitchDeg, double sensitivity) {
        float gcd = gcdStep(sensitivity);
        float dy = Mth.wrapDegrees(targetYaw - curYaw);
        float dp = Mth.clamp(targetPitch, -90f, 90f) - curPitch;
        float qdy;
        float qdp;
        if (gcd > 0f && Float.isFinite(gcd)) {
            int maxYawSteps = (int) Math.floor(maxYawDeg / gcd);
            int maxPitchSteps = (int) Math.floor(maxPitchDeg / gcd);
            int yawSteps = Math.max(-maxYawSteps, Math.min(maxYawSteps, Math.round(dy / gcd)));
            int pitchSteps = Math.max(-maxPitchSteps, Math.min(maxPitchSteps, Math.round(dp / gcd)));
            qdy = yawSteps * gcd;
            qdp = pitchSteps * gcd;
        } else {
            qdy = Mth.clamp(dy, -maxYawDeg, maxYawDeg);
            qdp = Mth.clamp(dp, -maxPitchDeg, maxPitchDeg);
        }
        float nextYaw = Mth.wrapDegrees(curYaw + qdy);
        float nextPitch = Mth.clamp(curPitch + qdp, -90f, 90f);
        return new Rotation(nextYaw, nextPitch);
    }

    public static boolean isQuantizedDelta(float delta, double sensitivity, float tolerance) {
        float gcd = gcdStep(sensitivity);
        if (gcd <= 0f) {
            return true;
        }
        float quantized = quantizeDelta(delta, gcd);
        return Math.abs(delta - quantized) <= tolerance;
    }
}
