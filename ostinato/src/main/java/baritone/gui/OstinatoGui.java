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


package baritone.gui;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.gui.model.KeyNames;
import baritone.gui.screen.OstinatoScreen;
import baritone.gui.screen.PaletteScreen;
import baritone.gui.screen.RadialScreen;
import baritone.gui.tasks.game.TaskService;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Wiring for the Ostinato GUI: polls the {@code guiKeybind} key every client tick (edge-triggered, only with no
 * screen open, same approach as TenorClef's menu key), polls the hold-key radial quick menu ({@code ostinato.radialKey},
 * default {@code R}), polls {@code Ctrl+P} (configurable via {@code ostinato.paletteKey}, avoiding TenorClef's
 * {@code Ctrl+K} stop-tasks shortcut) for the in-game command palette, and flushes debounced GUI saves.
 */
public final class OstinatoGui implements AbstractGameEventListener {

    public static final GuiSettingsStore STORE = new GuiSettingsStore();

    private final Baritone baritone;
    private boolean wasDown = true; // don't open on a key that was already held at startup
    private boolean radialWasDown = true; // Ostinato: P3 hold-key radial menu
    private boolean paletteWasDown = true; // Ostinato: P3 in-game Ctrl+K palette

    public OstinatoGui(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void onTick(TickEvent event) {
        if (BaritoneAPI.getProvider().getPrimaryBaritone() != baritone) {
            return;
        }
        STORE.tick();
        TaskService.INSTANCE.tick();
        Minecraft mc = Minecraft.getInstance();
        int key = KeyNames.parse(Baritone.settings().guiKeybind.value);
        boolean down = key != KeyNames.NONE && InputConstants.isKeyDown(key);
        if (down && !wasDown && mc.gui.screen() == null && mc.player != null) {
            mc.gui.setScreen(new OstinatoScreen());
        }
        wasDown = down;

        // Ostinato: P3 hold-key radial quick menu (default R; override via -Dostinato.radialKey=...)
        int radialKey = KeyNames.parse(System.getProperty("ostinato.radialKey", "R"));
        boolean radialDown = radialKey != KeyNames.NONE && radialKey != key && InputConstants.isKeyDown(radialKey);
        if (radialDown && !radialWasDown && mc.gui.screen() == null && mc.player != null) {
            mc.gui.setScreen(new RadialScreen(radialKey));
        }
        radialWasDown = radialDown;

        // Ostinato: P3 in-game command palette (Ctrl+P by default; Ctrl+K is reserved by TenorClef stopTasks)
        int paletteKey = KeyNames.parse(System.getProperty("ostinato.paletteKey", "P"));
        boolean ctrl = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
        boolean paletteDown = paletteKey != KeyNames.NONE && ctrl && InputConstants.isKeyDown(paletteKey);
        if (paletteDown && !paletteWasDown && mc.gui.screen() == null && mc.player != null) {
            mc.gui.setScreen(new PaletteScreen());
        }
        paletteWasDown = paletteDown;
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        // leaving a world ends a running task list; its steps refer to that world
        if (event.getWorld() == null && event.getState() == EventState.POST
                && BaritoneAPI.getProvider().getPrimaryBaritone() == baritone) {
            TaskService.INSTANCE.runner.stop(System.currentTimeMillis());
        }
    }
}
