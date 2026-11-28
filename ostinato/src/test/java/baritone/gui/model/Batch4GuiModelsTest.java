package baritone.gui.model;

import baritone.api.command.ArgDecl;
import baritone.api.command.FormDecl;
import baritone.api.command.FormPlacement;
import baritone.swarm.center.CenterTheme;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class Batch4GuiModelsTest {

    @After
    public void cleanup() {
        System.clearProperty(UnifiedThemeBridge.SYNC_PROPERTY);
    }

    @Test
    public void commandFormsClassifyValidateCrosshairAndRoute() {
        assertEquals(CommandForms.Section.MOVEMENT, CommandForms.classify("goto"));
        assertEquals(CommandForms.Section.BUILDING, CommandForms.classify("mine"));
        assertEquals(CommandForms.Section.SWARM, CommandForms.classify("pov"));
        assertEquals(CommandForms.Section.GENERAL, CommandForms.classify("help"));

        FormDecl gotoForm = FormDecl.of("goto", "Go to", "goto", FormPlacement.TILE, ArgDecl.rest("target", true));
        CommandForms.FormEntry entry = new CommandForms.FormEntry("goto", gotoForm, CommandForms.Section.MOVEMENT, "OWNER");

        // Missing required arg produces clear error
        CommandForms.Preview bad = CommandForms.buildCommand(entry, Map.of(), "self");
        assertFalse(bad.ok());

        // Crosshair fills target coordinates
        RadialMenu.Crosshair hit = new RadialMenu.Crosshair(true, 100, 64, -20, "minecraft:iron_ore", "Azalea");
        String coords = CommandForms.crosshairFillFor(gotoForm.args().get(0), hit);
        assertEquals("100 64 -20", coords);
        assertEquals("iron_ore", CommandForms.crosshairFillFor(ArgDecl.token("block", true), hit));
        assertEquals("Azalea", CommandForms.crosshairFillFor(ArgDecl.token("player", true), hit));

        // Valid values build routed command line
        CommandForms.Preview selfPrev = CommandForms.buildCommand(entry, Map.of("target", coords), "self");
        assertTrue(selfPrev.ok());
        assertEquals("goto 100 64 -20", selfPrev.line());

        CommandForms.Preview botPrev = CommandForms.buildCommand(entry, Map.of("target", coords), "Azalea");
        assertTrue(botPrev.ok());
        assertEquals("swarm send Azalea goto 100 64 -20", botPrev.line());
    }

    @Test
    public void serverSettingsStoreOverridesApplyRestoreAndToggle() throws Exception {
        Path dir = Files.createTempDirectory("srv-settings");
        assertEquals("localhost_25565", ServerSettingsStore.serverKey("localhost:25565"));
        assertEquals("singleplayer", ServerSettingsStore.serverKey(""));

        ServerSettingsStore.writeOverride(dir, "play.example.com:25565", "allowbreak", "allowBreak false");
        Map<String, String> read = ServerSettingsStore.readOverrides(dir, "play.example.com:25565");
        assertEquals("false", read.get("allowbreak"));

        Map<String, String> live = new LinkedHashMap<>();
        live.put("allowbreak", "true");

        ServerSettingsStore store = new ServerSettingsStore();
        int applied = store.enterServer(dir, "play.example.com:25565", live::get, live::put);
        assertEquals(1, applied);
        assertEquals("false", live.get("allowbreak"));
        assertEquals(ServerSettingsStore.Scope.SERVER, store.scopeOf("allowBreak", true));

        // Toggle off removes server override
        ServerSettingsStore.Scope afterToggleOff = store.toggleServerOverride(dir, "allowBreak", "allowBreak false", "false", true);
        assertEquals(ServerSettingsStore.Scope.GLOBAL, afterToggleOff);
        assertFalse(store.isServerOverride("allowBreak"));

        // Toggle back on writes it again
        ServerSettingsStore.Scope afterToggleOn = store.toggleServerOverride(dir, "allowBreak", "allowBreak false", "false", true);
        assertEquals(ServerSettingsStore.Scope.SERVER, afterToggleOn);
        assertTrue(store.isServerOverride("allowBreak"));

        // Leaving restores global value
        int restored = store.leaveServer(live::put);
        assertEquals(1, restored);
        assertEquals("true", live.get("allowbreak"));
    }

    @Test
    public void unifiedThemeBridgeSyncsNonDefaultThemesAndRespectsOptOut() {
        int fallback = 0xFF4FD1C5;
        // Default dark preserves Baritone's existing accent
        assertEquals(fallback, UnifiedThemeBridge.accent(CenterTheme.DEFAULT_DARK, fallback));

        // Flavor theme (Nether) overrides live
        assertEquals(0xFF000000 | CenterTheme.NETHER.accent(),
                UnifiedThemeBridge.accent(CenterTheme.NETHER, fallback));

        // Opt-out via system property preserves fallback
        System.setProperty(UnifiedThemeBridge.SYNC_PROPERTY, "false");
        assertEquals(fallback, UnifiedThemeBridge.accent(CenterTheme.NETHER, fallback));
    }
}
