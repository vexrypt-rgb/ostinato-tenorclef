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

package baritone.swarm.rts;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Ostinato: cooperative container lease table keyed by canonical RTS chest keys ({@code c:<dim>:x,y,z}).
 * Pure JDK (no Minecraft imports) so it compiles into {@code center-app} and unit tests as well as the mod jar.
 *
 * <p>Cooperative leases prevent two local bot jobs from fighting over the same container at once, but cannot
 * stop human players from editing a chest — callers must always re-verify live counts at the open screen.
 */
public final class ChestLeaseTable {

    public static final long MIN_TTL_MS = 1_000L;
    public static final long DEFAULT_TTL_MS = 30_000L;

    private record Entry(String owner, long expiresAtMs) {
    }

    private final Map<String, Entry> leases = new HashMap<>();

    /** Canonical chest key matching {@link RoleOrder#chestKey(char)} and {@link ChestScheduler}. */
    public static String chestKey(char dim, int x, int y, int z) {
        return "c:" + dim + ":" + x + "," + y + "," + z;
    }

    /**
     * Attempts to acquire or renew a cooperative lease on {@code chestKey} for {@code owner}.
     * Returns {@code true} if the chest is free, expired, or already leased by {@code owner};
     * returns {@code false} if actively held by another owner.
     */
    public synchronized boolean tryAcquire(String chestKey, String owner, long nowMs, long ttlMs) {
        if (chestKey == null || chestKey.isBlank() || owner == null || owner.isBlank()) {
            return false;
        }
        pruneExpired(nowMs);
        long boundedTtl = Math.max(MIN_TTL_MS, ttlMs);
        Entry existing = leases.get(chestKey);
        if (existing != null && !existing.owner().equals(owner)) {
            return false;
        }
        leases.put(chestKey, new Entry(owner, nowMs + boundedTtl));
        return true;
    }

    /** Releases {@code chestKey} only if currently held by {@code owner}. Returns {@code true} if released. */
    public synchronized boolean release(String chestKey, String owner) {
        if (chestKey == null || owner == null) {
            return false;
        }
        Entry existing = leases.get(chestKey);
        if (existing != null && existing.owner().equals(owner)) {
            leases.remove(chestKey);
            return true;
        }
        return false;
    }

    /** Releases every active lease held by {@code owner}. Returns the number of leases released. */
    public synchronized int releaseAll(String owner) {
        if (owner == null) {
            return 0;
        }
        int removed = 0;
        Iterator<Map.Entry<String, Entry>> it = leases.entrySet().iterator();
        while (it.hasNext()) {
            if (Objects.equals(it.next().getValue().owner(), owner)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Current non-expired holder of {@code chestKey}, or {@code null} if free. */
    public synchronized String holder(String chestKey, long nowMs) {
        if (chestKey == null) {
            return null;
        }
        pruneExpired(nowMs);
        Entry e = leases.get(chestKey);
        return e == null ? null : e.owner();
    }

    /** True if {@code owner} currently holds a valid non-expired lease on {@code chestKey}. */
    public synchronized boolean holds(String chestKey, String owner, long nowMs) {
        if (owner == null) {
            return false;
        }
        return owner.equals(holder(chestKey, nowMs));
    }

    /** Count of currently active (non-expired) leases. */
    public synchronized int activeCount(long nowMs) {
        pruneExpired(nowMs);
        return leases.size();
    }

    private void pruneExpired(long nowMs) {
        leases.entrySet().removeIf(e -> nowMs >= e.getValue().expiresAtMs());
    }
}
