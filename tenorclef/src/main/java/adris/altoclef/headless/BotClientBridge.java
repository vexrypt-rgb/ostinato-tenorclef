package adris.altoclef.headless;

import adris.altoclef.multiversion.ConnectScreenVer;
import adris.altoclef.multiversion.entity.PlayerVer;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
//#if MC >= 260000
//$$ import baritone.api.context.BotContext;
//$$ import baritone.api.context.BotContextScope;
//$$ import baritone.api.headless.IHeadlessHost;
//#endif
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Dual-mode bridge replacing direct {@code MinecraftClient.getInstance()} calls.
 * When a {@link BotContext} with an attached {@link IHeadlessHost} is bound on the current thread,
 * all operations dispatch directly to the bot's isolated protocol host; otherwise they delegate to
 * the standard single-client graphical {@link MinecraftClient}.
 */
public final class BotClientBridge {

    private BotClientBridge() {
    }

    //#if MC >= 260000
    //$$ public static IHeadlessHost hostOrNull() {
    //$$     BotContext ctx = BotContextScope.currentOrNull();
    //$$     return ctx == null ? null : ctx.headlessHost();
    //$$ }
    //#endif

    public static boolean isHeadless() {
        //#if MC >= 260000
        //$$ return hostOrNull() != null;
        //#else
        return false;
        //#endif
    }

    public static String botIdOrNull() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ return host == null ? null : host.botId();
        //#else
        return null;
        //#endif
    }

    public static IBaritone headlessBaritoneOrNull() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ return host == null ? null : host.baritone();
        //#else
        return null;
        //#endif
    }

    public static PlayerEntity player() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.player();
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null ? null : mc.player;
    }

    public static World world() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.level();
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null ? null : mc.world;
    }

    public static boolean inGame() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.isInGame();
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.player != null && mc.getNetworkHandler() != null;
    }

    public static Iterable<Entity> entities() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.worldEntities();
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) {
            return Collections.emptyList();
        }
        return mc.world.getEntities();
    }

    public static boolean isChunkLoaded(int chunkX, int chunkZ) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.isChunkLoaded(chunkX, chunkZ);
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.world != null && mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
    }

    public static int tickCount() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return (int) host.tickCount();
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getNetworkHandler() == null) {
            return 0;
        }
        ClientConnection con = mc.getNetworkHandler().getConnection();
        return con == null ? 0 : ((adris.altoclef.mixins.ClientConnectionAccessor) con).getTicks();
    }

    public static void sendClientMessage(Text text, boolean overlay) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     if (text != null) {
        //$$         System.out.println("[" + host.botId() + "] " + text.getString());
        //$$     }
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            PlayerVer.sendClientMessage(mc.player, text, overlay);
        }
    }

    public static void sendChatMessage(String content) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     host.sendChat(content);
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            PlayerVer.sendChatMessage(mc.player, content);
        }
    }

    public static void sendChatCommand(String command) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     String line = command.startsWith("/") ? command : "/" + command;
        //$$     host.sendChat(line);
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            PlayerVer.sendChatCommand(mc.player, command);
        }
    }

    public static void clickSlot(int syncId, int slotId, int button, SlotActionType type, PlayerEntity player) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     host.clickContainerSlot(syncId, slotId, button, type.name());
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.interactionManager != null) {
            mc.interactionManager.clickSlot(syncId, slotId, button, type, player);
        }
    }

    public static void clickButton(int syncId, int buttonId) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     host.clickContainerButton(syncId, buttonId);
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.interactionManager != null) {
            mc.interactionManager.clickButton(syncId, buttonId);
        }
    }

    public static void closeScreen() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     host.closeContainer();
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.closeHandledScreen();
        }
    }

    public static void respawn() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     host.respawn();
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.requestRespawn();
            mc.setScreen(null);
        }
    }

    public static HitResult raycast(double reach, boolean includeFluids) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.rayTrace(reach, includeFluids);
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) {
            return null;
        }
        return mc.player.raycast(reach, tickDelta(), includeFluids);
    }

    public static HitResult crosshairTarget() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.rayTrace(4.5, false);
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null ? null : mc.crosshairTarget;
    }

    public static float tickDelta() {
        if (isHeadless()) {
            return 1.0f;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            return 1.0f;
        }
        return mc.getRenderTickCounter().getTickDelta(true);
    }

    public static double mouseSensitivity() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.mouseSensitivity();
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null ? 0.5 : mc.options.getMouseSensitivity().getValue();
    }

    public static ActionResult interactBlock(Hand hand, BlockHitResult hit) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.playerController().processRightClickBlock(host.player(), host.level(), hand, hit);
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.interactionManager == null || mc.player == null) {
            return ActionResult.PASS;
        }
        return mc.interactionManager.interactBlock(mc.player, hand, hit);
    }

    public static ActionResult interactItem(Hand hand) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.playerController().processRightClick(host.player(), host.level(), hand);
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.interactionManager == null || mc.player == null) {
            return ActionResult.PASS;
        }
        return mc.interactionManager.interactItem(mc.player, hand);
    }

    public static void stopUsingItem() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     host.player().stopUsingItem();
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.interactionManager != null && mc.player != null) {
            mc.interactionManager.stopUsingItem(mc.player);
        }
    }

    public static void attackEntity(Entity target) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     // HeadlessPlayerController handles swing/attack on the wire; punch + attack is performed via controller or player
        //$$     net.minecraft.world.entity.player.Player p = host.player();
        //$$     if (p != null && target != null) {
        //$$         p.attack(target);
        //$$     }
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.interactionManager != null && mc.player != null && target != null) {
            mc.interactionManager.attackEntity(mc.player, target);
        }
    }

    public static void setInputOverride(Input input, boolean pressed) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     host.setInputOverride(input, pressed);
        //$$     return;
        //$$ }
        //#endif
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null) {
            baritone.getInputOverrideHandler().setInputForceState(input, pressed);
        }
    }

    public static boolean isInputPressed(Input input) {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.isInputPressed(input);
        //$$ }
        //#endif
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        return baritone != null && baritone.getInputOverrideHandler().isInputForcedDown(input);
    }

    public static Path runDirectory() {
        //#if MC >= 260000
        //$$ BotContext ctx = BotContextScope.currentOrNull();
        //$$ if (ctx != null) {
        //$$     IHeadlessHost host = ctx.headlessHost();
        //$$     if (host != null && host.configDir() != null) {
        //$$         return host.configDir();
        //$$     }
        //$$     if (ctx.configDir() != null) {
        //$$         return ctx.configDir();
        //$$     }
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null ? Path.of(".") : mc.runDirectory.toPath();
    }

    public static void requestStop() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     host.requestStop();
        //$$     return;
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.scheduleStop();
        }
    }

    public static boolean hasNetworkHandler() {
        //#if MC >= 260000
        //$$ IHeadlessHost host = hostOrNull();
        //$$ if (host != null) {
        //$$     return host.isConnected();
        //$$ }
        //#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.getNetworkHandler() != null;
    }

    public static Screen currentScreenOrNull() {
        if (isHeadless()) {
            return null;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null ? null : adris.altoclef.multiversion.ScreenVer.current(mc);
    }

    public static void setScreen(Screen screen) {
        if (isHeadless()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.setScreen(screen);
        }
    }

    public static ClientPlayerInteractionManager interactionManagerOrNull() {
        if (isHeadless()) {
            return null;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null ? null : mc.interactionManager;
    }
}
