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
        return l.order().role.id + " " + l.phase().name().toLowerCase() + (l.note().isEmpty() ? "" : " (" + l.note() + ")")
                + " deposits=" + l.deposits() + " stored=" + deposited + " free=" + lastFreeSlots + " output=" + lastOutput;
    }

    public String phase() {
        RoleLoop l = loop;
        return l == null ? "idle" : l.phase().name().toLowerCase();
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
        RoleOrder next = pending;
        if (next != null) {
            pending = null;
            stopWork();
            deposit.cancel();
            loop = next.role == Role.IDLE ? null : new RoleLoop(next);
            logDirect("role: " + next.line());
        }
        RoleLoop l = loop;
        LocalPlayer p = ctx.player();
        if (l == null || p == null) {
            return;
        }
        RoleOrder o = l.order();
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
        RoleLoop.Step step = l.step(new RoleLoop.Inputs(System.currentTimeMillis(), free, output, workActive,
                workFailed, deposit.isActive(), deposit.takeDone(), deposit.takeFailure()));
        if (!step.start()) {
            return;
        }
        logDirect("role " + o.role.id + ": " + step.phase().name().toLowerCase() + " - " + step.note());
        switch (step.phase()) {
            case WORK -> startWork(o);
            case DEPOSIT -> {
                stopWork();
                deposit.start(new BlockPos(o.chest[0], o.chest[1], o.chest[2]), o);
            }
            case WAIT -> stopWork();
            default -> {
            }
        }
    }

    private long workRequest = -1;

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
                baritone.getFarmProcess().farm(o.radius, at);
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
                        return fail("chest did not open");
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
