package baritone.swarm.center.host;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.run.RunMode;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.cache.CachedChunk;
import baritone.cache.CachedRegion;
import baritone.cache.CachedWorld;
import baritone.cache.WorldData;
import baritone.gui.hud.PathStatus;
import baritone.pathing.path.PathExecutor;
import baritone.swarm.SwarmSample;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.InventorySummary;
import baritone.swarm.center.feed.TerrainBuilder;
import baritone.swarm.center.feed.TerrainFeed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ostinato: Phase 3 read feed, bot side. Reads this client's state into a {@link BotFeed}. Client thread only, read
 * only: every call here is a getter that the HUD, the status view or {@code #swarm status} already makes. Nothing is
 * changed, so turning the feed on cannot change what the bot does.
 */
final class BotFeedGather {

    private BotFeedGather() {}

    static BotFeed gather(Baritone b, String name, long nowWallMs, long seq, CenterFeedService.SwarmPart swarm) {
        long t0 = System.nanoTime();
        LocalPlayer p = b.getPlayerContext().player();
        BotFeed.Pos pos = null;
        int health = -1, maxHealth = -1, hunger = -1;
        boolean dead = false;
        BotFeed.Inventory inv = BotFeed.Inventory.UNKNOWN;
        if (p != null) {
            pos = new BotFeed.Pos(dim(p.level()), p.getX(), p.getY(), p.getZ(), p.getYRot());
            health = (int) Math.ceil(p.getHealth());
            maxHealth = (int) Math.ceil(p.getMaxHealth());
            hunger = p.getFoodData().getFoodLevel();
            dead = p.isDeadOrDying();
            inv = inventory(p);
        }
        String process = b.getPathingControlManager().mostRecentInControl().map(IBaritoneProcess::displayName)
                .orElse("idle");
        SwarmSample s = swarm.sample() == null ? SwarmSample.UNKNOWN : swarm.sample();
        BotFeed.Perf perf = new BotFeed.Perf(s.pathMs(), s.tpsX10(), s.pingMs(), s.ramUsedMb(), s.ramMaxMb(),
                s.chatUsed(), s.chatCap(), s.dropped(), s.retried());
        BotFeed.Route route = route(b);
        BotFeed.WhyStuck whyStuck = whyStuck(b, nowWallMs);
        List<BotFeed.Danger> dangers = p == null ? List.of() : dangers(p, b);
        List<BotFeed.TaskItem> tasks = tasks();
        BotFeed.Speedrun speedrun = b.getSwarmBehavior() == null ? BotFeed.Speedrun.NONE
                : BotFeed.Speedrun.parseEncoded(b.getSwarmBehavior().latestSpeedrunSplits());
        BotFeed.LinkBudget linkBudget = linkBudget(b, s);
        RunMode.Value modeVal = RunMode.current();
        BotFeed.ViewCap viewCap = new BotFeed.ViewCap(
                baritone.api.headless.ViewCapability.canRenderSwap(modeVal),
                baritone.api.headless.ViewCapability.canStreamView(modeVal));
        long gatherUs = (System.nanoTime() - t0) / 1000;
        return new BotFeed(name, nowWallMs, seq, server(), modeVal.name().toLowerCase(Locale.ROOT),
                swarm.commandHost(), swarm.coordinator(), pos, health, maxHealth, hunger, dead, process, route, inv,
                swarm.executions(), swarm.leases(), perf, swarm.link(), gatherUs,
                b.getRoleBehavior() == null ? "idle" : b.getRoleBehavior().role().id, // Ostinato: RTS mode
                b.getRoleBehavior() == null ? "" : b.getRoleBehavior().describe(),
                whyStuck, dangers, tasks, speedrun, linkBudget, viewCap);
    }

    static BotFeed.WhyStuck whyStuck(Baritone b, long nowWallMs) {
        baritone.pathing.PathFailureLog log = b.getPathingBehavior().failureLog();
        String stall = b.getBuilderProcess() == null ? "" : b.getBuilderProcess().stallDebug;
        if (stall == null) {
            stall = "";
        }
        if (log.totalFailures() <= 0 && stall.isBlank()) {
            return BotFeed.WhyStuck.NONE;
        }
        long ageMs = log.lastFailureMs() < 0 ? -1L : Math.max(0L, nowWallMs - log.lastFailureMs());
        return new BotFeed.WhyStuck(log.lastGoal(), log.totalFailures(), log.lastReason(),
                log.lastTriedNodes(), ageMs, stall);
    }

    static List<BotFeed.Danger> dangers(LocalPlayer p, Baritone b) {
        List<BotFeed.Danger> out = new ArrayList<>();
        try {
            Level level = p.level();
            if (level == null) {
                return out;
            }
            java.util.Set<String> swarmPeers = new java.util.HashSet<>();
            swarmPeers.add(p.getName().getString().toLowerCase(Locale.ROOT));
            if (b.getSwarmBehavior() != null) {
                for (baritone.behavior.SwarmBehavior.PeerView pv : b.getSwarmBehavior().peerViews()) {
                    swarmPeers.add(pv.link().name().toLowerCase(Locale.ROOT));
                }
            }
            net.minecraft.world.phys.AABB box = p.getBoundingBox().inflate(24.0, 12.0, 24.0);
            for (net.minecraft.world.entity.Entity e : level.getEntities(p, box)) {
                if (out.size() >= BotFeed.MAX_DANGERS) {
                    break;
                }
                double dist = p.distanceTo(e);
                if (e instanceof net.minecraft.world.entity.player.Player other) {
                    String otherName = other.getName().getString();
                    if (!swarmPeers.contains(otherName.toLowerCase(Locale.ROOT))) {
                        out.add(new BotFeed.Danger("player", otherName, other.getX(), other.getY(), other.getZ(),
                                dist, dist <= 12.0 ? "danger" : "warn"));
                    }
                } else if (e instanceof net.minecraft.world.entity.monster.Enemy) {
                    String mobName = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                    out.add(new BotFeed.Danger("mob", mobName, e.getX(), e.getY(), e.getZ(),
                            dist, dist <= 8.0 ? "danger" : "warn"));
                }
            }
            // Local lava check within 5 blocks
            BlockPos origin = p.blockPosition();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int dx = -5; dx <= 5 && out.size() < BotFeed.MAX_DANGERS; dx += 2) {
                for (int dy = -2; dy <= 2 && out.size() < BotFeed.MAX_DANGERS; dy++) {
                    for (int dz = -5; dz <= 5 && out.size() < BotFeed.MAX_DANGERS; dz += 2) {
                        cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                        if (level.getBlockState(cursor).is(net.minecraft.world.level.block.Blocks.LAVA)) {
                            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            out.add(new BotFeed.Danger("lava", "lava", cursor.getX() + 0.5, cursor.getY(),
                                    cursor.getZ() + 0.5, d, d <= 3.5 ? "danger" : "warn"));
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    static List<BotFeed.TaskItem> tasks() {
        List<BotFeed.TaskItem> out = new ArrayList<>();
        try {
            baritone.gui.tasks.game.TaskService svc = baritone.gui.tasks.game.TaskService.INSTANCE;
            if (svc != null && svc.list() != null) {
                List<baritone.gui.tasks.TaskStep> steps = svc.list().steps();
                int cur = svc.runner == null ? -1 : svc.runner.index();
                boolean running = svc.runner != null && svc.runner.active();
                for (int i = 0; i < steps.size() && out.size() < BotFeed.MAX_TASKS; i++) {
                    baritone.gui.tasks.TaskStep st = steps.get(i);
                    String state = (running && i == cur) ? "running" : (running && i < cur) ? "done" : "queued";
                    out.add(new BotFeed.TaskItem(i, st.type().name().toLowerCase(Locale.ROOT), st.summary(), state));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static BotFeed.LinkBudget linkBudget(Baritone b, SwarmSample s) {
        if (b.getSwarmBehavior() == null) {
            return BotFeed.LinkBudget.NONE;
        }
        baritone.swarm.transport.ChatBudget cb = b.getSwarmBehavior().chatBudget();
        if (cb != null) {
            String cmd = cb.effectiveTemplate() == null ? "down" : SwarmSample.commandOf(cb.effectiveTemplate());
            return new BotFeed.LinkBudget(cmd, cb.effectiveMaxChars(),
                    (int) Math.round(cb.effectiveRate() * 1000.0), cb.backoffEvents());
        }
        if (s != null && !s.command().isEmpty()) {
            return new BotFeed.LinkBudget(s.command(), b.getSwarmBehavior().resolvedChatMaxChars(), -1, 0);
        }
        return BotFeed.LinkBudget.NONE;
    }

    static char dim(Level level) {
        if (level == null) {
            return '?';
        }
        if (level.dimension() == Level.OVERWORLD) {
            return 'o';
        }
        if (level.dimension() == Level.NETHER) {
            return 'n';
        }
        return level.dimension() == Level.END ? 'e' : '?';
    }

    static String server() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc.getCurrentServer() == null || mc.getCurrentServer().ip == null ? "" : mc.getCurrentServer().ip;
        } catch (RuntimeException e) {
            return "";
        }
    }

    static BotFeed.Inventory inventory(LocalPlayer p) {
        List<InventorySummary.Slot> slots = new ArrayList<>();
        for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
            if (st.isEmpty()) {
                slots.add(null);
                continue;
            }
            slots.add(new InventorySummary.Slot(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getCount(),
                    st.get(DataComponents.FOOD) != null, st.isDamageableItem() ? st.getDamageValue() : 0,
                    st.isDamageableItem() ? st.getMaxDamage() : 0));
        }
        return InventorySummary.of(slots);
    }

    static BotFeed.Route route(Baritone b) {
        PathStatus st = PathStatus.capture(b);
        PathingBehavior pb = b.getPathingBehavior();
        Goal goal = pb.getGoal();
        int[] goalPos = null;
        if (goal instanceof IGoalRenderPos g) {
            BlockPos gp = g.getGoalPos();
            goalPos = new int[]{gp.getX(), gp.getY(), gp.getZ()};
        }
        List<int[]> nodes = new ArrayList<>();
        PathExecutor cur = pb.getCurrent();
        if (cur != null) {
            List<BetterBlockPos> all = cur.getPath().positions();
            for (int i = Math.max(0, cur.getPosition()); i < all.size() && nodes.size() < BotFeed.MAX_NODES; i++) {
                BetterBlockPos n = all.get(i);
                nodes.add(new int[]{n.x, n.y, n.z});
            }
        }
        return new BotFeed.Route(st.state.name().toLowerCase(Locale.ROOT), goal == null ? "" : goal.toString(),
                goalPos, Double.isNaN(st.distance) ? -1 : st.distance, st.position, st.length, nodes);
    }

    /**
     * The terrain slice around (x, z) from this bot's own cache. Runs on the feed writer thread: the cache's regions
     * are read the same unsynchronized way the path threads read them, and nothing is loaded or written.
     */
    static TerrainFeed terrain(String name, long nowWallMs, long seq, char dim, int x, int z, WorldData world) {
        CachedWorld cache = world.cache;
        java.util.Map<Long, Object[]> memo = new java.util.HashMap<>(); // chunk key -> {CachedChunk, minY}
        return TerrainBuilder.around(name, nowWallMs, seq, dim, x, z, new TerrainBuilder.Columns() {
            private Object[] chunk(int bx, int bz) {
                long key = ((long) (bx >> 4) << 32) ^ ((bz >> 4) & 0xFFFFFFFFL);
                return memo.computeIfAbsent(key, k -> {
                    CachedRegion r = cache.getRegion(bx >> 9, bz >> 9);
                    CachedChunk c = r == null ? null : r.chunkAt((bx >> 4) & 31, (bz >> 4) & 31);
                    return new Object[]{c, r == null ? 0 : r.minY()};
                });
            }

            @Override
            public String block(int bx, int bz) {
                CachedChunk c = (CachedChunk) chunk(bx, bz)[0];
                if (c == null) {
                    return null;
                }
                BlockState s = c.getOverview()[(bz & 15) << 4 | (bx & 15)];
                return s == null ? null : BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            }

            @Override
            public int y(int bx, int bz) {
                Object[] e = chunk(bx, bz);
                CachedChunk c = (CachedChunk) e[0];
                return c == null ? TerrainFeed.UNKNOWN_Y : c.surfaceY(bx & 15, bz & 15) + (int) e[1];
            }
        });
    }
}
