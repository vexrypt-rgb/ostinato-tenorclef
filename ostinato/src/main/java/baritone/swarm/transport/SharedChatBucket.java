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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared token bucket per server address for bots running on the same machine/IP.
 * Servers often apply anti-spam rate limits per IP or across linked accounts; drawing
 * from one shared bucket prevents multiple co-located bots from tripping the shared limit.
 */
public final class SharedChatBucket {

    private static final ConcurrentHashMap<String, SharedChatBucket> REGISTRY = new ConcurrentHashMap<>();

    private final String serverKey;
    private double ratePerSec;
    private int burst;
    private double tokens;
    private long lastMs;
    private long granted;
    private long throttled;

    public SharedChatBucket(String serverKey, double ratePerSec, int burst, long nowMs) {
        this.serverKey = ServerChatStore.normalizeServer(serverKey);
        this.ratePerSec = Math.max(SwarmRateLimiter.MIN_RATE, ratePerSec);
        this.burst = Math.max(1, burst);
        this.tokens = this.burst;
        this.lastMs = nowMs;
    }

    /** Returns the process-shared bucket for {@code server}, creating it if absent. */
    public static SharedChatBucket forServer(String server, double ratePerSec, int burst, long nowMs) {
        String key = ServerChatStore.normalizeServer(server);
        return REGISTRY.computeIfAbsent(key, k -> new SharedChatBucket(k, ratePerSec, burst, nowMs));
    }

    /** Tests only. */
    public static void clearRegistryForTests() {
        REGISTRY.clear();
    }

    public String serverKey() {
        return serverKey;
    }

    public synchronized double ratePerSec() {
        return ratePerSec;
    }

    public synchronized int burst() {
        return burst;
    }

    public synchronized long granted() {
        return granted;
    }

    public synchronized long throttled() {
        return throttled;
    }

    /** Adjusts the shared rate (takes the conservative minimum if tightened, or updates directly). */
    public synchronized void updateLimit(double newRatePerSec, int newBurst) {
        this.ratePerSec = Math.max(SwarmRateLimiter.MIN_RATE, newRatePerSec);
        this.burst = Math.max(1, newBurst);
        if (this.tokens > this.burst) {
            this.tokens = this.burst;
        }
    }

    /** Attempts to consume one shared token at {@code nowMs}. */
    public synchronized boolean tryAcquire(long nowMs) {
        if (nowMs > lastMs) {
            tokens = Math.min(burst, tokens + (nowMs - lastMs) * ratePerSec / 1000.0);
            lastMs = nowMs;
        }
        if (tokens < 1.0) {
            throttled++;
            return false;
        }
        tokens -= 1.0;
        granted++;
        return true;
    }
}
