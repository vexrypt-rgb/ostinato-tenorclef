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

import baritone.swarm.center.feed.FeedReader;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.lease.BotLeaseGuard;
import baritone.swarm.lease.LeaseCoordinator;
import baritone.swarm.lease.LeaseFence;
import baritone.swarm.rts.ChestScheduler;
import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.process.ProcessOutcome;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.swarm.rts.Role;
import baritone.swarm.rts.RoleItems;
import baritone.swarm.rts.RoleLoop;
import baritone.swarm.rts.RoleOrder;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ostinato: RTS mode roles ({@code docs/rts-mode.md}). The pure {@link RoleLoop} decides; this class only starts
 * and stops existing processes (farm, mine) and runs the one new step, a chest deposit, as a process of its own.
 *
 * <p>Controls only: the deposit walks with the normal path executor, turns with the normal look step, opens the
 * chest with the use key, and moves items with {@code QUICK_MOVE} clicks on the open vanilla chest screen while no
 * movement, attack or use key is held. Nothing here builds a packet.
 */
public final class RoleBehavior extends Behavior implements Helper {

    private volatile RoleOrder pending;
    private RoleLoop loop;
    private final Deposit deposit;
    private int lastFreeSlots = -1;
    private int lastOutput;
    private int deposited;
    // Ostinato: RTS leases (docs/rts-mode.md section 2). A role given region= runs under that region lease once the
    // bot has held it; its deposits then wait for a chest lease the coordinator's scheduler grants one bot at a time.
    private boolean leased;
    private long regionMissingSince = -1;
    private String awaitChest;
    private long awaitSince;
    private volatile long generation;
    private volatile int depositSerial;
    private volatile boolean lastDepositOk;
    public static final long CHEST_WAIT_MS = 120_000;
    public static final long LEASE_GRACE_MS = 3_000;

    public RoleBehavior(Baritone baritone) {
        super(baritone);
        deposit = new Deposit(baritone);
    }

    /** Registered with the pathing control manager by {@link Baritone}. */
    public IBaritoneProcess process() {
        return deposit;
    }

    /** From {@code #role}. Thread-safe: it takes effect on the next client tick. */
    public void assign(RoleOrder order) {
        pending = order;
    }

    public Role role() {
        RoleLoop l = loop;
        return l == null ? Role.IDLE : l.order().role;
    }

    /** One line for {@code #role status}, the feed and the tile. */
    public String describe() {
        RoleLoop l = loop;
        if (l == null || l.order().role == Role.IDLE) {
            return "idle";
        }
        String chestWait = awaitChest == null ? "" : " wait-chest=" + awaitChest;
        return l.order().role.id + " " + l.phase().name().toLowerCase() + (l.note().isEmpty() ? "" : " (" + l.note() + ")")
                + chestWait + (leased ? " leased" : "")
                + " deposits=" + l.deposits() + " stored=" + deposited + " free=" + lastFreeSlots + " output=" + lastOutput;
    }

    public String phase() {
        RoleLoop l = loop;
        return l == null ? "idle" : l.phase().name().toLowerCase();
    }

    /** Ostinato: bumped on every assignment; the swarm executor ties a {@code role} command to one generation. */
    public long generation() {
        return generation;
    }

    /** Ostinato: deposits finished (done or failed) so far, and whether the last one stored its items. */
    public int depositSerial() {
        return depositSerial;
    }

    public boolean lastDepositOk() {
        return lastDepositOk;
    }

    /** Ostinato: true while a deposit is walking/clicking or waiting for its chest lease. */
    public boolean depositPending() {
        return deposit.isActive() || awaitChest != null;
    }

    /** Ostinato: an authorised lease on exactly this reservation key, from the swarm lease guard. */
    private boolean holds(String key) {
        try {
            BotLeaseGuard g = baritone.getSwarmBehavior().leaseGuard();
            return key != null && g != null && g.holds(key);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private char dim() {
        return baritone.getSwarmBehavior().dimLetter();
    }

    public String orderLine() {
        RoleLoop l = loop;
        return l == null ? "role idle" : l.order().line();
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        if (++schedTick % 20 == 0) {
            scheduleChests();
        }
        RoleOrder next = pending;
        if (next != null) {
            pending = null;
            stopWork();
            deposit.cancel();
            loop = next.role == Role.IDLE ? null : new RoleLoop(next);
            deposited = 0; // Ostinato: "stored so far" counts this assignment only
            leased = false;
            regionMissingSince = -1;
            awaitChest = null;
            generation++;
            logDirect("role: " + next.line());
        }
        RoleLoop l = loop;
        LocalPlayer p = ctx.player();
        if (l == null || p == null) {
            return;
        }
        RoleOrder o = l.order();
        // Ostinato: a region role keeps working only while its region lease is valid (revoked or expired: idle).
        if (o.region != null && Baritone.settings().swarmLeases.value) {
            if (holds(o.regionKey(dim()))) {
                if (!leased) {
                    logDirect("role " + o.role.id + ": region lease " + o.regionKey(dim()) + " held");
                }
                leased = true;
                regionMissingSince = -1;
            } else if (leased) {
                long now = System.currentTimeMillis();
                if (regionMissingSince < 0) {
                    regionMissingSince = now;
                } else if (now - regionMissingSince > LEASE_GRACE_MS) {
                    logDirect("role " + o.role.id + ": region lease ended; idle");
                    stopWork();
                    deposit.cancel();
                    awaitChest = null;
                    loop = null;
                    generation++;
                    return;
                }
            }
        }
        // Ostinato: a leased deposit starts only once this bot holds the chest lease.
        if (awaitChest != null) {
            if (holds(awaitChest)) {
                logDirect("role deposit: chest lease " + awaitChest + " held; depositing");
                awaitChest = null;
                deposit.start(new BlockPos(o.chest[0], o.chest[1], o.chest[2]), o);
            } else if (System.currentTimeMillis() - awaitSince > CHEST_WAIT_MS) {
                logDirect("role deposit: no chest lease for " + awaitChest + " in " + CHEST_WAIT_MS / 1000 + " s");
                awaitChest = null;
                deposit.failLater("no chest lease");
            }
        }
        int free = 0;
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
            if (st.isEmpty()) {
                free++;
            } else {
                stacks.add(st);
            }
        }
        // Ostinato: count only what a deposit would really move, so kept seeds never trigger another deposit.
        int output = 0;
        for (ItemStack st : movable(stacks, o)) {
            output += st.getCount();
        }
        lastFreeSlots = free;
        lastOutput = output;
        IBaritoneProcess work = workProcess(o.role);
        boolean workActive = work != null && work.isActive();
        ProcessOutcome wo = work == null ? ProcessOutcome.NONE : work.outcome();
        boolean workFailed = !workActive && wo.state() == ProcessOutcome.State.FAILED && workRequest >= 0
                && wo.request() == workRequest;
        boolean depDone = deposit.takeDone();
        String depFail = deposit.takeFailure();
        if (depDone || depFail != null) {
            lastDepositOk = depDone && depFail == null;
            depositSerial++;
        }
        RoleLoop.Step step = l.step(new RoleLoop.Inputs(System.currentTimeMillis(), free, output, workActive,
                workFailed, depositPending(), depDone, depFail));
        if (!step.start()) {
            return;
        }
        logDirect("role " + o.role.id + ": " + step.phase().name().toLowerCase() + " - " + step.note());
        switch (step.phase()) {
            case WORK -> startWork(o);
            case DEPOSIT -> {
                stopWork();
                if (leased && o.chest != null) {
                    // Ostinato: one bot per chest: ask the coordinator's scheduler (it reads this feed) and wait.
                    awaitChest = o.chestKey(dim());
                    awaitSince = System.currentTimeMillis();
                    logDirect("role deposit: waiting for chest lease " + awaitChest);
                } else {
                    deposit.start(new BlockPos(o.chest[0], o.chest[1], o.chest[2]), o);
                }
            }
            case WAIT -> stopWork();
            default -> {
            }
        }
    }

    private long workRequest = -1;
    private int schedTick;
    private final ChestScheduler chests = new ChestScheduler();

    /**
     * Ostinato: on the active lease coordinator only, once a second: read the same-PC feeds, and lease each waited-for
     * chest to one waiting bot, bound to {@code role deposit}. The coordinator refuses a chest that is still leased.
     */
    private void scheduleChests() {
        LeaseCoordinator c;
        try {
            c = baritone.getSwarmBehavior().leaseCoordinatorOrNull();
        } catch (RuntimeException e) {
            return;
        }
        if (c == null || c.mode() != LeaseCoordinator.Mode.ACTIVE) {
            return;
        }
        java.nio.file.Path feeds = BlueprintBehavior.dir().getParent();
        java.util.List<ChestScheduler.Want> wants = new java.util.ArrayList<>();
        try {
            SwarmView v =
                    new FeedReader(feeds, System::currentTimeMillis).snapshot();
            for (SwarmView.BotState b : v.bots()) {
                String chest = ChestScheduler.waitingFor(b.feed().roleState());
                if (chest != null && b.freshness() == SwarmView.Freshness.FRESH) {
                    wants.add(new ChestScheduler.Want(b.name(), chest));
                }
            }
        } catch (RuntimeException e) {
            return;
        }
        if (wants.isEmpty()) {
            return;
        }
        for (ChestScheduler.Grant g : chests.next(wants, k -> c.busyReason(k) != null,
                System.currentTimeMillis())) {
            String r = c.lease(g.bot(), g.chestKey(), "role deposit");
            logDirect("role scheduler: chest " + g.chestKey() + " -> " + g.bot() + ": " + r);
        }
    }

    private IBaritoneProcess workProcess(Role role) {
        return switch (role) {
            case FARMER -> baritone.getFarmProcess();
            case MINER -> baritone.getMineProcess();
            default -> null;
        };
    }

    private void startWork(RoleOrder o) {
        switch (o.role) {
            case FARMER -> {
                BlockPos at = o.at == null ? null : new BlockPos(o.at[0], o.at[1], o.at[2]);
                int radius = o.radius;
                if (o.region != null) { // Ostinato: a region farmer works its box, centred on it
                    int[] r = o.region;
                    at = new BlockPos((r[0] + r[3]) / 2, (r[1] + r[4]) / 2, (r[2] + r[5]) / 2);
                    radius = Math.max(r[3] - r[0], Math.max(r[4] - r[1], r[5] - r[2])) + 1;
                }
                baritone.getFarmProcess().farm(radius, at);
                if (o.region != null) {
                    baritone.getFarmProcess().farmBox(o.region);
                }
                workRequest = baritone.getFarmProcess().outcome().request();
            }
            case MINER -> {
                // Ostinato: mine until a batch is in the inventory; the loop deposits and starts the next batch.
                baritone.getMineProcess().mine(o.batch, new BlockOptionalMetaLookup(o.ores.toArray(new String[0])));
                workRequest = baritone.getMineProcess().outcome().request();
            }
            default -> logDirect("role " + o.role.id + ": no work loop on this build yet");
        }
    }

    private void stopWork() {
        RoleLoop l = loop;
        if (l == null) {
            return;
        }
        IBaritoneProcess work = workProcess(l.order().role);
        if (work != null && work.isActive()) {
            work.onLostControl();
        }
        workRequest = -1;
    }

    /**
     * The stacks a deposit moves, whole stacks only (a quick-move takes the stack): output items, leaving at least
     * the role's keep count of each id behind. The same rule drives the loop's output count and the clicks.
     */
    static java.util.List<ItemStack> movable(java.util.List<ItemStack> stacks, RoleOrder o) {
        Map<String, Integer> left = new HashMap<>();
        for (ItemStack st : stacks) {
            left.merge(id(st), st.getCount(), Integer::sum);
        }
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (ItemStack st : stacks) {
            String item = id(st);
            if (!RoleItems.isOutput(o.role, item)) {
                continue;
            }
            int remaining = left.get(item);
            if (remaining - st.getCount() < RoleItems.keep(o.role, item, o)) {
                continue;
            }
            left.put(item, remaining - st.getCount());
            out.add(st);
        }
        return out;
    }

    static String id(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
    }

    /**
     * Ostinato: walk to one chest, open it with the use key, quick-move the role's output into it, close it. Active
     * only while a deposit is running; above the farm and mine processes so it gets the path.
     */
    final class Deposit extends BaritoneProcessHelper {

        private static final int OPEN_TICKS = 40;
        private static final long WALK_MS = 90_000;

        private enum Stage { WALK, OPEN, MOVE, CLOSE }

        private BlockPos chest;
        private RoleOrder order;
        private Stage stage;
        private long startedMs;
        private int ticks;
        private int calcFails;
        private int lastSlot = -1;
        private int lastCount;
        private volatile boolean done;
        private volatile String failure;

        Deposit(Baritone baritone) {
            super(baritone);
        }

        void start(BlockPos chest, RoleOrder order) {
            this.chest = chest;
            this.order = order;
            stage = Stage.WALK;
            startedMs = System.currentTimeMillis();
            ticks = 0;
            calcFails = 0;
            lastSlot = -1;
            done = false;
            failure = null;
        }

        void cancel() {
            if (stage != null && ctx.player() != null && ctx.player().containerMenu instanceof ChestMenu) {
                ctx.player().closeContainer();
            }
            stage = null;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        }

        boolean takeDone() {
            boolean d = done;
            done = false;
            return d;
        }

        /** Ostinato: report a failure without having started (no chest lease). */
        void failLater(String why) {
            failure = why;
        }

        String takeFailure() {
            String f = failure;
            failure = null;
            return f;
        }

        private PathingCommand fail(String why) {
            cancel();
            failure = why;
            logDirect("role deposit: " + why);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        @Override
        public boolean isActive() {
            return stage != null;
        }

        @Override
        public double priority() {
            return 2;
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            LocalPlayer p = ctx.player();
            if (p == null) {
                return fail("no player");
            }
            PathingCommand pause = new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            switch (stage) {
                case WALK -> {
                    Optional<Rotation> rot = RotationUtils.reachable(ctx, chest, ctx.playerController().getBlockReachDistance());
                    if (rot.isPresent() && ctx.player().onGround()) {
                        stage = Stage.OPEN;
                        ticks = 0;
                        return pause;
                    }
                    if (calcFailed && ++calcFails > 5) {
                        return fail("chest " + chest.toShortString() + " unreachable");
                    }
                    if (System.currentTimeMillis() - startedMs > WALK_MS) {
                        return fail("walk to chest timed out");
                    }
                    return new PathingCommand(new GoalGetToBlock(chest), PathingCommandType.SET_GOAL_AND_PATH);
                }
                case OPEN -> {
                    if (p.containerMenu instanceof ChestMenu) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                        stage = Stage.MOVE;
                        ticks = 0;
                        return pause;
                    }
                    if (++ticks > OPEN_TICKS) {
                        // Ostinato: say what the crosshair saw, so a miss can be told from a fenced use
                        return fail("chest did not open (crosshair " + ctx.getSelectedBlock().map(BlockPos::toShortString).orElse("none")
                                + ", menu " + p.containerMenu.getClass().getSimpleName() + ", dist "
                                + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(p.distanceToSqr(chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5)))
                                + ", fence denials " + LeaseFence.denials() + ")");
                    }
                    Optional<Rotation> rot = RotationUtils.reachable(ctx, chest, ctx.playerController().getBlockReachDistance());
                    if (rot.isEmpty()) {
                        stage = Stage.WALK;
                        return pause;
                    }
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    // Ostinato: press use only once the crosshair is on the chest; vanilla sends the use packet.
                    boolean onChest = chest.equals(ctx.getSelectedBlock().orElse(null));
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, onChest);
                    return pause;
                }
                case MOVE -> {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                    if (!(p.containerMenu instanceof ChestMenu menu)) {
                        return fail("chest closed early");
                    }
                    if (++ticks < 3) {
                        return pause; // the screen and the slot contents arrive a tick after the menu
                    }
                    if (ticks % 2 == 1) {
                        return pause; // one click every other tick, like a player shift-clicking
                    }
                    int slot = nextSlot(p, menu);
                    if (slot < 0) {
                        stage = Stage.CLOSE;
                        return pause;
                    }
                    ItemStack st = menu.slots.get(slot).getItem();
                    if (slot == lastSlot && st.getCount() == lastCount) {
                        // the last click moved nothing: the chest is full
                        logDirect("role deposit: chest full");
                        deposited -= lastCount;
                        stage = Stage.CLOSE;
                        return pause;
                    }
                    lastSlot = slot;
                    lastCount = st.getCount();
                    deposited += st.getCount();
                    ctx.playerController().windowClick(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, p);
                    return pause;
                }
                case CLOSE -> {
                    p.closeContainer();
                    stage = null;
                    done = true;
                    logDirect("role deposit: done at " + chest.toShortString() + ", " + deposited + " stored so far");
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                default -> {
                    return pause;
                }
            }
        }

        /** The player slot in the chest menu to quick-move next, or -1. Keeps seeds and building blocks back. */
        private int nextSlot(LocalPlayer p, ChestMenu menu) {
            java.util.List<ItemStack> mine = new java.util.ArrayList<>();
            for (Slot s : menu.slots) {
                if (s.container == p.getInventory() && !s.getItem().isEmpty()) {
                    mine.add(s.getItem());
                }
            }
            java.util.List<ItemStack> move = movable(mine, order);
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot s = menu.slots.get(i);
                if (s.container != p.getInventory() || s.getItem().isEmpty()) {
                    continue;
                }
                boolean wanted = false;
                for (ItemStack m : move) {
                    if (m == s.getItem()) {
                        wanted = true;
                        break;
                    }
                }
                if (!wanted) {
                    continue;
                }
                if (i == lastSlot && s.getItem().getCount() != lastCount) {
                    lastSlot = -1; // that click worked; a new stack moved into this slot
                }
                return i;
            }
            return -1;
        }

        @Override
        public void onLostControl() {
            // Ostinato: a higher process (combat, a player command) took over; the loop retries after its backoff.
            if (stage != null) {
                cancel();
                failure = "interrupted";
            }
        }

        @Override
        public String displayName0() {
            return "Role deposit " + (stage == null ? "" : stage.name().toLowerCase()) + " " + (chest == null ? "" : chest.toShortString());
        }
    }
}
