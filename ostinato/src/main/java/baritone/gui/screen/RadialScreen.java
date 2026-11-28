package baritone.gui.screen;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.gui.model.RadialMenu;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Skin;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ostinato: P3 in-game hold-key radial quick menu (design doc: In-game menus).
 * Opens centered on the crosshair while holding the radial key, highlights the aimed slice, previews the exact
 * {@code #} command and target bot, and dispatches through the unified command registry on key release or click.
 */
public final class RadialScreen extends Screen {

    private static final float OUTER_R = 86f;
    private static final float INNER_R = 24f;
    private static final float RING_R = (OUTER_R + INNER_R) * 0.5f;
    private static final float RING_THICK = OUTER_R - INNER_R;

    private final int holdKey;
    private final RadialMenu.Crosshair crosshair;
    private final List<String> targets;
    private int targetIndex = 0;
    private int hoveredSlice = -1;
    private boolean cancelled = false;
    private boolean finished = false;

    public RadialScreen(int holdKey) {
        super(Component.literal("Ostinato Radial"));
        this.holdKey = holdKey;
        this.crosshair = captureCrosshair();
        this.targets = discoverTargets();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static RadialMenu.Crosshair captureCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return RadialMenu.Crosshair.NONE;
        }
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos p = bhr.getBlockPos();
            BlockState st = mc.level.getBlockState(p);
            String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            return new RadialMenu.Crosshair(true, p.getX(), p.getY(), p.getZ(), id, "");
        }
        if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) {
            Entity e = ehr.getEntity();
            String name = e.getName().getString();
            BlockPos p = e.blockPosition();
            return new RadialMenu.Crosshair(true, p.getX(), p.getY(), p.getZ(), "", name);
        }
        return RadialMenu.Crosshair.NONE;
    }

    private static List<String> discoverTargets() {
        Set<String> out = new LinkedHashSet<>();
        out.add("self");
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

    private String currentTarget() {
        if (targets.isEmpty()) {
            return "self";
        }
        return targets.get(Math.floorMod(targetIndex, targets.size()));
    }

    private void cycleTarget(int delta) {
        if (targets.size() > 1) {
            targetIndex = Math.floorMod(targetIndex + delta, targets.size());
        }
    }

    @Override
    public void tick() {
        if (finished) {
            return;
        }
        if (holdKey > 0 && !InputConstants.isKeyDown(holdKey)) {
            commitAndClose();
        }
    }

    private void commitAndClose() {
        if (finished) {
            return;
        }
        finished = true;
        if (!cancelled && hoveredSlice >= 0) {
            String cmd = RadialMenu.commandFor(hoveredSlice, currentTarget(), crosshair);
            if (!cmd.isEmpty()) {
                IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (primary != null) {
                    primary.getCommandManager().execute(cmd);
                }
            }
        }
        onClose();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent ev, boolean dbl) {
        if (ev.button() == 1) {
            cancelled = true;
            finished = true;
            onClose();
            return true;
        }
        if (ev.button() == 0) {
            float cx = width * 0.5f;
            float cy = height * 0.5f;
            hoveredSlice = RadialMenu.sliceAt(ev.x() - cx, ev.y() - cy, RadialMenu.DEADZONE_PX);
            commitAndClose();
            return true;
        }
        return super.mouseClicked(ev, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double deltaX, double delta) {
        if (delta != 0) {
            cycleTarget(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mx, my, deltaX, delta);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent ev) {
        int key = ev.key();
        if (key == InputConstants.KEY_ESCAPE) {
            cancelled = true;
            finished = true;
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_TAB) {
            cycleTarget(1);
            return true;
        }
        return super.keyPressed(ev);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ms, int mouseX, int mouseY, float partialTicks) {
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        hoveredSlice = RadialMenu.sliceAt(mouseX - cx, mouseY - cy, RadialMenu.DEADZONE_PX);

        int accent = Theme.accent();
        int n = RadialMenu.SLICES.size();
        double sector = (Math.PI * 2.0) / n;
        double gap = 0.04;

        // Draw 6 radial slices clockwise starting at 12 o'clock (-PI/2)
        for (int i = 0; i < n; i++) {
            double center = -Math.PI * 0.5 + i * sector;
            double a0 = center - sector * 0.5 + gap;
            double a1 = center + sector * 0.5 - gap;
            boolean active = i == hoveredSlice;
            int fill = active ? GuiDraw.alphaOf(accent, 0xCC) : 0xB8111622;
            int rim = active ? GuiDraw.alphaOf(accent, 0xFF) : 0xCC2B3344;
            Skin.arc(ms, cx, cy, RING_R, RING_R, a0, a1, RING_THICK, fill, fill);
            Skin.arc(ms, cx, cy, OUTER_R, OUTER_R, a0, a1, 1.5f, rim, rim);

            RadialMenu.Slice s = RadialMenu.SLICES.get(i);
            float lx = cx + (float) Math.cos(center) * (RING_R + 2f);
            float ly = cy + (float) Math.sin(center) * (RING_R + 2f);
            String label = s.glyph() + " " + s.label();
            float tw = GuiDraw.width(label);
            int textCol = active ? 0xFFFFFFFF : Skin.text();
            GuiDraw.text(ms, label, lx - tw * 0.5f, ly - 4f, textCol, true);
        }

        // Center hub
        GuiDraw.roundBorder(ms, cx - INNER_R, cy - INNER_R, cx + INNER_R, cy + INNER_R, INNER_R,
                GuiDraw.alphaOf(accent, 0xC0), 0xE60B0E14);
        String tgt = currentTarget();
        float tw = GuiDraw.width(tgt, 0.85f, true);
        GuiDraw.text(ms, tgt, cx - tw * 0.5f, cy - 4f, 0.85f, accent, true, true);

        // Command preview pill below wheel
        String prefix = Baritone.settings().prefix.value;
        String cmd = hoveredSlice >= 0 ? RadialMenu.commandFor(hoveredSlice, tgt, crosshair) : "";
        String line = cmd.isEmpty()
                ? "Move mouse to select · Scroll/Tab: target (" + tgt + ") · Esc/RMB: cancel"
                : prefix + cmd;
        float pw = Math.max(150f, GuiDraw.width(line) + 18f);
        float px0 = cx - pw * 0.5f;
        float py0 = cy + OUTER_R + 12f;
        GuiDraw.roundBorder(ms, px0, py0, px0 + pw, py0 + 18f, 4f,
                hoveredSlice >= 0 ? accent : 0xFF2B3344, 0xDC0B0E14);
        float ltw = GuiDraw.width(line);
        GuiDraw.text(ms, line, cx - ltw * 0.5f, py0 + 5f, hoveredSlice >= 0 ? accent : Skin.muted(), false);
    }
}
