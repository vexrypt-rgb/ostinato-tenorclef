package adris.altoclef.swarm.survey;

import adris.altoclef.swarm.SwarmMessage;

/**
 * What one scout is asked to observe: the unknown chunks of one region ({@code todo}), plus the grid so it can
 * report any chunk it happens to see. The leader sends the todo mask as it is at offer time, so work that was half done
 * before a scout dropped out is offered as only what remains.
 */
public record SurveyTask(String surveyId, int region, SurveyArea area, int side, long todo) {

    public SurveyGrid grid() { return new SurveyGrid(area, side); }

    public SurveyTask withTodo(long newTodo) { return new SurveyTask(surveyId, region, area, side, newTodo); }

    public void writeTo(SwarmMessage m) {
        m.put("kind", "survey").put("sv", surveyId).put("rg", region).put("dim", area.dimension())
                .put("area", area.encodeRect()).put("side", side).put("todo", Long.toHexString(todo));
    }

    /** @return null when the offer is not a well-formed survey offer. */
    public static SurveyTask from(SwarmMessage m) {
        try {
            String sv = m.get("sv");
            SurveyArea area = SurveyArea.decode(m.get("dim"), m.get("area"));
            if (sv == null || area == null) return null;
            int side = Integer.parseInt(m.get("side", "0"));
            int region = Integer.parseInt(m.get("rg", "-1"));
            SurveyGrid g = new SurveyGrid(area, side);
            if (region < 0 || region >= g.regionCount()) return null;
            long todo = Long.parseUnsignedLong(m.get("todo", "0"), 16) & g.fullMask(region);
            return new SurveyTask(sv, region, area, side, todo);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
