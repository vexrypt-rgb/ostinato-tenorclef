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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 2c part 2. The detail of a container record once a bot has opened it: item counts, not the block id.
 * Canonical form is {@code id.count} pairs sorted by id, joined with commas. An empty container is {@code empty}.
 * A block id such as {@code chest} is not a contents detail.
 */
public final class ContainerDetail {

    private ContainerDetail() {}

    /** Sorted {@code id.count} pairs. Null counts are null. An empty map is {@code empty}. */
    public static String of(Map<String, Integer> counts) {
        if (counts == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            parts.add(ObservationVerifier.shortId(e.getKey()) + "." + e.getValue());
        }
        if (parts.isEmpty()) {
            return "empty";
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    /** True when {@code detail} is {@link #of}, not a block id from a scan that has not opened the container. */
    public static boolean isContents(String detail) {
        if (detail == null) {
            return false;
        }
        if (detail.equals("empty")) {
            return true;
        }
        String[] parts = detail.split(",", -1);
        if (parts.length == 0) {
            return false;
        }
        for (String p : parts) {
            int dot = p.lastIndexOf('.');
            if (dot <= 0 || dot == p.length() - 1) {
                return false;
            }
            for (int i = 0; i < dot; i++) {
                char c = p.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '/' || c == '.' || c == '-')) {
                    return false;
                }
            }
            for (int i = dot + 1; i < p.length(); i++) {
                if (p.charAt(i) < '0' || p.charAt(i) > '9') {
                    return false;
                }
            }
            if (p.charAt(dot + 1) == '0') {
                return false; // a zero count is omitted, not stored
            }
        }
        return true;
    }
}
