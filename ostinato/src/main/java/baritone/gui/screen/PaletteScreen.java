package baritone.gui.screen;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.utils.Helper;
import baritone.gui.model.InGamePalette;
import baritone.gui.model.TextInput;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Skin;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ostinato: P3 in-game command palette screen (Ctrl+P in-game to avoid TenorClef's Ctrl+K stop-tasks keybind).
 * Fuzzy-searches every registered {@link ICommand}, saved presets, and session history; supports Tab target cycling,
 * Ctrl+D dry-run preview, Ctrl+S favorite toggling, and dispatches through {@code ICommandManager.execute}.
 */
public final class PaletteScreen extends Screen {

    private static final int MAX_VISIBLE = 9;
    private static final float ROW_H = 22f;

    private final InGamePalette palette;
    private final TextInput input = new TextInput(128);
    private final List<String> targets;
    private final List<String> peers;
    private List<InGamePalette.Entry> matches = List.of();
    private int selected = 0;
    private int targetIndex = 0;
    private boolean dryRun = false;

    public PaletteScreen() {
        super(Component.literal("Ostinato Command Palette"));
        this.palette = new InGamePalette(collectRegistryEntries());
        this.peers = discoverPeers();
        this.targets = new ArrayList<>();
        this.targets.add("self");
        this.targets.addAll(peers);
        if (!peers.isEmpty()) {
            this.targets.add("all");
        }
        refreshMatches();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static List<InGamePalette.Entry> collectRegistryEntries() {
        List<InGamePalette.Entry> out = new ArrayList<>();
        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (primary == null) {
            return out;
        }
        primary.getCommandManager().getRegistry().stream().forEach(cmd -> {
            if (cmd == null || cmd.hiddenFromHelp() || cmd.getNames().isEmpty()) {
                return;
            }
            String primaryName = cmd.getNames().get(0);
            String desc = cmd.getShortDesc() == null ? "" : cmd.getShortDesc();
            String perm = cmd.getPermission() == null ? "OWNER" : cmd.getPermission().name();
            out.add(new InGamePalette.Entry("cmd:" + primaryName, primaryName, desc, perm, false));
        });
        return out;
    }

    private static List<String> discoverPeers() {
        Set<String> out = new LinkedHashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.level != null) {
            String selfName = mc.player == null ? "" : mc.player.getName().getString();
            for (Player p : mc.level.players()) {
                String n = p.getName().getString();
                if (n != null && !n.isBlank() && !n.equalsIgnoreCase(selfName)) {
                    out.add(n);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private void refreshMatches() {
        matches = palette.search(input.get());
        if (selected >= matches.size()) {
            selected = Math.max(0, matches.size() - 1);
        }
    }

    private String currentTarget() {
        if (targets.isEmpty()) return "self";
        return targets.get(Math.floorMod(targetIndex, targets.size()));
    }

    private String resolvedCommand() {
        String typed = input.get().trim();
        if (!typed.isEmpty() && typed.contains(" ")) {
            return InGamePalette.routeCommand(typed, currentTarget(), peers);
        }
        if (selected >= 0 && selected < matches.size()) {
            return InGamePalette.routeCommand(matches.get(selected).command(), currentTarget(), peers);
        }
        return InGamePalette.routeCommand(typed, currentTarget(), peers);
    }

    private void runSelected() {
        String cmd = resolvedCommand();
        if (cmd.isEmpty()) {
            onClose();
            return;
        }
        String prefix = Baritone.settings().prefix.value;
        if (dryRun) {
            Helper.HELPER.logDirect("[dry-run] would run: " + prefix + cmd);
            onClose();
            return;
        }
        InGamePalette.recordHistory(cmd);
        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (primary != null) {
            primary.getCommandManager().execute(cmd);
        }
        onClose();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent ev) {
        int key = ev.key();
        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            runSelected();
            return true;
        }
        if (key == InputConstants.KEY_TAB) {
            if (targets.size() > 1) {
                targetIndex = Math.floorMod(targetIndex + 1, targets.size());
            }
            return true;
        }
        if (key == InputConstants.KEY_UP) {
            if (!matches.isEmpty()) {
                selected = Math.floorMod(selected - 1, matches.size());
            }
            return true;
        }
        if (key == InputConstants.KEY_DOWN) {
            if (!matches.isEmpty()) {
                selected = Math.floorMod(selected + 1, matches.size());
            }
            return true;
        }
        if (Keys.ctrl() && key == InputConstants.KEY_D) {
            dryRun = !dryRun;
            return true;
        }
        if (Keys.ctrl() && key == InputConstants.KEY_S) {
            if (selected >= 0 && selected < matches.size()) {
                InGamePalette.toggleFavorite(matches.get(selected).command());
                refreshMatches();
            }
            return true;
        }
        if (Keys.ctrl() && key == InputConstants.KEY_A) {
            input.selectAll();
            return true;
        }
        String before = input.get();
        switch (key) {
            case InputConstants.KEY_BACKSPACE -> input.backspace();
            case InputConstants.KEY_DELETE -> input.delete();
            case InputConstants.KEY_LEFT -> input.left();
            case InputConstants.KEY_RIGHT -> input.right();
            case InputConstants.KEY_HOME -> input.home();
            case InputConstants.KEY_END -> input.end();
            default -> {
                return super.keyPressed(ev);
            }
        }
        if (!before.equals(input.get())) {
            selected = 0;
            refreshMatches();
        }
        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent ev) {
        char c = (char) ev.codepoint();
        if (!StringUtil.isAllowedChatCharacter(c)) {
            return false;
        }
        input.insert(String.valueOf(c));
        selected = 0;
        refreshMatches();
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent ev, boolean dbl) {
        float w = Math.min(460f, width - 32f);
        float x0 = (width - w) * 0.5f;
        float y0 = Math.max(24f, height * 0.14f);
        float listY0 = y0 + 44f;
        int shown = Math.min(MAX_VISIBLE, matches.size());
        if (ev.x() >= x0 + 8 && ev.x() <= x0 + w - 8 && ev.y() >= listY0 && ev.y() < listY0 + shown * ROW_H) {
            int idx = (int) ((ev.y() - listY0) / ROW_H);
            if (idx >= 0 && idx < matches.size()) {
                selected = idx;
                runSelected();
                return true;
            }
        }
        if (ev.x() < x0 || ev.x() > x0 + w || ev.y() < y0) {
            onClose();
            return true;
        }
        return super.mouseClicked(ev, dbl);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ms, int mouseX, int mouseY, float partialTicks) {
        int accent = Theme.accent();
        float w = Math.min(460f, width - 32f);
        int shown = Math.min(MAX_VISIBLE, matches.size());
        float h = 76f + Math.max(1, shown) * ROW_H;
        float x0 = (width - w) * 0.5f;
        float y0 = Math.max(24f, height * 0.14f);
        float x1 = x0 + w;
        float y1 = y0 + h;

        GuiDraw.gradV(ms, 0, 0, width, height, 0x7007090D, 0x9807090D);
        GuiDraw.shadow(ms, x0, y0, x1, y1, 5, 8, 0x90);
        GuiDraw.roundBorder(ms, x0, y0, x1, y1, 5, 0xFF2B3344, 0xF2151A25, 0xF40D1017);

        // Header + search input
        GuiDraw.text(ms, "Command Palette", x0 + 10, y0 + 7, accent, true);
        String meta = "Target: " + currentTarget() + (dryRun ? "  [DRY-RUN]" : "");
        float mw = GuiDraw.width(meta);
        GuiDraw.text(ms, meta, x1 - 10 - mw, y0 + 7, dryRun ? Theme.AMBER : Skin.muted(), false);

        float fx0 = x0 + 10, fy0 = y0 + 20, fx1 = x1 - 10, fy1 = y0 + 38;
        GuiDraw.roundBorder(ms, fx0, fy0, fx1, fy1, 3f, accent, Theme.FIELD);
        String query = input.get();
        String display = query.isEmpty() ? "Type command, preset, or fuzzy query..." : query;
        GuiDraw.text(ms, GuiDraw.trim(display, w - 32, 1f), fx0 + 6, fy0 + 5,
                query.isEmpty() ? Skin.dim() : Skin.text(), false);

        // Result list
        float ry = y0 + 44f;
        if (matches.isEmpty()) {
            GuiDraw.text(ms, "No matching registry command — press Enter to run as typed", x0 + 12, ry + 6, Skin.muted(), false);
        } else {
            for (int i = 0; i < shown; i++) {
                InGamePalette.Entry e = matches.get(i);
                boolean active = i == selected;
                float r0 = ry + i * ROW_H;
                if (active) {
                    GuiDraw.roundBorder(ms, x0 + 8, r0, x1 - 8, r0 + ROW_H - 2, 3f,
                            GuiDraw.alphaOf(accent, 0xCC), GuiDraw.alphaOf(accent, 0x36));
                }
                boolean fav = InGamePalette.isFavorite(e.command());
                String title = (fav ? "★ " : "") + e.command();
                GuiDraw.text(ms, title, x0 + 14, r0 + 3, active ? 0xFFFFFFFF : Skin.text(), false);
                String desc = GuiDraw.trim(e.description(), w - 160, 0.85f);
                GuiDraw.text(ms, desc, x0 + 14, r0 + 12, 0.85f, Skin.muted(), false, false);
                String perm = e.permission();
                float pw = GuiDraw.width(perm, 0.8f, false);
                GuiDraw.text(ms, perm, x1 - 14 - pw, r0 + 6, 0.8f,
                        "OWNER".equals(perm) ? Theme.AMBER : accent, false, false);
            }
        }

        // Footer preview bar
        String prefix = Baritone.settings().prefix.value;
        String preview = resolvedCommand();
        String footLeft = preview.isEmpty() ? "Esc close" : (dryRun ? "[DRY-RUN] " : "") + prefix + preview;
        String footRight = "Tab target · Ctrl+D dry-run · Ctrl+S ★";
        GuiDraw.rect(ms, x0 + 8, y1 - 22, x1 - 8, y1 - 21.5f, 0x44FFFFFF);
        GuiDraw.text(ms, GuiDraw.trim(footLeft, w * 0.52f, 0.9f), x0 + 10, y1 - 15, 0.9f, accent, false, false);
        float rw = GuiDraw.width(footRight, 0.8f, false);
        GuiDraw.text(ms, footRight, x1 - 10 - rw, y1 - 14, 0.8f, Skin.dim(), false, false);
    }
}
