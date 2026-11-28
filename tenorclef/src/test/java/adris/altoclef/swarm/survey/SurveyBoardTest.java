package adris.altoclef.swarm.survey;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SurveyBoardTest {
    static SurveyArea area(int w, int h) { return new SurveyArea("OVERWORLD", -3, 5, -3 + w - 1, 5 + h - 1); }

    @Test
    void regionsPartitionTheAreaExactlyAndStayBounded() {
        SurveyArea a = area(19, 11); // not a multiple of the region side: edge regions are smaller
        SurveyBoard b = new SurveyBoard("sv1", a, 4, 0);
        SurveyGrid g = b.grid();
        Set<Long> seen = new HashSet<>();
        for (int r = 0; r < g.regionCount(); r++) {
            assertTrue(g.width(r) <= 8 && g.height(r) <= 8 && g.width(r) * g.height(r) <= 64, "bounded region");
            for (int bit = 0; bit < g.width(r) * g.height(r); bit++) {
                int cx = g.chunkX(r, bit), cz = g.chunkZ(r, bit);
                assertEquals(r, g.regionOf(cx, cz));
                assertEquals(bit, g.bitOf(r, cx, cz));
                assertTrue(seen.add(SurveyGrid.pack(cx, cz)), "a chunk belongs to exactly one region");
            }
        }
        assertEquals(a.chunkCount(), seen.size());
        assertEquals(-1, g.regionOf(a.maxCx() + 1, a.minCz()));
    }

    @Test
    void largeAreasGrowTheRegionSideAndHugeOnesAreRefused() {
        SurveyGrid g = SurveyGrid.plan(new SurveyArea("OVERWORLD", 0, 0, 79, 79), 2);
        assertTrue(g.regionCount() <= SurveyGrid.MAX_REGIONS);
        assertTrue(g.side() <= SurveyGrid.MAX_SIDE);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SurveyGrid.plan(new SurveyArea("OVERWORLD", 0, 0, 200, 200), 4));
        assertTrue(e.getMessage().contains("too large"));
    }

    @Test
    void chunksAreInExactlyOneOfFourStatesAndTheCountsAddUp() {
        SurveyBoard b = new SurveyBoard("sv1", area(8, 4), 4, 0);
        SurveyBoard.Snapshot s0 = b.snapshot();
        assertEquals(32, s0.total());
        assertEquals(32, s0.unknown());
        assertTrue(b.claim(0, "a", 10));
        assertFalse(b.claim(0, "b", 11), "a held region cannot be claimed twice");
        b.merge("a", "OVERWORLD", Map.of(0, 0b1111L), 20);
        b.markUnreachable(1, "test");
        SurveyBoard.Snapshot s = b.snapshot();
        assertEquals(4, s.observed());
        assertEquals(12, s.claimed());
        assertEquals(0, s.unknown());
        assertEquals(16, s.unreachable());
        assertEquals(s.total(), s.observed() + s.claimed() + s.unknown() + s.unreachable());
        assertEquals(SurveyBoard.RegionState.CLAIMED, b.state(0));
        assertEquals(SurveyBoard.RegionState.UNREACHABLE, b.state(1));
        assertEquals(SurveyBoard.ChunkState.OBSERVED, b.chunkState(-3, 5));
        assertEquals(SurveyBoard.ChunkState.CLAIMED, b.chunkState(0, 6));
        assertEquals(SurveyBoard.ChunkState.UNREACHABLE, b.chunkState(1, 5));
        assertEquals(4, b.chunks(SurveyBoard.ChunkState.OBSERVED).size());
    }

    @Test
    void releasingAClaimKeepsWhatWasObserved() {
        SurveyBoard b = new SurveyBoard("sv1", area(4, 4), 4, 0);
        b.claim(0, "a", 0);
        b.merge("a", "OVERWORLD", Map.of(0, 0xFFL), 5);
        b.release(0);
        assertEquals(SurveyBoard.RegionState.UNKNOWN, b.state(0));
        assertEquals(8, b.snapshot().unknown());
        assertEquals(8, b.snapshot().observed());
        assertEquals(0xFF00L, b.todo(0));
    }

    @Test
    void observationsMergeAndDuplicatesAreCounted() {
        SurveyBoard b = new SurveyBoard("sv1", area(4, 4), 4, 0);
        SurveyBoard.MergeResult r1 = b.merge("a", "OVERWORLD", Map.of(0, 0b0111L), 1_000);
        SurveyBoard.MergeResult r2 = b.merge("b", "OVERWORLD", Map.of(0, 0b1110L), 2_000);
        assertEquals(3, r1.fresh());
        assertEquals(1, r2.fresh());
        assertEquals(2, r2.duplicate());
        assertEquals(4, b.metrics().unique());
        assertEquals(2, b.metrics().duplicateObservations());
        b.merge("a", "OVERWORLD", Map.of(0, 0xFFFFL, 99, 1L), 3_000); // an unknown region index is ignored
        assertEquals(16, b.snapshot().observed());
        assertEquals(3_000, b.metrics().timeToCoverage(0.95));
        assertEquals(1_000 - 0, b.metrics().timeToCoverage(0.1));
    }

    @Test
    void observationFromAnotherDimensionIsRefusedWhole() {
        SurveyBoard b = new SurveyBoard("sv1", area(4, 4), 4, 0);
        SurveyBoard.MergeResult r = b.merge("a", "NETHER", Map.of(0, 0xFFFFL), 5);
        assertTrue(r.rejected());
        assertEquals(0, b.snapshot().observed());
        assertEquals(1, b.rejectedReports());
    }

    @Test
    void seeingAnUnreachableChunkSettlesIt() {
        SurveyBoard b = new SurveyBoard("sv1", area(4, 4), 4, 0);
        b.markUnreachable(0, "no path");
        assertEquals(16, b.snapshot().unreachable());
        b.merge("a", "OVERWORLD", Map.of(0, 0b11L), 5);
        assertEquals(14, b.snapshot().unreachable());
        assertEquals(2, b.snapshot().observed());
    }

    @Test
    void staleClaimNeedsNeitherNewChunksNorMovement() {
        SurveyBoard b = new SurveyBoard("sv1", area(8, 4), 4, 0);
        b.claim(0, "a", 0);
        b.claim(1, "b", 0);
        b.touch(0, "a", 0, 0, 1_000);
        b.touch(0, "a", 30, 0, 9_000);                       // moved: alive
        b.merge("b", "OVERWORLD", Map.of(1, 1L), 9_500);       // new chunk: alive
        assertEquals(List.of(), b.stale(10_000, 10_000));
        assertEquals(List.of(0, 1), b.stale(20_000, 10_000));
    }

    @Test
    void renderedMapAndDescriptionShowExactProgress() {
        SurveyBoard b = new SurveyBoard("sv1", area(8, 4), 4, 0);
        b.claim(0, "a", 0);
        b.merge("a", "OVERWORLD", Map.of(0, 0b1L), 1);
        b.markUnreachable(1, "water");
        List<String> lines = b.describe();
        String text = String.join("\n", lines);
        assertTrue(text.contains("observed 1/32"), text);
        assertTrue(text.contains("claimed 15"), text);
        assertTrue(text.contains("unreachable 16"), text);
        assertTrue(text.contains("region 0") && text.contains("CLAIMED by a"), text);
        assertTrue(text.contains("region 1") && text.contains("water"), text);
        assertTrue(text.contains("#ccc") && text.contains("xxxx"), text);
    }

    @Test
    void metricsAttributeLegsAndNeverHideOffAreaTravel() {
        SurveyMetrics m = new SurveyMetrics(100, 0);
        m.leg(100, 3, 1);   // 75% new
        m.leg(40, 0, 4);    // all duplicate
        m.leg(60, 0, 0);    // exposed nothing in the area
        assertEquals(75, m.newTravel(), 1e-9);
        assertEquals(25 + 40, m.duplicateTravel(), 1e-9);
        assertEquals(60, m.offAreaTravel(), 1e-9);
        assertEquals(200, m.totalTravel(), 1e-9);
    }
}
