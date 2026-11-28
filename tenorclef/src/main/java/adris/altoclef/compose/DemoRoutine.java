package adris.altoclef.compose;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Saved or draft teach-by-demonstration routine consisting of semantic container-transfer steps.
 * Contains zero raw slot indices and zero hardcoded container coordinates; concrete containers
 * are resolved at replay time through {@link DemoStationBindings}.
 */
public final class DemoRoutine {

    private final String name;
    private final List<DemoStep> steps;

    public DemoRoutine(String name, List<DemoStep> steps) {
        this.name = sanitizeRoutineName(name);
        this.steps = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(steps, "steps")));
    }

    public static String sanitizeRoutineName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "draft";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                out.append(c);
            }
        }
        return out.isEmpty() ? "draft" : out.toString();
    }

    public String name() {
        return name;
    }

    public List<DemoStep> steps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public boolean hasUnresolvedSteps() {
        for (DemoStep step : steps) {
            if (!step.resolved()) {
                return true;
            }
        }
        return false;
    }

    /** 1-based indices of all unresolved steps. */
    public List<Integer> unresolvedIndices() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).resolved()) {
                out.add(i + 1);
            }
        }
        return out;
    }

    public boolean isReadyToSaveOrRun() {
        return !steps.isEmpty() && !hasUnresolvedSteps();
    }

    /** Ordered distinct station display names referenced by resolved steps, keyed by canonical stationKey. */
    public Map<String, String> requiredStations() {
        Map<String, String> out = new LinkedHashMap<>();
        for (DemoStep step : steps) {
            if (!step.stationKey().isEmpty() && !out.containsKey(step.stationKey())) {
                out.put(step.stationKey(), step.stationName());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public DemoRoutine withName(String newName) {
        return new DemoRoutine(newName, steps);
    }

    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Routine \"").append(name).append("\" (").append(steps.size())
                .append(steps.size() == 1 ? " step" : " steps");
        if (hasUnresolvedSteps()) {
            sb.append(", UNRESOLVED: ").append(unresolvedIndices());
        } else if (!steps.isEmpty()) {
            sb.append(", ready");
        }
        sb.append("):");
        if (steps.isEmpty()) {
            sb.append("\n  (no transfer steps recorded)");
            return sb.toString();
        }
        for (int i = 0; i < steps.size(); i++) {
            sb.append("\n  ").append(steps.get(i).formatSummaryLine(i + 1));
        }
        if (hasUnresolvedSteps()) {
            sb.append("\n  Resolve with: @teach confirm <step#> <withdraw|deposit> <count> <item> <station>");
        } else {
            sb.append("\n  Edit: @teach edit <step#> station <name> | Save: @teach save <name> | Bind: @teach bind <station> <x> <y> <z>");
        }
        return sb.toString();
    }

    public Properties toProperties() {
        if (hasUnresolvedSteps()) {
            throw new IllegalStateException("Cannot serialize routine with unresolved steps: " + unresolvedIndices());
        }
        Properties props = new Properties();
        props.setProperty("version", "1");
        props.setProperty("name", name);
        props.setProperty("stepCount", Integer.toString(steps.size()));
        for (int i = 0; i < steps.size(); i++) {
            DemoStep s = steps.get(i);
            String prefix = "step." + i + ".";
            props.setProperty(prefix + "action", s.action().name());
            props.setProperty(prefix + "item", s.itemId());
            props.setProperty(prefix + "count", Integer.toString(s.count()));
            props.setProperty(prefix + "station", s.stationName());
            if (!s.observedBlockType().isEmpty()) {
                props.setProperty(prefix + "blockType", s.observedBlockType());
            }
        }
        return props;
    }

    public static DemoRoutine fromProperties(String fallbackName, Properties props) {
        String name = props.getProperty("name", fallbackName);
        int count = Integer.parseInt(props.getProperty("stepCount", "0").trim());
        List<DemoStep> parsed = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            String prefix = "step." + i + ".";
            DemoStep.Action action = DemoStep.Action.valueOf(props.getProperty(prefix + "action", "").trim().toUpperCase(Locale.ROOT));
            String item = props.getProperty(prefix + "item", "");
            int qty = Integer.parseInt(props.getProperty(prefix + "count", "0").trim());
            String station = props.getProperty(prefix + "station", "");
            String blockType = props.getProperty(prefix + "blockType", "");
            parsed.add(DemoStep.resolved(action, item, qty, station, blockType));
        }
        return new DemoRoutine(name, parsed);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DemoRoutine that)) return false;
        return name.equals(that.name) && steps.equals(that.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, steps);
    }
}
