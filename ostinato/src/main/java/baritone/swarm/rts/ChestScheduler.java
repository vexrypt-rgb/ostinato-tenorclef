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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ostinato: RTS mode chest leases ({@code docs/rts-mode.md} section 2). Runs on the lease coordinator. Bots whose
 * role reached a deposit under a region lease publish {@code wait-chest=c:<dim>:x,y,z} in their feed's role state;
 * this picks, per chest, the bot that has waited longest and asks for one chest lease at a time. The coordinator
 * itself refuses a busy chest, so this only keeps it from offering the same chest twice while an offer is in flight.
 * Pure: no game or swarm types.
 */
public final class ChestScheduler {

    /** An offer not yet visible as a busy lease is not repeated for this long. */
    public static final long OFFER_HOLD_MS = 15_000;

    private static final Pattern WAIT = Pattern.compile("wait-chest=(c:[a-z?]:-?\\d+,-?\\d+,-?\\d+)");

    public record Want(String bot, String chestKey) {}

    public record Grant(String bot, String chestKey) {}

    private final Map<String, String> offeredTo = new HashMap<>();
    private final Map<String, Long> offeredAt = new HashMap<>();
    private final Map<String, Long> firstSeen = new HashMap<>(); // bot|chest -> when its wait was first seen

    /** The chest a role state is waiting for, or null. */
    public static String waitingFor(String roleState) {
        if (roleState == null) {
            return null;
        }
        Matcher m = WAIT.matcher(roleState);
        return m.find() ? m.group(1) : null;
    }

    /**
     * One pass. {@code wants} in any order; {@code busy} says whether the coordinator holds a live lease on a chest.
     * Returns at most one grant per chest, oldest waiter first (ties: name order).
     */
    public List<Grant> next(List<Want> wants, Predicate<String> busy, long now) {
        Map<String, List<Want>> byChest = new LinkedHashMap<>();
        java.util.Set<String> live = new java.util.HashSet<>();
        for (Want w : wants) {
            String k = w.bot() + "|" + w.chestKey();
            live.add(k);
            firstSeen.putIfAbsent(k, now);
            byChest.computeIfAbsent(w.chestKey(), c -> new ArrayList<>()).add(w);
        }
        firstSeen.keySet().retainAll(live);
        List<Grant> out = new ArrayList<>();
        for (Map.Entry<String, List<Want>> e : byChest.entrySet()) {
            String chest = e.getKey();
            if (busy.test(chest)) {
                offeredTo.remove(chest); // the lease is live now; the hold is no longer needed
                offeredAt.remove(chest);
                continue;
            }
            Long at = offeredAt.get(chest);
            if (at != null && now - at < OFFER_HOLD_MS) {
                continue;
            }
            Want pick = null;
            for (Want w : e.getValue()) {
                if (pick == null) {
                    pick = w;
                    continue;
                }
                long a = firstSeen.get(w.bot() + "|" + chest), b = firstSeen.get(pick.bot() + "|" + chest);
                if (a < b || (a == b && w.bot().compareTo(pick.bot()) < 0)) {
                    pick = w;
                }
            }
            offeredTo.put(chest, pick.bot());
            offeredAt.put(chest, now);
            out.add(new Grant(pick.bot(), chest));
        }
        return out;
    }
}
