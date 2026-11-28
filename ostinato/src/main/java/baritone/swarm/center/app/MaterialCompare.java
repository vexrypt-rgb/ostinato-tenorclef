package baritone.swarm.center.app;

import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.rts.Blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ostinato: P3 Schematic Editor Bill of Materials compared against swarm bot inventories and observed chests
 * (design doc: Schematic editor — material list against swarm inventory and stash). Pure, JDK-only.
 */
public final class MaterialCompare {

    private static final Pattern CHEST_ITEM = Pattern.compile("([a-z0-9_]+)\\s*[x×:=]\\s*(\\d+)|(\\d+)\\s*[x×]?\\s+([a-z0-9_]+)");

    public record Row(String block, int needed, int placed, int remaining, int inBots, int inChests, int missing) {
        public int available() {
            return inBots + inChests;
        }

        public boolean ready() {
            return missing <= 0;
        }
    }

    public record Report(List<Row> rows, int totalBlocks, int totalPlaced, int totalRemaining,
                         int totalAvailable, int totalMissing) {
        public boolean allReady() {
            return totalMissing <= 0 && totalBlocks > 0;
        }
    }

    private MaterialCompare() {}

    /** Compares {@code bp} (minus already-placed cells) against bot inventories and confirmed/stale chest observations. */
    public static Report compare(Blueprint bp, Set<Long> placed, SwarmView view) {
        if (bp == null || bp.size() == 0) {
            return new Report(List.of(), 0, 0, 0, 0, 0);
        }
        Map<String, Integer> needMap = new LinkedHashMap<>();
        Map<String, Integer> placedMap = new LinkedHashMap<>();
        for (Blueprint.Cell c : bp.cells()) {
            String id = normalize(c.block());
            needMap.merge(id, 1, Integer::sum);
            if (placed != null && placed.contains(Blueprint.cellKey(c.x(), c.y(), c.z()))) {
                placedMap.merge(id, 1, Integer::sum);
            }
        }

        Map<String, Integer> botStock = new LinkedHashMap<>();
        Map<String, Integer> chestStock = new LinkedHashMap<>();
        if (view != null) {
            for (SwarmView.BotState b : view.bots()) {
                BotFeed.Inventory inv = b.feed().inventory();
                if (inv == null) continue;
                for (BotFeed.Item it : inv.counts()) {
                    String id = normalize(it.id());
                    if (!id.isEmpty() && it.count() > 0) {
                        botStock.merge(id, it.count(), Integer::sum);
                    }
                }
            }
            CoordinatorFeed coord = view.coordinator();
            if (coord != null) {
                for (CoordinatorFeed.Observation o : coord.observations()) {
                    if (!"chest".equalsIgnoreCase(o.kind()) || "gone".equalsIgnoreCase(o.state())) {
                        continue;
                    }
                    parseChestDetail(o.detail(), chestStock);
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        int totalBlocks = 0, totalPlaced = 0, totalRemaining = 0, totalAvail = 0, totalMissing = 0;
        for (Map.Entry<String, Integer> e : needMap.entrySet()) {
            String blk = e.getKey();
            int need = e.getValue();
            int done = placedMap.getOrDefault(blk, 0);
            int rem = Math.max(0, need - done);
            int haveBots = botStock.getOrDefault(blk, 0);
            int haveChests = chestStock.getOrDefault(blk, 0);
            int avail = haveBots + haveChests;
            int miss = Math.max(0, rem - avail);
            rows.add(new Row(blk, need, done, rem, haveBots, haveChests, miss));
            totalBlocks += need;
            totalPlaced += done;
            totalRemaining += rem;
            totalAvail += Math.min(rem, avail);
            totalMissing += miss;
        }
        // Sort most-missing first, then highest remaining, then alphabetically
        rows.sort(Comparator.comparingInt(Row::missing).reversed()
                .thenComparing(Comparator.comparingInt(Row::remaining).reversed())
                .thenComparing(Row::block));
        return new Report(rows, totalBlocks, totalPlaced, totalRemaining, totalAvail, totalMissing);
    }

    /** Parses chest observation detail strings like {@code "cobblestone:128, oak_planks=64"} or {@code "32 glass"}. */
    static void parseChestDetail(String detail, Map<String, Integer> out) {
        if (detail == null || detail.isBlank()) {
            return;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        Matcher m = CHEST_ITEM.matcher(lower);
        while (m.find()) {
            String id;
            int count;
            if (m.group(1) != null) {
                id = normalize(m.group(1));
                count = Integer.parseInt(m.group(2));
            } else {
                count = Integer.parseInt(m.group(3));
                id = normalize(m.group(4));
            }
            if (!id.isEmpty() && count > 0) {
                out.merge(id, count, Integer::sum);
            }
        }
    }

    private static String normalize(String id) {
        if (id == null) return "";
        String t = id.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("minecraft:") ? t.substring("minecraft:".length()) : t;
    }
}
