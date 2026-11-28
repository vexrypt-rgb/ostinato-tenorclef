package adris.altoclef.swarm;

import adris.altoclef.swarm.survey.SurveyTask;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What an assignment is trying to achieve, and how the swarm checks it from evidence.
 * ACQUIRE is executed and verified end to end through the goal system (AcquireItemGoal on the agent).
 * SURVEY asks one scout to observe the unknown chunks of one bounded region of a marked area; the leader verifies it
 * against the merged chunk observations, not against the scout's word (see {@code SwarmCoordinator}).
 */
public final class Objective {
    public enum Kind { ACQUIRE, SURVEY }

    public final String item;
    public final int count;
    private final SurveyTask survey;

    private Objective(String item, int count, SurveyTask survey) {
        this.item = item;
        this.count = count;
        this.survey = survey;
    }

    public static Objective survey(SurveyTask task) {
        if (task == null) throw new IllegalArgumentException("survey task required");
        return new Objective("", 1, task);
    }

    public Kind kind() { return survey == null ? Kind.ACQUIRE : Kind.SURVEY; }
    public boolean isSurvey() { return survey != null; }
    /** The region this objective covers; null for ACQUIRE. */
    public SurveyTask survey() { return survey; }

    public static Objective acquire(String item, int count) {
        if (item == null || item.isBlank()) throw new IllegalArgumentException("item required");
        return new Objective(item.trim().toLowerCase(Locale.ROOT), Math.max(1, count), null);
    }

    public String describe() {
        return survey != null ? "survey " + survey.surveyId() + " region " + survey.region() : "acquire " + item + " x" + count;
    }

    /** Capabilities usually needed for this objective; the submitter may override them on the Assignment. */
    public Set<Capability> defaultCapabilities() {
        Set<Capability> caps = EnumSet.noneOf(Capability.class);
        if (survey != null) {
            caps.add(Capability.CAN_SCOUT);
        } else if (item.endsWith("_log") || item.endsWith("_ore") || item.contains("cobblestone") || item.equals("obsidian")
                || item.equals("coal") || item.equals("diamond")) {
            caps.add(Capability.CAN_MINE);
        } else if (item.equals("blaze_rod") || item.equals("ender_pearl") || item.equals("leather")
                || item.startsWith("cooked_") || item.equals("bone") || item.equals("string")) {
            caps.add(Capability.CAN_FIGHT);
        } else {
            caps.add(Capability.CAN_CRAFT);
        }
        return caps;
    }

    /** The swarm's own check of a SUCCEEDED report: the agent must have observed the full count. */
    public boolean verify(Map<String, String> evidence) {
        if (evidence == null || survey != null) return false; // a survey is verified by the leader against merged observations
        String have = evidence.get("have");
        if (have == null) return false;
        try {
            return Integer.parseInt(have) >= count;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
