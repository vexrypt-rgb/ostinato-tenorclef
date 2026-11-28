package baritone.swarm.center.app;

import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.center.feed.TerrainFeed;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/**
 * Ostinato: P4 Bot POV viewing overlay banner + live software-rendered 3D first-person stream preview for
 * headless bots (design doc: Bot POV switching — Full stream &amp; UI hooks).
 * View-only by default; taking control routes through the unified command registry.
 *
 * <p>Names: {@code pov:banner}, {@code pov:info}, {@code pov:prev}, {@code pov:next}, {@code pov:return},
 * {@code pov:stream}.
 */
public final class PovStreamPanel {

    private final AppStyle style;
    private final JPanel root;
    private final JLabel info;
    private final StreamCanvas canvas;
    private SwarmView view = SwarmView.EMPTY;
    private String watching;
    private PovSwitcher.Mode mode = PovSwitcher.Mode.NONE;

    public PovStreamPanel(AppStyle style, Runnable onPrev, Runnable onNext, Runnable onReturn) {
        this.style = style;
        root = style.panel(new BorderLayout(8, 4), style.surface());
        root.setName("pov:banner");
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, style.accent()),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        root.setVisible(false);

        JPanel bar = style.panel(new BorderLayout(8, 0), null);
        info = style.label(" ", style.text(), Font.BOLD, 12f);
        info.setName("pov:info");
        bar.add(info, BorderLayout.WEST);

        JPanel btns = style.panel(new FlowLayout(FlowLayout.RIGHT, 4, 0), null);
        JButton prev = style.button("◀ Prev");
        prev.setName("pov:prev");
        prev.addActionListener(e -> { if (onPrev != null) onPrev.run(); });
        JButton next = style.button("Next ▶");
        next.setName("pov:next");
        next.addActionListener(e -> { if (onNext != null) onNext.run(); });
        JButton ret = style.button("Return to own player");
        ret.setName("pov:return");
        ret.addActionListener(e -> { if (onReturn != null) onReturn.run(); });
        btns.add(prev);
        btns.add(next);
        btns.add(ret);
        bar.add(btns, BorderLayout.EAST);
        root.add(bar, BorderLayout.NORTH);

        canvas = new StreamCanvas();
        canvas.setName("pov:stream");
        root.add(canvas, BorderLayout.CENTER);
    }

    public JComponent component() { return root; }
    public boolean visible() { return root.isVisible(); }

    public void show(String bot, PovSwitcher.Mode mode, SwarmView view) {
        this.watching = bot;
        this.mode = mode == null ? PovSwitcher.Mode.NONE : mode;
        this.view = view == null ? SwarmView.EMPTY : view;
        boolean on = watching != null && !watching.isEmpty();
        root.setVisible(on);
        canvas.setVisible(on && this.mode == PovSwitcher.Mode.STREAM);
        refreshText();
        canvas.repaint();
        root.revalidate();
    }

    public void update(SwarmView view) {
        this.view = view == null ? SwarmView.EMPTY : view;
        if (root.isVisible()) {
            refreshText();
            canvas.repaint();
        }
    }

    private void refreshText() {
        if (watching == null) {
            info.setText(" ");
            return;
        }
        SwarmView.BotState b = view.bot(watching);
        if (b == null) {
            info.setText("Watching POV: " + watching + " (" + mode.label + ") · offline");
            return;
        }
        BotFeed f = b.feed();
        String pos = TileText.position(f);
        String hp = f.health() < 0 ? "hp n/a" : f.health() + "/" + f.maxHealth() + " HP";
        String food = f.hunger() < 0 ? "" : " · " + f.hunger() + "/20 food";
        info.setText("Watching POV: " + watching + " [" + mode.label + "] · " + pos + " · " + hp + food
                + " · " + f.process());
    }

    /** Software first-person terrain &amp; path preview for headless bots (no OpenGL, no Minecraft imports). */
    private final class StreamCanvas extends JComponent {
        private BufferedImage img;

        StreamCanvas() {
            setPreferredSize(new Dimension(640, 150));
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            if (img == null || img.getWidth() != w || img.getHeight() != h) {
                img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            int[] pix = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            float[] zbuf = new float[w * h];
            Arrays.fill(zbuf, Float.POSITIVE_INFINITY);

            int sky = style.theme.background();
            int groundBg = style.theme.surface();
            int horizon = h / 2;
            for (int y = 0; y < h; y++) {
                int col = y < horizon ? sky : groundBg;
                Arrays.fill(pix, y * w, (y + 1) * w, col);
            }

            SwarmView.BotState b = watching == null ? null : view.bot(watching);
            BotFeed.Pos pos = b == null ? null : b.feed().pos();
            if (pos != null) {
                // First-person camera looking from bot's eye position along bot's Minecraft yaw.
                double rad = Math.toRadians(pos.yaw());
                double fx = -Math.sin(rad);
                double fz = Math.cos(rad);
                double rx = -fz;
                double rz = fx;
                double ex = pos.x();
                double ey = pos.y() + 1.62;
                double ez = pos.z();
                double focal = h * 0.9;

                MapTerrain.Grid grid = MapTerrain.Grid.merge(view.terrain().values(), pos.dim());
                if (!grid.isEmpty()) {
                    int bx0 = pos.blockX() - 16, bx1 = pos.blockX() + 16;
                    int bz0 = pos.blockZ() - 16, bz1 = pos.blockZ() + 16;
                    for (int gz = bz0; gz <= bz1; gz++) {
                        for (int gx = bx0; gx <= bx1; gx++) {
                            int rgb = grid.rgbAt(gx, gz);
                            if (rgb == 0) continue;
                            int gy = grid.heightAt(gx, gz);
                            if (gy == TerrainFeed.UNKNOWN_Y) continue;
                            double dx = gx + 0.5 - ex;
                            double dy = gy + 0.5 - ey;
                            double dz = gz + 0.5 - ez;
                            double depth = dx * fx + dz * fz;
                            if (depth < 0.8 || depth > 22.0) continue;
                            double side = dx * rx + dz * rz;
                            double sx = w * 0.5 + (side / depth) * focal;
                            double sy = h * 0.5 - (dy / depth) * focal;
                            int half = Math.max(1, (int) Math.ceil((0.65 / depth) * focal));
                            int x0 = Math.max(0, (int) sx - half);
                            int x1 = Math.min(w - 1, (int) sx + half);
                            int y0 = Math.max(0, (int) sy - half);
                            int y1 = Math.min(h - 1, (int) sy + half);
                            if (x0 > x1 || y0 > y1) continue;
                            float fog = (float) Math.max(0.35, 1.0 - depth / 26.0);
                            int shaded = IsoMapRenderer.shade(rgb, fog);
                            float fd = (float) depth;
                            for (int py = y0; py <= y1; py++) {
                                int row = py * w;
                                for (int px = x0; px <= x1; px++) {
                                    if (fd < zbuf[row + px]) {
                                        zbuf[row + px] = fd;
                                        pix[row + px] = shaded;
                                    }
                                }
                            }
                        }
                    }
                }

                Graphics2D g2 = img.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Project upcoming path nodes in first-person perspective
                g2.setColor(new Color(style.theme.accent()));
                g2.setStroke(new BasicStroke(2f));
                int prevX = -1, prevY = -1;
                for (int[] node : b.feed().path().nodes()) {
                    if (node == null || node.length < 3) continue;
                    double dx = node[0] + 0.5 - ex;
                    double dy = node[1] + 0.3 - ey;
                    double dz = node[2] + 0.5 - ez;
                    double depth = dx * fx + dz * fz;
                    if (depth < 0.5) continue;
                    int sx = (int) Math.round(w * 0.5 + ((dx * rx + dz * rz) / depth) * focal);
                    int sy = (int) Math.round(h * 0.5 - (dy / depth) * focal);
                    if (prevX >= 0) {
                        g2.drawLine(prevX, prevY, sx, sy);
                    }
                    g2.fillOval(sx - 2, sy - 2, 5, 5);
                    prevX = sx;
                    prevY = sy;
                }
                // Crosshair + HUD overlay
                g2.setColor(new Color(style.theme.text()));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(w / 2 - 6, h / 2, w / 2 + 6, h / 2);
                g2.drawLine(w / 2, h / 2 - 6, w / 2, h / 2 + 6);
                g2.setFont(AppStyle.font(Font.PLAIN, 10.5f));
                int normYaw = Math.floorMod(Math.round(pos.yaw()), 360);
                g2.drawString("STREAM PREVIEW (view-only) · yaw " + normYaw + "° · "
                        + TileText.inventory(b.feed().inventory()), 8, h - 8);
                g2.dispose();
            }
            g.drawImage(img, 0, 0, null);
        }
    }
}
