package baritone.swarm.center;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: Default dark meets WCAG AA, and tile status never relies on color alone. */
public class CenterThemeTest {

    private static final CenterTheme T = CenterTheme.DEFAULT_DARK;

    @Test public void textMeetsAaOnEverySurface() {
        for (int bg : new int[] {T.background(), T.surface(), T.raised()}) {
            assertTrue(CenterTheme.contrast(T.text(), bg) >= 4.5);
            assertTrue(CenterTheme.contrast(T.muted(), bg) >= 4.5);
        }
        assertTrue(CenterTheme.contrast(T.faint(), T.surface()) >= 3.0);
        assertTrue(CenterTheme.contrast(T.onAccent(), T.accent()) >= 4.5);
        for (int status : new int[] {T.ok(), T.warn(), T.danger(), T.accent()}) {
            assertTrue(CenterTheme.contrast(status, T.surface()) >= 4.5);
        }
        assertEquals(21.0, CenterTheme.contrast(0x000000, 0xFFFFFF), 0.01);
    }

    private static BotSnapshot bot(int health, String task, ExecutionState state, ObservationState seen) {
        return new BotSnapshot("Bot", health, 20, task, state, seen, "whisper", "", "local");
    }

    @Test public void statusHasGlyphAndWordNotJustColor() {
        Set<String> glyphs = new HashSet<>();
        for (TileStatus.Level level : TileStatus.Level.values()) {
            glyphs.add(level.glyph);
        }
        assertEquals(TileStatus.Level.values().length, glyphs.size());
        TileStatus failed = TileStatus.of(bot(20, "mine", ExecutionState.FAILED, ObservationState.CONFIRMED));
        assertEquals("✖ failed", failed.badge());
        assertTrue(failed.alert());
        assertEquals("process dead", TileStatus.of(bot(-1, "process dead", null, ObservationState.UNKNOWN)).word());
        assertEquals(TileStatus.Level.WARN, TileStatus.of(bot(4, "", ExecutionState.RUNNING, ObservationState.CONFIRMED)).level());
        assertEquals("running", TileStatus.of(bot(-1, "", ExecutionState.RUNNING, ObservationState.CONFIRMED)).word());
        assertFalse(TileStatus.of(bot(20, "", ExecutionState.SUCCEEDED, ObservationState.CONFIRMED)).alert());
        assertEquals(2, TileStatus.alerts(List.of(
                bot(20, "", ExecutionState.FAILED, ObservationState.CONFIRMED),
                bot(20, "", null, ObservationState.STALE),
                bot(20, "", ExecutionState.RUNNING, ObservationState.CONFIRMED))));
    }
}
