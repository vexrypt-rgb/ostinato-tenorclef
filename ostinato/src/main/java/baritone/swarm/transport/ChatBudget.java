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

import baritone.swarm.SwarmConfig;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Per-server adaptive chat budget feeding {@link SwarmRateLimiter}, {@link ChatSwarmTransport},
 * and {@link baritone.swarm.frame.SwarmChunker}.
 *
 * <p>Starts conservative on a new server (0.5 lines/s and 200 chars), learns from server chat replies,
 * disconnect reasons, and missing acks, halves rate or reduces line length on error signals, recovers
 * slowly after a clean period, and persists learned values in {@link ServerChatStore} while always
 * respecting manual per-server overrides.
 */
public final class ChatBudget {

    /** Conservative default line cap on an unknown server (below vanilla's 256). */
    public static final int CONSERVATIVE_MAX_CHARS = 200;
    /** Minimum line cap we ever back off to (keeps room for a sealed S1C/S2C/S2S frame). */
    public static final int MIN_LINE_CHARS = SwarmConfig.MIN_SEAL_LINE;
    /** Conservative default rate on an unknown server (never above vanilla's 1/s neutral rate). */
    public static final double CONSERVATIVE_RATE = 0.5;
    /** Clean interval required before slowly recovering rate/length. */
    public static final long RECOVERY_INTERVAL_MS = 120_000L;
    /** Consecutive missing acks before backing off rate. */
    public static final int ACK_MISS_THRESHOLD = 3;

    public enum Signal {
        NONE,
        COOLDOWN_OR_SPAM,
        TOO_LONG,
        WHISPER_REJECTED
    }

    private final String serverKey;
    private final ServerChatStore store;
    private final WhisperDetector detector;
    private final SharedChatBucket sharedBucket;
    private final double configuredMaxRate;
    private final int configuredMaxChars;
    private final boolean rateOverridden;
    private final boolean maxCharsOverridden;
    private final boolean allowUnsafe;
    private final Consumer<String> log;

    private double effectiveRate;
    private int effectiveMaxChars;
    private long lastSignalMs;
    private long lastRecoveryMs;
    private int consecutiveAckMisses;
    private long backoffEvents;
    private String lastSignalText = "";

    public ChatBudget(String server, ServerChatStore store, WhisperDetector detector, SharedChatBucket sharedBucket,
                      double configuredRate, int configuredMaxChars, boolean allowUnsafe, long nowMs,
                      Consumer<String> log) {
        this.serverKey = ServerChatStore.normalizeServer(server);
        this.store = store;
        this.detector = detector;
        this.sharedBucket = sharedBucket;
        this.allowUnsafe = allowUnsafe;
        this.log = log == null ? s -> {} : log;

        double maxAllowedRate = allowUnsafe ? Math.max(SwarmRateLimiter.MIN_RATE, configuredRate)
                : Math.min(SwarmRateLimiter.SAFE_MAX_RATE, Math.max(SwarmRateLimiter.MIN_RATE, configuredRate));
        this.configuredMaxRate = maxAllowedRate;
        this.configuredMaxChars = Math.max(MIN_LINE_CHARS, Math.min(256, configuredMaxChars));

        ServerChatStore.ServerProfile ov = store == null ? null : store.overrideFor(this.serverKey);
        ServerChatStore.ServerProfile learned = store == null ? ServerChatStore.ServerProfile.EMPTY
                : store.learnedFor(this.serverKey);

        this.rateOverridden = ov != null && ov.hasRate();
        this.maxCharsOverridden = ov != null && ov.hasMaxLineChars();

        if (rateOverridden) {
            this.effectiveRate = clampRate(ov.ratePerSec());
        } else if (learned.hasRate()) {
            this.effectiveRate = clampRate(Math.min(learned.ratePerSec(), this.configuredMaxRate));
        } else {
            this.effectiveRate = clampRate(Math.min(CONSERVATIVE_RATE, this.configuredMaxRate));
        }

        if (maxCharsOverridden) {
            this.effectiveMaxChars = clampChars(ov.maxLineChars());
        } else if (learned.hasMaxLineChars()) {
            this.effectiveMaxChars = clampChars(Math.min(learned.maxLineChars(), this.configuredMaxChars));
        } else {
            this.effectiveMaxChars = clampChars(Math.min(CONSERVATIVE_MAX_CHARS, this.configuredMaxChars));
        }

        this.lastSignalMs = nowMs;
        this.lastRecoveryMs = nowMs;
        if (sharedBucket != null) {
            sharedBucket.updateLimit(this.effectiveRate, SwarmRateLimiter.SAFE_MAX_BURST);
        }
    }

    public String serverKey() {
        return serverKey;
    }

    public WhisperDetector detector() {
        return detector;
    }

    public SharedChatBucket sharedBucket() {
        return sharedBucket;
    }

    public synchronized double effectiveRate() {
        return effectiveRate;
    }

    public synchronized int effectiveMaxChars() {
        return effectiveMaxChars;
    }

    public synchronized String effectiveTemplate() {
        return detector == null ? SwarmChannel.WHISPER.defaultTemplate() : detector.currentTemplate();
    }

    public synchronized int effectiveReserveChars() {
        String tpl = effectiveTemplate();
        return tpl == null ? 22 : ChatSwarmTransport.templateOverhead(tpl);
    }

    /** Remaining characters available for the sealed token after subtracting the active whisper prefix overhead. */
    public synchronized int effectiveSealBudget() {
        return Math.max(SwarmConfig.MIN_SEAL_LINE - 22, effectiveMaxChars - effectiveReserveChars());
    }

    public synchronized long backoffEvents() {
        return backoffEvents;
    }

    public synchronized String lastSignalText() {
        return lastSignalText;
    }

    /** Classifies a server chat or disconnect message into a budget signal. */
    public static Signal classifyServerText(String text) {
        if (text == null || text.isBlank()) {
            return Signal.NONE;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        // Never misclassify sealed SIGIL tokens themselves as error text.
        if (lower.contains("s1c.") || lower.contains("s2c.") || lower.contains("s2s.")) {
            return Signal.NONE;
        }
        if (lower.contains("unknown command") || lower.contains("unknown or incomplete command")
                || lower.contains("whispers are disabled") || lower.contains("cannot whisper")
                || lower.contains("private messaging is disabled") || lower.contains("do not have permission to msg")
                || lower.contains("no permission to whisper")) {
            return Signal.WHISPER_REJECTED;
        }
        if (lower.contains("too long") || lower.contains("character limit") || lower.contains("max length")
                || lower.contains("maximum length") || lower.contains("exceeds maximum") || lower.contains("message length")) {
            return Signal.TOO_LONG;
        }
        if (lower.contains("slow down") || lower.contains("cooldown") || lower.contains("too fast")
                || lower.contains("too quickly") || lower.contains("rate limit") || lower.contains("disconnect.spam")
                || lower.contains("kicked for spamming") || lower.contains("please wait before")
                || lower.contains("spamming")) {
            return Signal.COOLDOWN_OR_SPAM;
        }
        return Signal.NONE;
    }

    /** Learns from an incoming server chat/system message. Returns the classified signal. */
    public synchronized Signal onServerText(String text, long nowMs) {
        Signal sig = classifyServerText(text);
        if (sig == Signal.NONE) {
            return Signal.NONE;
        }
        applySignal(sig, text, nowMs);
        return sig;
    }

    /** Learns from a disconnect/kick reason string. */
    public synchronized Signal onDisconnect(String reason, long nowMs) {
        Signal sig = classifyServerText(reason);
        if (sig == Signal.NONE && reason != null && reason.toLowerCase(Locale.ROOT).contains("spam")) {
            sig = Signal.COOLDOWN_OR_SPAM;
        }
        if (sig != Signal.NONE) {
            applySignal(sig, reason, nowMs);
        }
        return sig;
    }

    /** Called when a signed ack arrives cleanly over chat. */
    public synchronized void onSignedAck(boolean matchedWhisperFormat, long nowMs) {
        consecutiveAckMisses = 0;
        if (detector != null && detector.onSignedAckReceived(matchedWhisperFormat)) {
            persist(nowMs);
        }
    }

    /** Called when a SwarmControl ping or command ack times out. */
    public synchronized boolean onAckTimeout(long nowMs) {
        consecutiveAckMisses++;
        if (consecutiveAckMisses >= ACK_MISS_THRESHOLD && !rateOverridden) {
            consecutiveAckMisses = 0;
            double next = clampRate(effectiveRate * 0.75);
            if (next < effectiveRate) {
                effectiveRate = next;
                lastSignalMs = nowMs;
                backoffEvents++;
                lastSignalText = "missing acks";
                updateShared();
                persist(nowMs);
                log.accept(String.format(Locale.ROOT,
                        "swarm-budget[%s]: missing acks, backing off rate to %.2f/s", serverKey, effectiveRate));
                return true;
            }
        }
        return false;
    }

    /** Periodic clean tick: advances whisper probe timeout and slowly recovers rate/length after a clean period. */
    public synchronized boolean tick(long nowMs) {
        boolean changed = false;
        if (detector != null && detector.tickTimeout(nowMs)) {
            changed = true;
            persist(nowMs);
        }
        if (nowMs - lastSignalMs >= RECOVERY_INTERVAL_MS && nowMs - lastRecoveryMs >= RECOVERY_INTERVAL_MS) {
            lastRecoveryMs = nowMs;
            if (!rateOverridden && effectiveRate < configuredMaxRate) {
                double next = clampRate(Math.min(configuredMaxRate, effectiveRate * 1.15));
                if (next > effectiveRate + 1e-4) {
                    effectiveRate = next;
                    changed = true;
                }
            }
            if (!maxCharsOverridden && effectiveMaxChars < configuredMaxChars) {
                int next = clampChars(Math.min(configuredMaxChars, effectiveMaxChars + 16));
                if (next > effectiveMaxChars) {
                    effectiveMaxChars = next;
                    changed = true;
                }
            }
            if (changed) {
                updateShared();
                persist(nowMs);
            }
        }
        return changed;
    }

    private void applySignal(Signal sig, String rawText, long nowMs) {
        lastSignalMs = nowMs;
        lastRecoveryMs = nowMs;
        lastSignalText = rawText == null ? sig.name() : rawText.trim();
        backoffEvents++;
        switch (sig) {
            case COOLDOWN_OR_SPAM -> {
                if (!rateOverridden) {
                    effectiveRate = clampRate(effectiveRate * 0.5);
                    updateShared();
                    persist(nowMs);
                    log.accept(String.format(Locale.ROOT,
                            "swarm-budget[%s]: spam/cooldown signal, halving rate to %.2f/s", serverKey, effectiveRate));
                }
            }
            case TOO_LONG -> {
                if (!maxCharsOverridden) {
                    int excess = Math.max(16, (effectiveMaxChars - MIN_LINE_CHARS) / 2);
                    effectiveMaxChars = clampChars(effectiveMaxChars - excess);
                    persist(nowMs);
                    log.accept(String.format(Locale.ROOT,
                            "swarm-budget[%s]: line-too-long signal, lowering max chars to %d",
                            serverKey, effectiveMaxChars));
                }
            }
            case WHISPER_REJECTED -> {
                if (detector != null) {
                    String next = detector.onCandidateRejected(nowMs);
                    persist(nowMs);
                    log.accept("swarm-budget[" + serverKey + "]: whisper command rejected, next="
                            + (next == null ? "NONE (whisper link down)" : next));
                }
            }
            case NONE -> {
            }
        }
    }

    private void updateShared() {
        if (sharedBucket != null) {
            sharedBucket.updateLimit(effectiveRate, SwarmRateLimiter.SAFE_MAX_BURST);
        }
    }

    private void persist(long nowMs) {
        if (store == null) {
            return;
        }
        String tpl = detector == null ? "" : (detector.currentTemplate() == null ? "" : detector.currentTemplate());
        store.putLearned(serverKey, new ServerChatStore.ServerProfile(tpl, effectiveMaxChars, effectiveRate, nowMs));
    }

    private double clampRate(double r) {
        double hi = allowUnsafe ? Math.max(SwarmRateLimiter.SAFE_MAX_RATE, configuredMaxRate) : SwarmRateLimiter.SAFE_MAX_RATE;
        return Math.max(SwarmRateLimiter.MIN_RATE, Math.min(hi, r));
    }

    private int clampChars(int chars) {
        return Math.max(MIN_LINE_CHARS, Math.min(256, chars));
    }
}
