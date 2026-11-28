package adris.altoclef.swarm.survey;

import java.util.ArrayList;
import java.util.List;

/** ASCII picture of a survey: '#' observed, 'c' claimed, '.' unknown, 'x' unreachable. North (low z) is up. */
public final class SurveyRender {
    private SurveyRender() {}

    public static List<String> map(SurveyBoard b, int maxCols, int maxRows) {
        SurveyArea a = b.area();
        List<String> out = new ArrayList<>();
        if (a.width() > maxCols || a.height() > maxRows) {
            out.add("  (map hidden: " + a.width() + "x" + a.height() + " chunks is wider than " + maxCols + "x" + maxRows + ")");
            return out;
        }
        out.add("  map x " + a.minCx() + ".." + a.maxCx() + " z " + a.minCz() + ".." + a.maxCz() + "  # observed  c claimed  . unknown  x unreachable");
        for (int cz = a.minCz(); cz <= a.maxCz(); cz++) {
            StringBuilder row = new StringBuilder("  ");
            for (int cx = a.minCx(); cx <= a.maxCx(); cx++) {
                row.append(switch (b.chunkState(cx, cz)) {
                    case OBSERVED -> '#';
                    case CLAIMED -> 'c';
                    case UNKNOWN -> '.';
                    case UNREACHABLE -> 'x';
                });
            }
            out.add(row.toString());
        }
        return out;
    }
}
