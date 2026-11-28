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

package baritone.swarm.transport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-server whisper command detection.
 *
 * <p>Filters the server's Brigadier command tree for commands shaped like {@code <player> <message>},
 * orders them by known-good preference ({@code msg}, {@code tell}, {@code w}, {@code whisper}, {@code m},
 * {@code pm}, {@code dm}), tracks self-test progress via sealed ping/pong, and refuses to fall back
 * to public chat if no whisper command works.
 */
public final class WhisperDetector {

    /** Known-good whisper command preference order from the design doc. */
    public static final List<String> KNOWN_ORDER = List.of("msg", "tell", "w", "whisper", "m", "pm", "dm");

    /** Default self-test wait per candidate before advancing to the next candidate. */
    public static final long PROBE_TIMEOUT_MS = 8_000L;

    public enum State {
        /** Using a manual override or cached working template. */
        RESOLVED,
        /** Probing candidates in order; awaiting signed pong or server error. */
        PROBING,
        /** All candidates failed; chat whisper link must stay down (use local spool if available). */
        FAILED
    }

    private final List<String> candidates;
    private int index;
    private State state;
    private String resolvedTemplate;
    private long probeStartedAtMs = -1L;
    private boolean echoVerified;

    private WhisperDetector(List<String> candidates, State state, String resolvedTemplate) {
        this.candidates = List.copyOf(candidates);
        this.state = state;
        this.resolvedTemplate = resolvedTemplate;
    }

    /**
     * Builds a detector from the server's two-argument command names (commands whose Brigadier tree
     * accepts {@code <player> <message>}). Manual overrides or cached templates resolve immediately.
     */
    public static WhisperDetector create(Collection<String> treeTwoArgCommands, String manualOverride,
                                         String cachedTemplate) {
        if (manualOverride != null && !manualOverride.isBlank()
                && SwarmChannel.WHISPER.validTemplate(manualOverride)) {
            return new WhisperDetector(List.of(manualOverride.trim()), State.RESOLVED, manualOverride.trim());
        }
        List<String> ordered = orderCandidates(treeTwoArgCommands);
        if (cachedTemplate != null && !cachedTemplate.isBlank()
                && SwarmChannel.WHISPER.validTemplate(cachedTemplate)) {
            String cmd = commandNameOf(cachedTemplate);
            // If we have a Brigadier tree and the cached command is not even in it, re-probe.
            if (treeTwoArgCommands == null || treeTwoArgCommands.isEmpty() || containsIgnoreCase(treeTwoArgCommands, cmd)) {
                return new WhisperDetector(ordered.isEmpty() ? List.of(cachedTemplate.trim()) : ordered,
                        State.RESOLVED, cachedTemplate.trim());
            }
        }
        if (ordered.isEmpty()) {
            // Tree was empty or unavailable (e.g. offline/early start): start with standard known order.
            ordered = toTemplates(KNOWN_ORDER);
        }
        return new WhisperDetector(ordered, State.PROBING, ordered.get(0));
    }

    /**
     * Filters {@code treeTwoArgCommands} by {@link #KNOWN_ORDER} first, followed by any other
     * whisper-like two-argument commands present in the tree, formatted as {@code /<cmd> {to} {msg}}.
     */
    public static List<String> orderCandidates(Collection<String> treeTwoArgCommands) {
        if (treeTwoArgCommands == null || treeTwoArgCommands.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String c : treeTwoArgCommands) {
            if (c == null) {
                continue;
            }
            String s = c.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("/")) {
                s = s.substring(1);
            }
            if (!s.isEmpty() && s.matches("[a-z0-9_:-]+")) {
                normalized.add(s);
            }
        }
        List<String> result = new ArrayList<>();
        for (String known : KNOWN_ORDER) {
            if (normalized.contains(known)) {
                result.add("/" + known + " {to} {msg}");
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Checks whether a Brigadier root command node has a {@code <player> <message>} shape
     * (at least depth 2 of argument/literal children, following redirects up to depth 3).
     */
    public static boolean looksLikeWhisperCommand(com.mojang.brigadier.tree.CommandNode<?> rootNode) {
        return hasDepthAtLeast(rootNode, 2, 0);
    }

    private static boolean hasDepthAtLeast(com.mojang.brigadier.tree.CommandNode<?> node, int needed, int redirects) {
        if (node == null) {
            return false;
        }
        if (needed <= 0) {
            return true;
        }
        if (node.getRedirect() != null && redirects < 3) {
            if (hasDepthAtLeast(node.getRedirect(), needed, redirects + 1)) {
                return true;
            }
        }
        for (com.mojang.brigadier.tree.CommandNode<?> child : node.getChildren()) {
            if (hasDepthAtLeast(child, needed - 1, redirects)) {
                return true;
            }
        }
        return false;
    }

    /** Extracts all root command names from a Brigadier dispatcher that accept at least two arguments. */
    public static List<String> inspectDispatcher(com.mojang.brigadier.CommandDispatcher<?> dispatcher) {
        if (dispatcher == null || dispatcher.getRoot() == null) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (com.mojang.brigadier.tree.CommandNode<?> child : dispatcher.getRoot().getChildren()) {
            if (looksLikeWhisperCommand(child)) {
                found.add(child.getName());
            }
        }
        return found;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized List<String> candidates() {
        return candidates;
    }

    /**
     * Active template to use right now, or {@code null} when detection failed (do not leak tokens to public chat).
     */
    public synchronized String currentTemplate() {
        return state == State.FAILED ? null : resolvedTemplate;
    }

    public synchronized boolean isEchoVerified() {
        return echoVerified;
    }

    /** Marks that a sealed ping self-test was sent on the current candidate. */
    public synchronized void markProbeSent(long nowMs) {
        if (state == State.PROBING && probeStartedAtMs < 0L) {
            probeStartedAtMs = nowMs;
        }
    }

    /** Called when a signed pong or authenticated peer message arrives over chat. */
    public synchronized boolean onSignedAckReceived(boolean matchedWhisperFormat) {
        if (state == State.FAILED) {
            return false;
        }
        echoVerified = echoVerified || matchedWhisperFormat;
        boolean newlyResolved = state != State.RESOLVED;
        state = State.RESOLVED;
        return newlyResolved;
    }

    /**
     * Called when the server rejects the current whisper command (unknown command, whispers disabled, no permission).
     * Advances to the next candidate, or transitions to {@link State#FAILED} if none remain.
     *
     * @return the new candidate template, or {@code null} if exhausted
     */
    public synchronized String onCandidateRejected(long nowMs) {
        if (state == State.FAILED) {
            return null;
        }
        index++;
        if (index >= candidates.size()) {
            state = State.FAILED;
            resolvedTemplate = null;
            return null;
        }
        state = State.PROBING;
        resolvedTemplate = candidates.get(index);
        probeStartedAtMs = nowMs;
        return resolvedTemplate;
    }

    /**
     * Checks whether the active probe timed out without a signed ack; advances to the next candidate if so.
     *
     * @return true if the active template changed (including to {@code null} on exhaustion)
     */
    public synchronized boolean tickTimeout(long nowMs) {
        if (state != State.PROBING || probeStartedAtMs < 0L) {
            return false;
        }
        if (nowMs - probeStartedAtMs < PROBE_TIMEOUT_MS) {
            return false;
        }
        onCandidateRejected(nowMs);
        return true;
    }

    /** Checks common whisper echo formats (vanilla, Essentials, Bukkit/Paper). */
    public static boolean matchesWhisperEcho(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return false;
        }
        String lower = rawLine.toLowerCase(Locale.ROOT);
        return lower.contains("whispers to you:")
                || lower.contains("you whisper to ")
                || lower.contains(" -> me]")
                || lower.contains("[me -> ")
                || lower.contains(" whispers: ")
                || lower.contains("from ") && lower.contains(": ");
    }

    public static String commandNameOf(String template) {
        String head = baritone.swarm.SwarmSample.commandOf(template);
        return head.startsWith("/") ? head.substring(1).toLowerCase(Locale.ROOT) : head.toLowerCase(Locale.ROOT);
    }

    private static List<String> toTemplates(List<String> names) {
        List<String> out = new ArrayList<>(names.size());
        for (String n : names) {
            out.add("/" + n + " {to} {msg}");
        }
        return out;
    }

    private static boolean containsIgnoreCase(Collection<String> items, String target) {
        for (String item : items) {
            if (item != null && item.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }
}
