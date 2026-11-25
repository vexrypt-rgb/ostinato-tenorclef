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
import baritone.api.utils.Helper;
import baritone.gui.studio.OstinatoSchematic;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.host.CenterFeedService;
import baritone.swarm.rts.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ostinato: RTS mode live build ({@code docs/rts-mode.md} section 4). Watches one blueprint file the command
 * center writes, and on every new version restarts the ordinary builder on it. The builder's own diff against the
 * world skips cells that are already right, so an edit streams into the build. Once a second it writes which cells
 * the world already matches, for the map's ghost. The builder places with the usual controls-only input.
 */
public final class BlueprintBehavior extends Behavior implements Helper {

    private static final int CHECK_TICKS = 20;

    private volatile String watching;
    private volatile boolean stopRequested;
    private String lastText;
    private Blueprint current;
    private int tick;
    private String note = "";
    private int placed;

    public BlueprintBehavior(Baritone baritone) {
        super(baritone);
    }

    public static Path dir() {
        Path game = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        return CenterFeedService.dir(Baritone.settings(), game).resolve("blueprints");
    }

    public void live(String name) {
        watching = Blueprint.safeName(name);
        stopRequested = false;
    }

    public void stop() {
        stopRequested = true;
    }

    public String describe() {
        String w = watching;
        if (w == null) {
            return "no live blueprint";
        }
        Blueprint b = current;
        return "live " + w + (b == null ? " (waiting for " + dir().resolve(Blueprint.fileName(w)) + ")"
                : " v" + b.version() + " " + placed + "/" + b.size() + " placed" + (note.isEmpty() ? "" : ", " + note));
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        if (stopRequested) {
            stopRequested = false;
            if (watching != null && baritone.getBuilderProcess().isActive()) {
                baritone.getBuilderProcess().onLostControl();
            }
            logDirect("blueprint: stopped " + watching);
            watching = null;
            current = null;
            lastText = null;
            return;
        }
        String name = watching;
        if (name == null || ctx.player() == null || ++tick % CHECK_TICKS != 0) {
            return;
        }
        Path file = dir().resolve(Blueprint.fileName(name));
        String text;
        try {
            text = Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (java.io.IOException e) {
            return; // the window is mid-rename; next check
        }
        if (text == null) {
            note = "no file yet";
            return;
        }
        if (!text.equals(lastText)) {
            lastText = text;
            try {
                current = Blueprint.read(name, text);
            } catch (RuntimeException e) {
                note = "bad blueprint: " + e.getMessage();
                logDirect("blueprint: " + note);
                return;
            }
            restart(current);
        }
        Blueprint b = current;
        if (b == null) {
            return;
        }
        List<int[]> done = new ArrayList<>();
        for (Blueprint.Cell c : b.cells()) {
            Block want = block(c.block());
            if (want != null && ctx.world().getBlockState(new BlockPos(c.x(), c.y(), c.z())).getBlock() == want) {
                done.add(new int[]{c.x(), c.y(), c.z()});
            }
        }
        boolean building = baritone.getBuilderProcess().isActive();
        int before = placed;
        placed = done.size();
        if (placed == b.size() && b.size() > 0) {
            note = "done";
        } else if (building) {
            note = "building";
        } else if (b.size() > 0 && System.currentTimeMillis() - restartedMs > 10_000) {
            // Ostinato: the builder gave up or was taken over (a command, combat); pick the rest up again, at most
            // every 10 s so a build that cannot go on (no blocks) does not restart every second.
            restart(b);
            note = "resumed";
        }
        if (placed != before || note.equals("done")) {
            try {
                CenterFeed.writeAtomic(dir().resolve(Blueprint.statusName(name)),
                        Blueprint.writeStatus(b.version(), done, b.size(), note));
            } catch (java.io.IOException ignored) {
                // next second
            }
        }
    }

    private long restartedMs;

    private void restart(Blueprint b) {
        restartedMs = System.currentTimeMillis();
        if (b.size() == 0) {
            if (baritone.getBuilderProcess().isActive()) {
                baritone.getBuilderProcess().onLostControl();
            }
            note = "empty";
            return;
        }
        int[] o = b.origin();
        int sx = 1, sy = 1, sz = 1;
        for (Blueprint.Cell c : b.cells()) {
            sx = Math.max(sx, c.x() - o[0] + 1);
            sy = Math.max(sy, c.y() - o[1] + 1);
            sz = Math.max(sz, c.z() - o[2] + 1);
        }
        BlockState[][][] states = new BlockState[sx][sz][sy];
        int unknown = 0;
        for (Blueprint.Cell c : b.cells()) {
            Block block = block(c.block());
            if (block == null) {
                unknown++;
                continue;
            }
            states[c.x() - o[0]][c.z() - o[2]][c.y() - o[1]] = block.defaultBlockState();
        }
        // Ostinato: OstinatoSchematic leaves empty cells alone (not "want air"), so painting never breaks the world.
        baritone.getBuilderProcess().build("blueprint " + b.name() + " v" + b.version(), new OstinatoSchematic(states),
                new Vec3i(o[0], o[1], o[2]));
        note = unknown > 0 ? unknown + " unknown blocks skipped" : "building";
        logDirect("blueprint: " + b.name() + " v" + b.version() + " " + b.size() + " blocks at " + o[0] + "," + o[1]
                + "," + o[2]);
    }

    static Block block(String id) {
        Optional<? extends net.minecraft.core.Holder.Reference<Block>> h = BuiltInRegistries.BLOCK.get(Identifier.withDefaultNamespace(id));
        return h.map(net.minecraft.core.Holder.Reference::value).orElse(null);
    }
}
