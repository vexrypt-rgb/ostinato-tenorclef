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

package baritone.swarm.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ostinato: Phase 2c part 2. Greedy assignment of veins to bots by
 * {@code C(b,t) = travel + work + risk + switch}. Travel is manhattan blocks, work is two per ore block, and risk
 * and switch are penalties the caller supplies in the same units. Each bot and each vein is used at most once.
 * Ties break by bot name, then by vein key, so the same inputs always give the same owners.
 */
public final class GatherAssign {

    /** Penalty for handing a vein to a bot that is already doing something else. */
    public static final long SWITCH = 10_000L;

    public static final class Bot {
        public final String name;
        public final int x, y, z;
        public final long risk;
        public final long sw;

        public Bot(String name, int x, int y, int z, long risk, long sw) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.risk = risk;
            this.sw = sw;
        }
    }

    public static final class Vein {
        public final String key;
        public final int x, y, z;
        public final int blocks;

        public Vein(String key, int x, int y, int z, int blocks) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blocks = blocks;
        }
    }

    public static final class Assignment {
        public final String bot;
        public final String key;
        public final long cost;

        public Assignment(String bot, String key, long cost) {
            this.bot = bot;
            this.key = key;
            this.cost = cost;
        }
    }

    /** A placed peer, or the coordinator standing on this client. {@code pos} is {@code x,y,z}. */
    public static final class Named {
        public final String name;
        public final String pos;
        public final String proc;

        public Named(String name, String pos, String proc) {
            this.name = name;
            this.pos = pos;
            this.proc = proc;
        }
    }

    private GatherAssign() {}

    /**
     * Remote peers that have a position, plus the local bot when it has one. A name already listed is not added again.
     * A bot whose process is not idle pays {@link #SWITCH}.
     */
    public static List<Bot> candidates(List<Named> peers, Named local) {
        List<Bot> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (peers != null) {
            for (Named n : peers) {
                addCandidate(out, seen, n);
            }
        }
        addCandidate(out, seen, local);
        return out;
    }

    private static void addCandidate(List<Bot> out, Set<String> seen, Named n) {
        if (n == null || n.name == null || n.name.isEmpty() || "?".equals(n.name)) {
            return;
        }
        if (!seen.add(n.name.toLowerCase(Locale.ROOT))) {
            return;
        }
        int[] at = parsePos(n.pos);
        if (at == null) {
            return;
        }
        long sw = n.proc != null && !"idle".equalsIgnoreCase(n.proc) ? SWITCH : 0L;
        out.add(new Bot(n.name, at[0], at[1], at[2], 0L, sw));
    }

    /** {@code x,y,z}, or null when it is missing or not three integers. */
    public static int[] parsePos(String pos) {
        if (pos == null) {
            return null;
        }
        String[] f = pos.split(",", -1);
        if (f.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static long cost(Bot b, Vein v) {
        long travel = Math.abs((long) b.x - v.x) + Math.abs((long) b.y - v.y) + Math.abs((long) b.z - v.z);
        long work = Math.max(0, v.blocks) * 2L;
        return travel + work + b.risk + b.sw;
    }

    /** At most {@code n} pairs. A bot or a vein already chosen is not chosen again. */
    public static List<Assignment> assign(List<Bot> bots, List<Vein> veins, int n) {
        List<Assignment> out = new ArrayList<>();
        if (n <= 0 || bots == null || veins == null || bots.isEmpty() || veins.isEmpty()) {
            return out;
        }
        List<Bot> leftB = new ArrayList<>(bots);
        List<Vein> leftV = new ArrayList<>(veins);
        while (out.size() < n && !leftB.isEmpty() && !leftV.isEmpty()) {
            Bot bestB = null;
            Vein bestV = null;
            long best = Long.MAX_VALUE;
            for (Bot b : leftB) {
                for (Vein v : leftV) {
                    long c = cost(b, v);
                    if (better(c, b, v, best, bestB, bestV)) {
                        best = c;
                        bestB = b;
                        bestV = v;
                    }
                }
            }
            out.add(new Assignment(bestB.name, bestV.key, best));
            leftB.remove(bestB);
            leftV.remove(bestV);
        }
        return out;
    }

    private static boolean better(long c, Bot b, Vein v, long best, Bot bestB, Vein bestV) {
        if (bestB == null || c < best) {
            return true;
        }
        if (c > best) {
            return false;
        }
        int byName = b.name.compareTo(bestB.name);
        if (byName != 0) {
            return byName < 0;
        }
        return v.key.compareTo(bestV.key) < 0;
    }
}
