package adris.altoclef.control;

import adris.altoclef.headless.BotClientBridge;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Sometimes we want to trigger a "press" for one frame, or do other input forcing.
 * <p>
 * Dealing with keeping track of a press and timing each time you do this is annoying.
 * <p>
 * For some reason using baritone's "Forcestate" doesn't always work, perhaps that's my bad.
 * <p>
 * But this will alleviate all confusion.
 */
@SuppressWarnings("UnnecessaryDefault")
public class InputControls {

    private final Queue<Input> toUnpress = new ArrayDeque<>();
    private final Set<Input> _waitForRelease = new HashSet<>(); // a click requires a release.

    private static KeyBinding inputToKeyBinding(Input input) {
        GameOptions o = MinecraftClient.getInstance().options;
        return switch (input) {
            case MOVE_FORWARD -> o.forwardKey;
            case MOVE_BACK -> o.backKey;
            case MOVE_LEFT -> o.leftKey;
            case MOVE_RIGHT -> o.rightKey;
            case CLICK_LEFT -> o.attackKey;
            case CLICK_RIGHT -> o.useKey;
            case JUMP -> o.jumpKey;
            case SNEAK -> o.sneakKey;
            case SPRINT -> o.sprintKey;
            default -> throw new IllegalArgumentException("Invalid key input/not accounted for: " + input);
        };
    }

    public void tryPress(Input input) {
        // We just pressed, so let us release.
        if (_waitForRelease.contains(input)) {
            return;
        }
        if (BotClientBridge.isHeadless()) {
            BotClientBridge.setInputOverride(input, true);
        } else {
            inputToKeyBinding(input).setPressed(true);
            // Also necessary to ensure the game registers the input as "pressed"
            KeyBinding.onKeyPressed(inputToKeyBinding(input).getDefaultKey());
        }
        toUnpress.add(input);
        _waitForRelease.add(input);
    }

    public void hold(Input input) {
        if (BotClientBridge.isHeadless()) {
            BotClientBridge.setInputOverride(input, true);
            return;
        }
        if (!inputToKeyBinding(input).isPressed()) {
            KeyBinding.onKeyPressed(inputToKeyBinding(input).getDefaultKey());
        }
        inputToKeyBinding(input).setPressed(true);
    }

    public void release(Input input) {
        if (BotClientBridge.isHeadless()) {
            BotClientBridge.setInputOverride(input, false);
            return;
        }
        inputToKeyBinding(input).setPressed(false);
    }

    public boolean isHeldDown(Input input) {
        if (BotClientBridge.isHeadless()) {
            return BotClientBridge.isInputPressed(input);
        }
        return inputToKeyBinding(input).isPressed();
    }

    public void forceLook(float yaw, float pitch) {
        if (BotClientBridge.isHeadless()) {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b != null) {
                b.getLookBehavior().updateTarget(new Rotation(yaw, pitch), false);
            }
            return;
        }
        if (MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().player.setYaw(yaw);
            MinecraftClient.getInstance().player.setPitch(pitch);
        }
    }

    // Before the user calls input commands for the frame
    public void onTickPre() {
        while (!toUnpress.isEmpty()) {
            Input input = toUnpress.remove();
            if (BotClientBridge.isHeadless()) {
                BotClientBridge.setInputOverride(input, false);
            } else {
                inputToKeyBinding(input).setPressed(false);
            }
        }
    }

    // After the user calls input commands for the frame
    public void onTickPost() {
        _waitForRelease.clear();
    }
}
