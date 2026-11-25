package baritone.utils.player;

import baritone.api.BaritoneAPI;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import java.util.ArrayDeque;

// Ostinato: one input mailbox. It never sends packets or invokes gameplay actions.
// Container clicks are the sole exception, on an open screen with gameplay input released.
public final class ControlInput {
    private static int slot = -1;
    private static boolean attack, use, attackPulse, usePulse, drop;
    private static boolean ownedAttack, ownedUse, inventoryPause, openedInventory;
    private static Entity attackEntity, useEntity;
    private static BlockPos attackBlock, useBlock;
    private static LocalPlayer owner;
    private record Click(int window, int slot, int button, ContainerInput type) {}
    private static final ArrayDeque<Click> clicks = new ArrayDeque<>();

    private static final float FLICK_DEG = 10f; // Ostinato: largest per-tick turn still counted as settled
    private static int settledTicks;

    private ControlInput() {}

    // Ostinato: a headless host (no Minecraft client) has no keys; its driver turns the sim slot into the packet
    // vanilla sends for a hotbar key. A client always goes through the hotbar key below.
    public static void selectSlot(baritone.api.utils.IPlayerContext ctx, int value) {
        if (ctx.minecraft() == null) {
            if (value >= 0 && value < 9 && ctx.entity() != null) ctx.entity().getInventory().setSelectedSlot(value);
            return;
        }
        selectSlot(value);
    }

    public static void selectSlot(int value) {
        var player = Minecraft.getInstance().player;
        if (value >= 0 && value < 9 && player != null)
            slot = player.getInventory().getSelectedSlot() == value ? -1 : value;
    }

    public static void look(float yaw, float pitch) {
        var b = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (b.getPlayerContext().player() != null) b.getLookBehavior().updateTarget(new Rotation(yaw, pitch), true);
    }

    public static void press(KeyMapping key) {
        var mc = Minecraft.getInstance();
        if (key == mc.options.keyAttack) { attack = attackPulse = true; }
        else if (key == mc.options.keyUse) { use = usePulse = true; }
        else click(key);
    }

    public static void holdAttack(boolean down) { attack |= down; }
    public static void holdUse(boolean down) { use |= down; }
    public static void releaseAttack() { attack = attackPulse = false; }
    public static void releaseUsingItem(Player player) { use = usePulse = false; Minecraft.getInstance().options.keyUse.setDown(false); }
    public static void dropItem(LocalPlayer player, boolean all) { drop = true; }

    public static void attack(LocalPlayer player, Entity entity) {
        aim(player, entity);
        attack = attackPulse = true;
        attackEntity = entity;
    }

    public static InteractionResult interact(Player player, Entity entity, EntityHitResult hit, InteractionHand hand) {
        aim(player, entity);
        use = usePulse = true;
        useEntity = entity;
        // Requests have no synchronous result. Callers observe riding/entity/world state on later ticks.
        return InteractionResult.PASS;
    }

    public static InteractionResult useItem(Player player, InteractionHand hand) {
        use = usePulse = true;
        return InteractionResult.PASS;
    }

    public static InteractionResult useBlock(BlockHitResult hit) {
        use = usePulse = true;
        useBlock = hit.getBlockPos();
        return InteractionResult.PASS;
    }

    public static boolean breakBlock(BlockPos pos) {
        attack = true;
        attackBlock = pos;
        return false; // completion is observed from world state, never invented at request time
    }

    private static void aim(Player player, Entity entity) {
        var rotation = RotationUtils.calcRotationFromVec3d(player.getEyePosition(), entity.getBoundingBox().getCenter(),
                new Rotation(player.getYRot(), player.getXRot()));
        look(rotation.getYaw(), rotation.getPitch());
    }

    public static void windowClick(int window, int slot, int button, ContainerInput type) {
        Click c = new Click(window, slot, button, type);
        if (!clicks.contains(c) && clicks.size() < 64) clicks.add(c);
    }


    // Ostinato: anticheat control experiment (vkeys): plain KeyMapping holds that vanilla KeyboardInput/handleKeybinds read.
    private static int vanillaTicks;
    private static KeyMapping[] vanillaKeys = new KeyMapping[0];

    public static void holdVanillaKeys(int ticks, String[] names) {
        var o = Minecraft.getInstance().options;
        java.util.List<KeyMapping> keys = new java.util.ArrayList<>();
        for (String n : names) {
            switch (n.trim()) {
                case "forward" -> keys.add(o.keyUp);
                case "back" -> keys.add(o.keyDown);
                case "left" -> keys.add(o.keyLeft);
                case "right" -> keys.add(o.keyRight);
                case "jump" -> keys.add(o.keyJump);
                case "sneak" -> keys.add(o.keyShift);
                case "sprint" -> keys.add(o.keySprint);
                case "use" -> keys.add(o.keyUse);
                case "attack" -> keys.add(o.keyAttack);
                default -> { }
            }
        }
        for (KeyMapping k : vanillaKeys) k.setDown(false);
        vanillaKeys = keys.toArray(new KeyMapping[0]);
        vanillaTicks = Math.max(0, ticks);
    }

    private static void tickVanillaKeys(Minecraft mc) {
        if (vanillaKeys.length == 0) return;
        boolean down = vanillaTicks-- > 0;
        for (KeyMapping k : vanillaKeys) {
            if (down && !k.isDown() && (k == mc.options.keyUse || k == mc.options.keyAttack)) click(k); // a press, as GLFW would
            k.setDown(down);
        }
        if (!down) vanillaKeys = new KeyMapping[0];
    }

    public static boolean inventoryPaused() { return inventoryPause; }

    // Called before crosshair pick and handleKeybinds, once per tick. Slot keys are consumed by vanilla.
    public static void beginTick() {
        var mc = Minecraft.getInstance();
        if (owner != mc.player) {
            owner = mc.player;
            clear();
        }
        inventoryPause = !clicks.isEmpty();
        if (mc.player == null) return;
        tickVanillaKeys(mc);
        if (slot >= 0 && mc.gui.screen() == null) {
            click(mc.options.keyHotbarSlots[slot]);
            slot = -1;
        }
        if (openedInventory && clicks.isEmpty()) {
            if (mc.gui.screen() instanceof InventoryScreen) mc.gui.screen().onClose();
            openedInventory = false;
        }
    }

    // Called after planners, before vanilla handleKeybinds. Uses the crosshair vanilla already picked.
    public static void flush() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) { clear(); return; }
        inventoryPause |= !clicks.isEmpty();
        boolean blocked = inventoryPause || mc.gui.screen() != null || slot >= 0;
        // Ostinato: no fresh block click mid-flick. Also wait one extra settled tick so vanilla can send a
        // Rot/PosRot with the aim before handleKeybinds clicks. Clicking on the first settled tick (cc8
        // bridge14) sent UseItemOn 51 deg away from the last flying PosRot and Grim RotationPlace flagged it.
        boolean flicking = baritone.behavior.LookBehavior.lastTurnDegrees() > FLICK_DEG;
        if (flicking) settledTicks = 0;
        else settledTicks++;
        boolean unsettled = settledTicks < 2;
        boolean a = attack && !blocked && matches(mc, attackEntity, attackBlock) && allowed(mc, true)
                && !(unsettled && attackEntity == null && !ownedAttack);
        boolean u = use && !blocked && !a && matches(mc, useEntity, useBlock) && allowed(mc, false)
                && !(unsettled && useEntity == null);
        if (ownedAttack || a) mc.options.keyAttack.setDown(a);
        if (ownedUse || u) mc.options.keyUse.setDown(u);
        if (a && (!ownedAttack || attackPulse)) click(mc.options.keyAttack);
        if (u && (!ownedUse || usePulse)) click(mc.options.keyUse);
        ownedAttack = a;
        ownedUse = u;
        if (drop && !blocked && !a && !u) click(mc.options.keyDrop);
        if (inventoryPause) {
            for (KeyMapping key : new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
                    mc.options.keyRight, mc.options.keyJump, mc.options.keyShift, mc.options.keySprint,
                    mc.options.keyAttack, mc.options.keyUse}) key.setDown(false);
            while (mc.options.keyAttack.consumeClick()) {}
            while (mc.options.keyUse.consumeClick()) {}
            Click c = clicks.peek();
            if (c != null && c.window == mc.player.inventoryMenu.containerId && mc.gui.screen() == null) {
                mc.gui.setScreen(new InventoryScreen(mc.player));
                openedInventory = true;
            } else if (c != null && mc.gui.screen() instanceof AbstractContainerScreen<?> screen
                    && screen.getMenu().containerId == c.window && mc.player.containerMenu.containerId == c.window) {
                // Ostinato: same call as a click in this open screen, isolated from gameplay actions.
                mc.gameMode.handleContainerInput(c.window, c.slot, c.button, c.type, mc.player);
                clicks.remove();
            } else if (c != null && c.window != mc.player.containerMenu.containerId) {
                clicks.clear(); // closed/replaced container: never replay against a different menu
            }
        }
        attack = use = attackPulse = usePulse = drop = false;
        attackEntity = useEntity = null;
        attackBlock = useBlock = null;
    }

    // Ostinato: enforce reservations at the actual crosshair, including helper-held clicks.
    private static boolean allowed(Minecraft mc, boolean breaking) {
        if (!(mc.hitResult instanceof BlockHitResult h) || h.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return true;
        BlockPos at = h.getBlockPos();
        boolean container = mc.level.getBlockEntity(at) instanceof net.minecraft.world.Container;
        var kind = breaking ? baritone.swarm.lease.LeaseFence.Kind.BREAK
                : container ? baritone.swarm.lease.LeaseFence.Kind.CONTAINER : baritone.swarm.lease.LeaseFence.Kind.PLACE;
        if (!breaking && !container) at = at.relative(h.getDirection());
        return !baritone.swarm.lease.LeaseFence.denied(kind, at.getX(), at.getY(), at.getZ());
    }

    private static boolean matches(Minecraft mc, Entity entity, BlockPos block) {
        if (entity != null) return mc.hitResult instanceof EntityHitResult h && h.getEntity() == entity;
        if (block != null) return mc.hitResult instanceof BlockHitResult h && h.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && h.getBlockPos().equals(block);
        return true;
    }

    private static void click(KeyMapping key) {
        KeyMapping.click(InputConstants.getKey(key.saveString()));
    }

    private static void clear() {
        slot = -1;
        attack = use = attackPulse = usePulse = drop = false;
        ownedAttack = ownedUse = inventoryPause = openedInventory = false;
        attackEntity = useEntity = null;
        attackBlock = useBlock = null;
        clicks.clear();
        settledTicks = 0;
    }
}
