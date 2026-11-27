package adris.altoclef.util.helpers;

import adris.altoclef.headless.BotClientBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

public class InputHelper {

    public static boolean isKeyPressed(int code) {
        if (BotClientBridge.isHeadless()) {
            return false;
        }
        //#if MC >= 260000
        //$$ return com.mojang.blaze3d.platform.InputConstants.isKeyDown(code);
        //#elseif MC >= 12111
        //$$ return InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow(), code);
        //#else
        return InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow().getHandle(), code);
        //#endif
    }
}
