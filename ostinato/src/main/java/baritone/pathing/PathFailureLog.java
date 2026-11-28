/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing;

import baritone.api.event.events.PathEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Durable per-bot pathing failure log for the Why-Stuck diagnostics panel.
 * Captures CALC_FAILED, NEXT_CALC_FAILED, and CANCELED events along with the
 * attempted goal, node count, failure count, and recent failure history.
 */
public final class PathFailureLog {

    public static final int MAX_HISTORY = 32;

    public record Entry(long timestampMs, String event, String goal, int triedNodes, String reason) {}

    private final Deque<Entry> history = new ArrayDeque<>(MAX_HISTORY);
    private int consecutiveFailures;
    private int totalFailures;
    private String lastGoal = "";
    private String lastReason = "";
    private int lastTriedNodes;
    private long lastFailureMs = -1L;

    public synchronized void recordEvent(PathEvent event, String goal, int triedNodes, String detail, long nowMs) {
        if (event == null) {
            return;
        }
        if (event == PathEvent.CALC_FINISHED_NOW_EXECUTING || event == PathEvent.AT_GOAL) {
            consecutiveFailures = 0;
            return;
        }
        if (event == PathEvent.CALC_FAILED || event == PathEvent.NEXT_CALC_FAILED || event == PathEvent.CANCELED) {
            String g = goal == null ? "" : goal;
            String reason = detail != null && !detail.isBlank() ? detail : switch (event) {
                case CALC_FAILED -> "A* could not find path";
                case NEXT_CALC_FAILED -> "next-segment A* failed";
                case CANCELED -> "path canceled";
                default -> event.name().toLowerCase(java.util.Locale.ROOT);
            };
            totalFailures++;
            if (event != PathEvent.CANCELED) {
                consecutiveFailures++;
            }
            lastGoal = g;
            lastReason = reason;
            lastTriedNodes = Math.max(0, triedNodes);
            lastFailureMs = nowMs;
            if (history.size() >= MAX_HISTORY) {
                history.pollFirst();
            }
            history.addLast(new Entry(nowMs, event.name(), g, lastTriedNodes, reason));
        }
    }

    public synchronized int totalFailures() {
        return totalFailures;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized String lastGoal() {
        return lastGoal;
    }

    public synchronized String lastReason() {
        return lastReason;
    }

    public synchronized int lastTriedNodes() {
        return lastTriedNodes;
    }

    public synchronized long lastFailureMs() {
        return lastFailureMs;
    }

    public synchronized List<Entry> history() {
        return new ArrayList<>(history);
    }

    public synchronized void clear() {
        history.clear();
        consecutiveFailures = 0;
        totalFailures = 0;
        lastGoal = "";
        lastReason = "";
        lastTriedNodes = 0;
        lastFailureMs = -1L;
    }
}
