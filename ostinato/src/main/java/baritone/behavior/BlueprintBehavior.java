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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.Helper;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.host.CenterFeedService;
import baritone.swarm.lease.BotLeaseGuard;
import baritone.swarm.rts.Blueprint;
import baritone.swarm.rts.BlueprintRepairSession;
import baritone.swarm.rts.RoleOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: RTS mode live blueprint watch, state-aware verification, and scoped repair.
 * Watches one active blueprint file written by the command center, compares ONLY active blueprint cells against
 * client-visible world state, reports matched cells and discrepancies (including wrong-state and unsupported
 * explicit properties), and executes bounded, settle-verified ordinary-placement repairs that invalidate
 * stale pending work immediately when a live edit changes or removes a target cell.
 */
public final class BlueprintBehavior extends Behavior implements Helper {

    private static final int CHECK_TICKS = 20;

    private volatile String watching;
    private volatile int[] part; // Ostinato: this bot's share of a split live build (x/z columns), or null
    private volatile long generation;
    private boolean leased;
    private long leaseMissingSince = -1;
    private volatile boolean stopRequested;
    private String lastText;
    private long lastFileMtime = Long.MIN_VALUE;
    private long lastFileSize = -1;
    private Blueprint current;
    private BlueprintRepairSession repairSession;
    private BlueprintRepairSession.Comparison lastComparison;
    private int tick;
    private String note = "";
    private int placed;
    private String lastStatusWritten;
    private long restartedMs;

    public BlueprintBehavior(Baritone baritone) {
        super(baritone);
    }

    public static Path dir() {
        Path game = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        return CenterFeedService.dir(Baritone.settings(), game).resolve("blueprints");
    }

    public void live(String name) {
        live(name, null);
    }

    /**
     * Ostinato: build {@code name} live; with {@code part} (x1,y1,z1,x2,y2,z2) only the cells in those columns. A
     * split build runs under a region lease on {@code part}; once held, losing it stops this bot's share.
     */
    public void live(String name, int[] part) {
        watching = Blueprint.safeName(name);
        this.part = part == null ? null : part.clone();
        lastText = null;
        lastFileMtime = Long.MIN_VALUE;
        lastFileSize = -1;
        current = null;
        repairSession = null;
        lastComparison = null;
        lastStatusWritten = null;
        leased = false;
        leaseMissingSince = -1;
        generation++;
        stopRequested = false;
    }

    /**
     * Compares {@code name} (or the active blueprint when {@code name} is null/blank) against client-visible
     * world state without modifying blocks, writes the status file with discrepancies, and returns a summary.
     */
    public String verify(String name) {
        String targetName = (name == null || name.isBlank()) ? watching : Blueprint.safeName(name);
        if (targetName == null) {
            throw new IllegalArgumentException("no active blueprint; pass blueprint verify <name>");
        }
        if (ctx.world() == null) {
            throw new IllegalArgumentException("world is not loaded");
        }
        Blueprint bp = loadBlueprint(targetName);
        if (part != null && targetName.equals(watching)) {
            bp = bp.part(part);
        }
        BlueprintRepairSession.Comparison cmp = compareWorld(bp);
        lastComparison = cmp;
        placed = cmp.matched().size();
        note = BlueprintRepairSession.summaryNote(cmp, baritone.getBuilderProcess().isActive(), repairSession);
        writeStatusFile(targetName, bp, cmp, note, repairSession);
        StringBuilder sb = new StringBuilder();
        sb.append(targetName).append(" v").append(bp.version()).append(": ")
                .append(cmp.matched().size()).append('/').append(bp.size()).append(" matched");
        if (!cmp.discrepancies().isEmpty()) {
            sb.append(", ").append(cmp.discrepancies().size()).append(" discrepancies (")
                    .append(note).append(')');
            int limit = Math.min(8, cmp.discrepancies().size());
            for (int i = 0; i < limit; i++) {
                Blueprint.Discrepancy d = cmp.discrepancies().get(i);
                sb.append("\n  ").append(d.kind()).append(' ')
                        .append(d.x()).append(',').append(d.y()).append(',').append(d.z())
                        .append(' ').append(d.expected());
                if (!"-".equals(d.actual())) {
                    sb.append(" was ").append(d.actual());
                }
                if (!d.reason().isEmpty()) {
                    sb.append(" (").append(d.reason()).append(')');
                }
            }
            if (cmp.discrepancies().size() > limit) {
                sb.append("\n  ... and ").append(cmp.discrepancies().size() - limit).append(" more");
            }
        }
        return sb.toString();
    }

    /**
     * Starts a scoped, settle-verified repair session for {@code name}.
     * When {@code requestedKeys} is non-null and non-empty, ONLY those cells are targeted; otherwise targets
     * wrong-state cells (when {@code wrongStateOnly} is true) or all actionable discrepancies.
     */
    public String repair(String name, Set<Long> requestedKeys, boolean wrongStateOnly) {
        String targetName = (name == null || name.isBlank()) ? watching : Blueprint.safeName(name);
        if (targetName == null) {
            throw new IllegalArgumentException("no active blueprint; pass blueprint repair <name>");
        }
        if (ctx.world() == null) {
            throw new IllegalArgumentException("world is not loaded");
        }
        Path file = dir().resolve(Blueprint.fileName(targetName));
        String text = readFileText(file);
        Blueprint bp = Blueprint.read(targetName, text);
        if (part != null && targetName.equals(watching)) {
            bp = bp.part(part);
        } else {
            part = null;
        }
        watching = targetName;
        current = bp;
        lastText = text;
        recordFileStat(file);
        leased = false;
        leaseMissingSince = -1;
        stopRequested = false;
        generation++;

        BlueprintRepairSession.WorldReader reader = worldReader();
        repairSession = new BlueprintRepairSession(bp, requestedKeys, wrongStateOnly, reader,
                BlueprintStateHelper::validateAgainstRegistry,
                BlueprintRepairSession.DEFAULT_SETTLE_TICKS,
                BlueprintRepairSession.DEFAULT_MAX_RETRIES);

        BlueprintRepairSession.Comparison cmp = compareWorld(bp);
        lastComparison = cmp;
        placed = cmp.matched().size();
        note = BlueprintRepairSession.summaryNote(cmp, false, repairSession);
        if (repairSession.isActive()) {
            BlueprintRepairSession.Action act = repairSession.tick(reader, false, false);
            applyRepairAction(bp, act);
        } else if (baritone.getBuilderProcess().isActive()) {
            baritone.getBuilderProcess().onLostControl();
        }
        writeStatusFile(targetName, bp, cmp, note, repairSession);
        return targetName + " v" + bp.version() + ": " + repairSession.statusNote();
    }

    public long generation() {
        return generation;
    }

    public String watchingName() {
        return watching;
    }

    public BlueprintRepairSession repairSession() {
        return repairSession;
    }

    public BlueprintRepairSession.Comparison lastComparison() {
        return lastComparison;
    }

    private String partKey() {
        int[] p = part;
        return p == null ? null : "r:" + baritone.getSwarmBehavior().dimLetter() + ":" + RoleOrder.boxText(p);
    }

    private String statusFile(String name) {
        return part == null ? Blueprint.statusName(name)
                : Blueprint.partStatusName(name, ctx.player() == null ? "bot" : ctx.player().getName().getString());
    }

    public void stop() {
        if (repairSession != null) {
            repairSession.cancel();
        }
        stopRequested = true;
    }

    public String describe() {
        String w = watching;
        if (w == null) {
            return "no live blueprint";
        }
        Blueprint b = current;
        int[] p = part;
        return "live " + w + (p == null ? "" : " part " + RoleOrder.boxText(p)) + (b == null ? " (waiting for " + dir().resolve(Blueprint.fileName(w)) + ")"
                : " v" + b.version() + " " + placed + "/" + b.size() + " placed" + (note.isEmpty() ? "" : ", " + note));
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        if (stopRequested) {
            stopRequested = false;
            if (repairSession != null) {
                repairSession.cancel();
            }
            if (watching != null && baritone.getBuilderProcess().isActive()) {
                baritone.getBuilderProcess().onLostControl();
            }
            if (watching != null && current != null && ctx.world() != null) {
                BlueprintRepairSession.Comparison cmp = compareWorld(current);
                writeStatusFile(watching, current, cmp,
                        repairSession != null ? "repair cancelled" : "stopped", repairSession);
            }
            logDirect("blueprint: stopped " + watching);
            watching = null;
            part = null;
            generation++;
            current = null;
            repairSession = null;
            lastComparison = null;
            lastText = null;
            lastFileMtime = Long.MIN_VALUE;
            lastFileSize = -1;
            lastStatusWritten = null;
            return;
        }
        String name = watching;
        if (name == null || ctx.player() == null || ctx.world() == null) {
            return;
        }
        tick++;
        boolean periodicCheck = (tick % CHECK_TICKS == 0);
        boolean activeRepair = repairSession != null && repairSession.isActive();

        // Keep background work proportional to active blueprints: only do full tick checks during active repair,
        // on periodic 1 s ticks, or when the single active blueprint file's mtime/size changed.
        Path file = dir().resolve(Blueprint.fileName(name));
        boolean fileChanged = hasFileChanged(file);
        if (!activeRepair && !periodicCheck && !fileChanged) {
            return;
        }

        // Ostinato: a split share keeps building only while its region lease is valid.
        if (periodicCheck) {
            String key = partKey();
            if (key != null && Baritone.settings().swarmLeases.value) {
                BotLeaseGuard g = baritone.getSwarmBehavior().leaseGuard();
                if (g != null && g.holds(key)) {
                    if (!leased) {
                        logDirect("blueprint: region lease " + key + " held");
                    }
                    leased = true;
                    leaseMissingSince = -1;
                } else if (leased) {
                    long now = System.currentTimeMillis();
                    if (leaseMissingSince < 0) {
                        leaseMissingSince = now;
                    } else if (now - leaseMissingSince > RoleBehavior.LEASE_GRACE_MS) {
                        logDirect("blueprint: region lease " + key + " ended; stopping this share");
                        stopRequested = true;
                        return;
                    }
                }
            }
        }

        if (fileChanged || current == null) {
            String text;
            try {
                text = Files.isRegularFile(file) ? Files.readString(file) : null;
            } catch (IOException e) {
                return; // the window is mid-rename; next check
            }
            if (text == null) {
                note = "no file yet";
                return;
            }
            recordFileStat(file);
            if (!text.equals(lastText)) {
                lastText = text;
                Blueprint updated;
                try {
                    updated = Blueprint.read(name, text);
                    if (part != null) {
                        updated = updated.part(part);
                    }
                } catch (RuntimeException e) {
                    note = "bad blueprint: " + e.getMessage();
                    logDirect("blueprint: " + note);
                    return;
                }
                current = updated;
                if (repairSession != null && repairSession.isActive()) {
                    // Immediate stale-target invalidation on live edit during repair.
                    boolean invalidated = repairSession.reconcile(updated, BlueprintStateHelper::validateAgainstRegistry);
                    if (invalidated) {
                        logDirect("blueprint: live edit updated repair targets for " + name + " v" + updated.version());
                    }
                } else {
                    repairSession = null;
                    restart(current, null);
                }
            }
        }

        Blueprint b = current;
        if (b == null) {
            return;
        }

        if (repairSession != null) {
            if (!repairSession.isActive()) {
                // Scoped repair already reached a terminal state (SUCCEEDED/PARTIAL/FAILED/CANCELLED):
                // never fall through and auto-restart full live building.
                return;
            }
            boolean building = baritone.getBuilderProcess().isActive();
            boolean paused = building && baritone.getBuilderProcess().isPaused();
            BlueprintRepairSession.Action act = repairSession.tick(worldReader(), building, paused);
            applyRepairAction(b, act);
            if (!repairSession.isActive() || periodicCheck || fileChanged || act.kind() != BlueprintRepairSession.ActionKind.NONE) {
                BlueprintRepairSession.Comparison cmp = compareWorld(b);
                lastComparison = cmp;
                placed = cmp.matched().size();
                note = BlueprintRepairSession.summaryNote(cmp, baritone.getBuilderProcess().isActive(), repairSession);
                writeStatusFile(name, b, cmp, note, repairSession);
                if (!repairSession.isActive()) {
                    logDirect("blueprint: " + name + " " + repairSession.statusNote());
                    generation++;
                }
            }
            return;
        }

        if (!periodicCheck && !fileChanged) {
            return;
        }

        BlueprintRepairSession.Comparison cmp = compareWorld(b);
        lastComparison = cmp;
        boolean building = baritone.getBuilderProcess().isActive();
        placed = cmp.matched().size();
        boolean hasActionable = hasActionableDiscrepancy(cmp);
        if (cmp.allMatched()) {
            note = "done";
        } else if (building) {
            note = BlueprintRepairSession.summaryNote(cmp, true, null);
        } else if (hasActionable && System.currentTimeMillis() - restartedMs > 10_000) {
            // Ostinato: the builder gave up or was taken over (a command, combat); pick the rest up again, at most
            // every 10 s so a build that cannot go on (no blocks) does not restart every second.
            restart(b, null);
            note = "resumed";
        } else {
            note = BlueprintRepairSession.summaryNote(cmp, false, null);
        }
        writeStatusFile(name, b, cmp, note, null);
    }

    private void applyRepairAction(Blueprint b, BlueprintRepairSession.Action act) {
        if (act == null || act.kind() == BlueprintRepairSession.ActionKind.NONE) {
            return;
        }
        if (act.kind() == BlueprintRepairSession.ActionKind.STOP_BUILDER) {
            if (baritone.getBuilderProcess().isActive()) {
                baritone.getBuilderProcess().onLostControl();
            }
            return;
        }
        if (act.kind() == BlueprintRepairSession.ActionKind.START_BUILDER) {
            restart(b, act.targetCells());
        }
    }

    private void restart(Blueprint b, Set<Long> scopeKeys) {
        restartedMs = System.currentTimeMillis();
        if (b.size() == 0 || (scopeKeys != null && scopeKeys.isEmpty())) {
            if (baritone.getBuilderProcess().isActive()) {
                baritone.getBuilderProcess().onLostControl();
            }
            note = b.size() == 0 ? "empty" : "idle";
            return;
        }
        int[] o = b.origin();
        ISchematic schem = BlueprintStateHelper.toSchematic(b, scopeKeys);
        int supportedCount = 0;
        int unsupportedCount = 0;
        for (Blueprint.Cell c : b.cells()) {
            if (scopeKeys != null && !scopeKeys.contains(Blueprint.cellKey(c.x(), c.y(), c.z()))) {
                continue;
            }
            if (BlueprintStateHelper.validateAgainstRegistry(c) != null) {
                unsupportedCount++;
            } else {
                supportedCount++;
            }
        }
        if (supportedCount == 0) {
            if (baritone.getBuilderProcess().isActive()) {
                baritone.getBuilderProcess().onLostControl();
            }
            note = unsupportedCount > 0 ? unsupportedCount + " unsupported" : "empty";
            return;
        }
        String label = (scopeKeys == null ? "blueprint " : "blueprint repair ") + b.name() + " v" + b.version();
        baritone.getBuilderProcess().build(label, schem, new Vec3i(o[0], o[1], o[2]));
        note = unsupportedCount > 0 ? unsupportedCount + " unsupported, building" : "building";
        logDirect("blueprint: " + b.name() + " v" + b.version() + " " + supportedCount
                + (scopeKeys == null ? " blocks" : " repair cells")
                + " at " + o[0] + "," + o[1] + "," + o[2]
                + (unsupportedCount > 0 ? " (" + unsupportedCount + " unsupported skipped)" : ""));
    }

    private BlueprintRepairSession.WorldReader worldReader() {
        return (x, y, z) -> BlueprintStateHelper.observe(ctx.world(), new BlockPos(x, y, z));
    }

    private BlueprintRepairSession.Comparison compareWorld(Blueprint b) {
        return BlueprintRepairSession.compare(b, worldReader(), BlueprintStateHelper::validateAgainstRegistry);
    }

    private static boolean hasActionableDiscrepancy(BlueprintRepairSession.Comparison cmp) {
        for (Blueprint.Discrepancy d : cmp.discrepancies()) {
            if (d.kind() == Blueprint.DiscrepancyKind.WRONG_STATE
                    || d.kind() == Blueprint.DiscrepancyKind.WRONG_BLOCK
                    || d.kind() == Blueprint.DiscrepancyKind.MISSING) {
                return true;
            }
        }
        return false;
    }

    private void writeStatusFile(String name, Blueprint b, BlueprintRepairSession.Comparison cmp,
                                 String statusNote, BlueprintRepairSession repair) {
        List<Blueprint.Discrepancy> allDiffs = mergeDiscrepancies(cmp, repair);
        String payload = Blueprint.writeStatus(b.version(), cmp.matched(), b.size(), statusNote, allDiffs);
        if (payload.equals(lastStatusWritten)) {
            return;
        }
        try {
            CenterFeed.writeAtomic(dir().resolve(statusFile(name)), payload);
            lastStatusWritten = payload;
        } catch (IOException ignored) {
            // next tick
        }
    }

    private static List<Blueprint.Discrepancy> mergeDiscrepancies(BlueprintRepairSession.Comparison cmp,
                                                                  BlueprintRepairSession repair) {
        Map<Long, Blueprint.Discrepancy> map = new LinkedHashMap<>();
        if (cmp != null) {
            for (Blueprint.Discrepancy d : cmp.discrepancies()) {
                map.put(Blueprint.cellKey(d.x(), d.y(), d.z()), d);
            }
        }
        if (repair != null) {
            for (Blueprint.Discrepancy f : repair.failedDiscrepancies()) {
                map.put(Blueprint.cellKey(f.x(), f.y(), f.z()), f);
            }
        }
        return new ArrayList<>(map.values());
    }

    private Blueprint loadBlueprint(String name) {
        Path file = dir().resolve(Blueprint.fileName(name));
        return Blueprint.read(name, readFileText(file));
    }

    private static String readFileText(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("blueprint file not found: " + file);
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("could not read blueprint: " + e.getMessage());
        }
    }

    private boolean hasFileChanged(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return lastFileMtime != Long.MIN_VALUE;
            }
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            return mtime != lastFileMtime || size != lastFileSize;
        } catch (IOException e) {
            return false;
        }
    }

    private void recordFileStat(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                lastFileMtime = Files.getLastModifiedTime(file).toMillis();
                lastFileSize = Files.size(file);
            }
        } catch (IOException ignored) {
        }
    }

    static Block block(String id) {
        return BlueprintStateHelper.lookupBlock(id);
    }
}
