package baritone.gui.screen;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.ArgDecl;
import baritone.api.command.FormDecl;
import baritone.gui.model.CommandForms;
import baritone.gui.model.RadialMenu;
import baritone.gui.model.TextInput;
import baritone.gui.render.GuiDraw;
import baritone.gui.render.Skin;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: P3 Commands desk inside {@link OstinatoScreen} — auto-generates typed forms directly from
 * registered {@link FormDecl} / {@link ArgDecl} schemas, with Use-Crosshair coordinate/block fill, target-bot picker,
 * live {@code #} command preview, and unified {@code ICommandManager.execute} dispatch.
 */
final class CommandsPanel {

    private static final float ROW_H = 19f;

    private final OstinatoScreen screen;
    private final List<CommandForms.FormEntry> allForms = new ArrayList<>();
    private final Map<String, TextInput> fieldInputs = new LinkedHashMap<>();
    private final List<String> targets = new ArrayList<>();
    private CommandForms.Section section = CommandForms.Section.ALL;
    private List<CommandForms.FormEntry> visible = List.of();
    private int selectedIdx = 0;
    private int targetIdx = 0;
    private String focusedField;
    private float listScroll = 0f;
    private float px0, px1, top, bottom;

    CommandsPanel(OstinatoScreen screen) {
        this.screen = screen;
        reloadForms();
    }

    int formCount() {
        return allForms.size();
    }

    boolean editing() {
        return focusedField != null;
    }

    void reloadForms() {
        allForms.clear();
        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (primary != null) {
            primary.getCommandManager().getRegistry().stream().forEach(cmd -> {
                if (cmd == null || cmd.getNames().isEmpty()) return;
                String name = cmd.getNames().get(0);
                String perm = cmd.getPermission() == null ? "OWNER" : cmd.getPermission().name();
                CommandForms.Section sec = CommandForms.classify(name);
                for (FormDecl f : cmd.forms()) {
                    allForms.add(new CommandForms.FormEntry(name, f, sec, perm));
                }
            });
        }
        discoverTargets();
        rebuildVisible();
    }

    private void discoverTargets() {
        Set<String> set = new LinkedHashSet<>();
        set.add("self");
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.level != null) {
            String self = mc.player == null ? "" : mc.player.getName().getString();
            for (Player p : mc.level.players()) {
                String n = p.getName().getString();
                if (n != null && !n.isBlank() && !n.equalsIgnoreCase(self)) {
                    set.add(n);
                }
            }
        }
        targets.clear();
        targets.addAll(set);
        if (targetIdx >= targets.size()) {
            targetIdx = 0;
        }
    }

    private void rebuildVisible() {
        visible = CommandForms.filter(allForms, section);
        if (selectedIdx >= visible.size()) {
            selectedIdx = Math.max(0, visible.size() - 1);
        }
        rebuildFields();
    }

    private void rebuildFields() {
        fieldInputs.clear();
        focusedField = null;
        CommandForms.FormEntry cur = current();
        if (cur == null) return;
        for (ArgDecl arg : cur.form().args()) {
            fieldInputs.put(arg.name(), new TextInput(128));
        }
    }

    private CommandForms.FormEntry current() {
        return selectedIdx >= 0 && selectedIdx < visible.size() ? visible.get(selectedIdx) : null;
    }

    private String currentTarget() {
        return targets.isEmpty() ? "self" : targets.get(Math.floorMod(targetIdx, targets.size()));
    }

    private Map<String, String> values() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, TextInput> e : fieldInputs.entrySet()) {
            out.put(e.getKey(), e.getValue().get());
        }
        return out;
    }

    void layout(float px0, float px1, float top, float bottom) {
        this.px0 = px0;
        this.px1 = px1;
        this.top = top;
        this.bottom = bottom;
    }

    void draw(GuiGraphicsExtractor ms, int mx, int my, int accent, long now) {
        GuiDraw.text(ms, "Commands", px0, top + 9, 1f, Skin.text(), true, true);
        GuiDraw.text(ms, visible.size() + " forms from registry", px0 + 66, top + 11, 0.5f, Skin.muted(), false, false);

        // Section filter chips
        float cx = px1;
        CommandForms.Section[] secs = CommandForms.Section.values();
        for (int i = secs.length - 1; i >= 0; i--) {
            CommandForms.Section s = secs[i];
            float w = GuiDraw.width(s.label) + 10;
            cx -= w;
            boolean on = s == section;
            boolean hov = OstinatoScreen.in(mx, my, cx, top + 6, cx + w, top + 20);
            GuiDraw.round(ms, cx, top + 6, cx + w, top + 20, 3,
                    on ? GuiDraw.alphaOf(accent, 0x2A) : (hov ? 0x1AFFFFFF : 0x0FFFFFFF));
            GuiDraw.text(ms, s.label, cx + 5, top + 9, on ? accent : (hov ? Skin.text() : Skin.muted()), false);
            cx -= 3;
        }

        float splitX = px0 + Math.min(165f, (px1 - px0) * 0.36f);
        float bodyY0 = top + 26f;
        float bodyY1 = bottom - 6f;

        // Left: form list
        GuiDraw.roundBorder(ms, px0, bodyY0, splitX - 6, bodyY1, 3, 0xFF232A38, 0xAA0C1018);
        GuiDraw.scissor(px0 + 1, bodyY0 + 1, splitX - 7, bodyY1 - 1);
        for (int i = 0; i < visible.size(); i++) {
            float ry = bodyY0 + 3 + i * ROW_H - listScroll;
            if (ry + ROW_H < bodyY0 || ry > bodyY1) continue;
            CommandForms.FormEntry e = visible.get(i);
            boolean sel = i == selectedIdx;
            boolean hov = OstinatoScreen.in(mx, my, px0 + 3, ry, splitX - 9, ry + ROW_H - 2);
            if (sel || hov) {
                GuiDraw.round(ms, px0 + 3, ry, splitX - 9, ry + ROW_H - 2, 2.5f,
                        sel ? GuiDraw.alphaOf(accent, 0x33) : 0x14FFFFFF);
            }
            GuiDraw.text(ms, GuiDraw.trim(e.form().label(), splitX - px0 - 20, 0.9f),
                    px0 + 7, ry + 4, 0.9f, sel ? accent : Skin.text(), false, sel);
        }
        GuiDraw.endScissor();

        // Right: auto-generated typed form
        float fx0 = splitX + 2;
        GuiDraw.roundBorder(ms, fx0, bodyY0, px1, bodyY1, 3, 0xFF232A38, 0xAA0C1018);
        CommandForms.FormEntry cur = current();
        if (cur == null) {
            GuiDraw.text(ms, "No forms registered in this section.", fx0 + 10, bodyY0 + 14, Skin.muted(), false);
            return;
        }

        float fy = bodyY0 + 8;
        GuiDraw.text(ms, cur.form().label(), fx0 + 10, fy, 1f, Skin.text(), true, true);
        String meta = "#" + cur.form().prefix() + "  [" + cur.permission() + "]";
        GuiDraw.text(ms, meta, fx0 + 10, fy + 11, 0.8f, Skin.muted(), false, false);

        // Target bot cycler + Use Crosshair button
        float btnY = fy;
        String tgtLabel = "Target: " + currentTarget();
        float tw = GuiDraw.width(tgtLabel) + 12;
        GuiDraw.roundBorder(ms, px1 - 10 - tw, btnY, px1 - 10, btnY + 15, 2.5f, accent, 0xCC111622);
        GuiDraw.text(ms, tgtLabel, px1 - 10 - tw + 6, btnY + 3.5f, accent, false);

        String xhLabel = "Use crosshair";
        float xw = GuiDraw.width(xhLabel) + 12;
        float xhX0 = px1 - 14 - tw - xw;
        GuiDraw.roundBorder(ms, xhX0, btnY, xhX0 + xw, btnY + 15, 2.5f, 0xFF2E3647, 0xCC111622);
        GuiDraw.text(ms, xhLabel, xhX0 + 6, btnY + 3.5f, Skin.text(), false);

        fy += 26;
        if (cur.form().args().isEmpty()) {
            GuiDraw.text(ms, "No arguments required.", fx0 + 10, fy + 4, Skin.muted(), false);
        } else {
            for (ArgDecl arg : cur.form().args()) {
                if (fy + 24 > bodyY1 - 42) break;
                String lbl = arg.name() + (arg.required() ? " *" : " (optional)") + "  [" + arg.kind().name().toLowerCase() + "]";
                GuiDraw.text(ms, lbl, fx0 + 10, fy, 0.85f, Skin.muted(), false, false);
                TextInput ti = fieldInputs.get(arg.name());
                boolean foc = arg.name().equals(focusedField);
                float bx0 = fx0 + 10, bx1 = px1 - 10, by0 = fy + 9, by1 = fy + 23;
                GuiDraw.roundBorder(ms, bx0, by0, bx1, by1, 2.5f, foc ? accent : Theme.FIELD_BORDER, Theme.FIELD);
                String val = ti == null ? "" : ti.get();
                GuiDraw.text(ms, GuiDraw.trim(val, bx1 - bx0 - 10, 1f), bx0 + 5, by0 + 3, Skin.text(), false);
                if (foc && (now / 530) % 2 == 0) {
                    float cxCur = bx0 + 5 + GuiDraw.width(val);
                    GuiDraw.rect(ms, Math.min(bx1 - 4, cxCur), by0 + 2, Math.min(bx1 - 3, cxCur + 0.5f), by1 - 2, Skin.text());
                }
                fy += 28;
            }
        }

        // Bottom command preview + Run button
        CommandForms.Preview prev = CommandForms.buildCommand(cur, values(), currentTarget());
        String prefix = Baritone.settings().prefix.value;
        float footY = bodyY1 - 32;
        GuiDraw.round(ms, fx0 + 8, footY, px1 - 8, footY + 24, 3, 0xFF07090D);
        float runW = 54f;
        String previewLine = prev.ok() ? prefix + prev.line() : "Invalid: " + prev.error();
        GuiDraw.text(ms, GuiDraw.trim(previewLine, (px1 - fx0) - runW - 28, 0.9f),
                fx0 + 14, footY + 8, 0.9f, prev.ok() ? accent : Theme.DANGER, false, false);
        float rx0 = px1 - 12 - runW;
        GuiDraw.roundBorder(ms, rx0, footY + 4, px1 - 12, footY + 20, 2.5f,
                prev.ok() ? accent : Theme.DIM, prev.ok() ? GuiDraw.alphaOf(accent, 0x33) : 0x20FFFFFF);
        float rw = GuiDraw.width("Run");
        GuiDraw.text(ms, "Run", rx0 + (runW - rw) * 0.5f, footY + 8, prev.ok() ? 0xFFFFFFFF : Skin.dim(), false);
    }

    boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        // Section chips
        float cx = px1;
        CommandForms.Section[] secs = CommandForms.Section.values();
        for (int i = secs.length - 1; i >= 0; i--) {
            CommandForms.Section s = secs[i];
            float w = GuiDraw.width(s.label) + 10;
            cx -= w;
            if (OstinatoScreen.in(mx, my, cx, top + 6, cx + w, top + 20)) {
                section = s;
                listScroll = 0f;
                selectedIdx = 0;
                rebuildVisible();
                return true;
            }
            cx -= 3;
        }

        float splitX = px0 + Math.min(165f, (px1 - px0) * 0.36f);
        float bodyY0 = top + 26f;
        float bodyY1 = bottom - 6f;

        // Left list click
        if (OstinatoScreen.in(mx, my, px0, bodyY0, splitX - 6, bodyY1)) {
            int idx = (int) Math.floor((my - (bodyY0 + 3) + listScroll) / ROW_H);
            if (idx >= 0 && idx < visible.size()) {
                selectedIdx = idx;
                rebuildFields();
                return true;
            }
        }

        CommandForms.FormEntry cur = current();
        if (cur == null) return false;

        float fx0 = splitX + 2;
        float btnY = bodyY0 + 8;
        String tgtLabel = "Target: " + currentTarget();
        float tw = GuiDraw.width(tgtLabel) + 12;
        if (OstinatoScreen.in(mx, my, px1 - 10 - tw, btnY, px1 - 10, btnY + 15)) {
            if (targets.size() > 1) {
                targetIdx = Math.floorMod(targetIdx + 1, targets.size());
            }
            return true;
        }
        String xhLabel = "Use crosshair";
        float xw = GuiDraw.width(xhLabel) + 12;
        float xhX0 = px1 - 14 - tw - xw;
        if (OstinatoScreen.in(mx, my, xhX0, btnY, xhX0 + xw, btnY + 15)) {
            fillFromCrosshair(cur);
            return true;
        }

        // Field focus
        focusedField = null;
        float fy = btnY + 26;
        for (ArgDecl arg : cur.form().args()) {
            if (fy + 24 > bodyY1 - 42) break;
            if (OstinatoScreen.in(mx, my, fx0 + 10, fy + 9, px1 - 10, fy + 23)) {
                focusedField = arg.name();
                return true;
            }
            fy += 28;
        }

        // Run button
        float footY = bodyY1 - 32;
        float runW = 54f;
        float rx0 = px1 - 12 - runW;
        if (OstinatoScreen.in(mx, my, rx0, footY + 4, px1 - 12, footY + 20)) {
            executeCurrent();
            return true;
        }
        return false;
    }

    private void fillFromCrosshair(CommandForms.FormEntry cur) {
        RadialMenu.Crosshair hit = captureCrosshair();
        int filled = 0;
        for (ArgDecl arg : cur.form().args()) {
            String val = CommandForms.crosshairFillFor(arg, hit);
            if (!val.isEmpty()) {
                TextInput ti = fieldInputs.get(arg.name());
                if (ti != null) {
                    ti.set(val);
                    filled++;
                }
            }
        }
        screen.flash(filled > 0 ? "Filled " + filled + " field(s) from crosshair" : "No matching crosshair target",
                filled > 0 ? Theme.accent() : Theme.MUTED);
    }

    private static RadialMenu.Crosshair captureCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return RadialMenu.Crosshair.NONE;
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos p = bhr.getBlockPos();
            BlockState st = mc.level.getBlockState(p);
            String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            return new RadialMenu.Crosshair(true, p.getX(), p.getY(), p.getZ(), id, "");
        }
        if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) {
            Entity e = ehr.getEntity();
            BlockPos p = e.blockPosition();
            return new RadialMenu.Crosshair(true, p.getX(), p.getY(), p.getZ(), "", e.getName().getString());
        }
        return RadialMenu.Crosshair.NONE;
    }

    private void executeCurrent() {
        CommandForms.FormEntry cur = current();
        if (cur == null) return;
        CommandForms.Preview prev = CommandForms.buildCommand(cur, values(), currentTarget());
        if (!prev.ok()) {
            screen.flash("Cannot run: " + prev.error(), Theme.DANGER);
            return;
        }
        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (primary != null) {
            primary.getCommandManager().execute(prev.line());
            screen.flash("Ran #" + prev.line(), Theme.accent());
        }
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        float max = Math.max(0f, visible.size() * ROW_H - (bottom - top - 36));
        listScroll = Math.max(0f, Math.min(max, listScroll - (float) delta * ROW_H * 1.5f));
        return true;
    }

    boolean keyPressed(int key) {
        if (focusedField == null) return false;
        TextInput ti = fieldInputs.get(focusedField);
        if (ti == null) return false;
        if (key == InputConstants.KEY_ESCAPE) {
            focusedField = null;
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            executeCurrent();
            return true;
        }
        return screen.editKey(ti, key);
    }

    boolean charTyped(char c) {
        if (focusedField == null) return false;
        TextInput ti = fieldInputs.get(focusedField);
        if (ti == null) return false;
        ti.insert(String.valueOf(c));
        return true;
    }
}
