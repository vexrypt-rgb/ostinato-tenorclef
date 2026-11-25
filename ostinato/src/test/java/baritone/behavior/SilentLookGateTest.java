package baritone.behavior;
import baritone.api.utils.Rotation;
import org.junit.Test;
import static org.junit.Assert.*;
// Ostinato: legacy free-look modes must never select an invisible rotation for physics or packets.
public class SilentLookGateTest {
    @Test public void allLegacyModesUseVisibleCamera() {
        Rotation camera = new Rotation(80, 5), sent = new Rotation(-60, -10);
        for (LookBehavior.Target.Mode mode : LookBehavior.Target.Mode.values()) {
            assertSame(camera, LookBehavior.gateFrom(mode, false, camera, sent));
            assertSame(camera, LookBehavior.gateFrom(mode, true, camera, null));
        }
    }
}
