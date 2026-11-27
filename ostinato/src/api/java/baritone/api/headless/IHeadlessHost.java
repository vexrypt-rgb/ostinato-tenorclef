/*
 * Ostinato: public common-side host contract for headless agents (e.g. TenorClef)
 * running directly on a protocol bot without net.minecraft.client.* singletons.
 */
package baritone.api.headless;

import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import baritone.api.utils.input.Input;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface IHeadlessHost {

    String botId();

    Player player();

    Level level();

    IBaritone baritone();

    IPlayerContext playerContext();

    IPlayerController playerController();

    Iterable<Entity> worldEntities();

    boolean isChunkLoaded(int chunkX, int chunkZ);

    boolean isConnected();

    default boolean isInGame() {
        return isConnected() && player() != null && level() != null;
    }

    long tickCount();

    void sendChat(String message);

    void setChatReceiveListener(Consumer<String> listener);

    double mouseSensitivity();

    void setInputState(boolean forward, boolean back, boolean left, boolean right,
                       boolean jump, boolean sneak, boolean sprint);

    void setInputOverride(Input input, boolean pressed);

    boolean isInputPressed(Input input);

    void clickContainerSlot(int containerId, int slot, int button, String clickType);

    void clickContainerButton(int containerId, int buttonId);

    void closeContainer();

    void respawn();

    Path configDir();

    void requestStop();

    HitResult rayTrace(double reach, boolean includeFluids);
}
