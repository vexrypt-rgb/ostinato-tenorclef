package adris.altoclef.swarm.survey;

/**
 * Parses the player's {@code @swarm survey ...} words. Pure text in, request out, so the entry point is testable.
 * <pre>
 *   survey                      survey the area marked with Baritone's selection
 *   survey x1 z1 x2 z2          survey the area between two block corners
 *   survey status [id]          exact progress: observed / claimed / unknown / unreachable
 *   survey stop [id]            call off the survey; what was observed stays observed
 * </pre>
 */
public final class SurveyCommandSpec {
    public enum Action { START, STATUS, STOP }

    /** @param corners {x1, z1, x2, z2} in blocks, or null to use the marked selection */
    public record Request(Action action, int[] corners, String surveyId) {}

    public static final String USAGE = "@swarm survey [x1 z1 x2 z2] | status [id] | stop [id]";

    private SurveyCommandSpec() {}

    /** @param words the tokens of the command; words[0] is "survey" */
    public static Request parse(String[] words) {
        if (words.length == 0 || !"survey".equalsIgnoreCase(words[0])) throw new IllegalArgumentException(USAGE);
        if (words.length == 1) return new Request(Action.START, null, null);
        String w1 = words[1].toLowerCase(java.util.Locale.ROOT);
        if (w1.equals("status") || w1.equals("stop")) {
            if (words.length > 3) throw new IllegalArgumentException(USAGE);
            String id = words.length == 3 ? words[2] : null;
            return new Request(w1.equals("status") ? Action.STATUS : Action.STOP, null, id);
        }
        if (words.length != 5) throw new IllegalArgumentException(USAGE);
        int[] c = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                c[i] = Integer.parseInt(words[i + 1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a block coordinate: " + words[i + 1] + " (" + USAGE + ")");
            }
        }
        return new Request(Action.START, c, null);
    }
}
