package baritone.swarm.center.app;

import baritone.swarm.center.feed.Alert;
import baritone.swarm.center.feed.SwarmView;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded ring buffer storing recent {@link SwarmView} snapshots and timestamped event lines
 * so operators can scrub back through the last few minutes of positions, paths, alerts, and logs.
 */
public final class ReplayBuffer {

    public static final int DEFAULT_CAPACITY = 600; // ~5 minutes at 500 ms refresh cadence
    public static final int MAX_EVENTS = 500;

    public record Frame(long timestampMs, SwarmView view) {}

    public record EventLine(long timestampMs, String level, String bot, String message) {}

    private final int capacity;
    private final List<Frame> frames = new ArrayList<>();
    private final List<EventLine> events = new ArrayList<>();
    private long lastRecordedMs = -1L;

    public ReplayBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public ReplayBuffer(int capacity) {
        this.capacity = Math.max(2, capacity);
    }

    public synchronized void record(SwarmView view) {
        if (view == null) {
            return;
        }
        long ts = view.readAtMs();
        if (lastRecordedMs == ts && !frames.isEmpty()) {
            return;
        }
        lastRecordedMs = ts;
        if (frames.size() >= capacity) {
            frames.remove(0);
        }
        frames.add(new Frame(ts, view));
        for (Alert a : view.alerts()) {
            appendEvent(ts, a.level().name(), a.bot(), a.badge());
        }
    }

    public synchronized void appendEvent(long timestampMs, String level, String bot, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String b = bot == null ? "" : bot;
        if (!events.isEmpty()) {
            EventLine tail = events.get(events.size() - 1);
            if (tail.bot().equals(b) && tail.message().equals(message) && timestampMs - tail.timestampMs() < 5_000L) {
                return;
            }
        }
        if (events.size() >= MAX_EVENTS) {
            events.remove(0);
        }
        events.add(new EventLine(timestampMs, level == null ? "INFO" : level, b, message));
    }

    public synchronized int size() {
        return frames.size();
    }

    public synchronized Frame frameAt(int index) {
        if (frames.isEmpty()) {
            return null;
        }
        int clamped = Math.max(0, Math.min(frames.size() - 1, index));
        return frames.get(clamped);
    }

    public synchronized Frame latest() {
        return frames.isEmpty() ? null : frames.get(frames.size() - 1);
    }

    /** Returns up to {@code limit} events at or before {@code timestampMs}, newest last. */
    public synchronized List<EventLine> eventsUpTo(long timestampMs, int limit) {
        List<EventLine> out = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && out.size() < limit; i--) {
            EventLine e = events.get(i);
            if (e.timestampMs() <= timestampMs) {
                out.add(0, e);
            }
        }
        return out;
    }

    public synchronized void clear() {
        frames.clear();
        events.clear();
        lastRecordedMs = -1L;
    }
}
