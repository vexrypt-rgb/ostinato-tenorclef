package adris.altoclef.swarm.survey;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SurveyCommandSpecTest {
    static SurveyCommandSpec.Request parse(String s) { return SurveyCommandSpec.parse(s.split("\\s+")); }

    @Test
    void bareSurveyUsesTheMarkedSelection() {
        SurveyCommandSpec.Request r = parse("survey");
        assertEquals(SurveyCommandSpec.Action.START, r.action());
        assertNull(r.corners());
    }

    @Test
    void cornersInAnyOrderAreAccepted() {
        SurveyCommandSpec.Request r = parse("survey 100 -50 -20 300");
        assertArrayEquals(new int[]{100, -50, -20, 300}, r.corners());
        SurveyArea a = SurveyArea.fromBlocks("OVERWORLD", r.corners()[0], r.corners()[1], r.corners()[2], r.corners()[3]);
        assertEquals(-20 >> 4, a.minCx());
        assertEquals(100 >> 4, a.maxCx());
        assertEquals(-50 >> 4, a.minCz());
        assertEquals(300 >> 4, a.maxCz());
    }

    @Test
    void statusAndStopTakeAnOptionalId() {
        assertEquals(SurveyCommandSpec.Action.STATUS, parse("survey status").action());
        assertEquals("sv2", parse("survey stop sv2").surveyId());
        assertNull(parse("survey stop").surveyId());
    }

    @Test
    void badInputIsRefusedWithUsage() {
        assertThrows(IllegalArgumentException.class, () -> parse("survey 1 2 3"));
        assertThrows(IllegalArgumentException.class, () -> parse("survey a b c d"));
        assertThrows(IllegalArgumentException.class, () -> parse("survey status a b"));
        assertThrows(IllegalArgumentException.class, () -> SurveyCommandSpec.parse(new String[]{"acquire"}));
    }
}
