package baritone.swarm.center.app;

import baritone.swarm.rts.Blueprint;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Ostinato: CPU-raster 3D orbit view of a {@link Blueprint}. Left-drag orbits, shift/middle-drag pans,
 * wheel dollies. A click without a drag paints (left) or erases (right) through {@link #onPaint}.
 */
public final class EditorCanvas extends JComponent {

    public final EditorCamera camera = new EditorCamera();
    private Blueprint blueprint = new Blueprint("untitled");
    private int layerY = -60;
    private int layers = 1;
    private boolean showAll = true;
    private boolean live;
    private final Set<Long> placed = new HashSet<>();
    private EditorHit hover = EditorHit.miss();
    private BiConsumer<EditorHit, Boolean> onPaint = (h, e) -> {};
    private final Color bg;
    private final Color grid;
    private final Color text;
    private int pressX, pressY, pressButton;
    private boolean dragging;

    public EditorCanvas(AppStyle style) {
        setName("ed:canvas");
        setOpaque(true);
        bg = style == null ? new Color(0x1B1B1D) : style.bg();
        grid = style == null ? new Color(0x3A3A40) : style.line();
        text = style == null ? new Color(0xC8C8C8) : style.muted();
        setBackground(bg);
        setPreferredSize(new Dimension(640, 480));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                pressX = e.getX();
                pressY = e.getY();
                pressButton = e.getButton();
                dragging = false;
                hover = pick(e.getX(), e.getY());
                repaint();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (!dragging && (e.getButton() == MouseEvent.BUTTON1 || e.getButton() == MouseEvent.BUTTON3)) {
                    EditorHit hit = pick(e.getX(), e.getY());
                    if (!hit.missed()) {
                        onPaint.accept(hit, e.getButton() == MouseEvent.BUTTON3);
                    }
                }
                dragging = false;
                hover = pick(e.getX(), e.getY());
                repaint();
            }

            @Override public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - pressX;
                int dy = e.getY() - pressY;
                if (!dragging && Math.hypot(dx, dy) < 4) {
                    return;
                }
                if (!dragging) {
                    dragging = true;
                }
                boolean pan = pressButton == MouseEvent.BUTTON2 || (e.getModifiersEx() & MouseEvent.SHIFT_DOWN_MASK) != 0;
                if (pan) {
                    double scale = camera.distance / Math.max(80, getHeight());
                    camera.pan(-dx * scale, dy * scale);
                } else {
                    camera.orbit(dx * 0.01, dy * 0.01);
                }
                pressX = e.getX();
                pressY = e.getY();
                hover = pick(e.getX(), e.getY());
                repaint();
            }

            @Override public void mouseMoved(MouseEvent e) {
                EditorHit h = pick(e.getX(), e.getY());
                if (h.kind != hover.kind || h.x != hover.x || h.y != hover.y || h.z != hover.z) {
                    hover = h;
                    repaint();
                }
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                camera.dolly(-e.getPreciseWheelRotation());
                hover = pick(e.getX(), e.getY());
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public void setOnPaint(BiConsumer<EditorHit, Boolean> onPaint) {
        this.onPaint = onPaint == null ? (h, e) -> {} : onPaint;
    }

    public void setBlueprint(Blueprint b) {
        this.blueprint = b == null ? new Blueprint("untitled") : b;
        repaint();
    }

    /** Same as {@link #setBlueprint}; kept so call sites can say they are not refitting the camera. */
    public void setBlueprintKeepCamera(Blueprint b) {
        setBlueprint(b);
    }

    public Blueprint blueprint() {
        return blueprint;
    }

    public void setLayer(int y, int h) {
        layerY = y;
        layers = Math.max(1, h);
        repaint();
    }

    public int layerY() { return layerY; }

    public void setShowAll(boolean on) {
        showAll = on;
        repaint();
    }

    public void setLive(boolean on) {
        live = on;
        repaint();
    }

    public void setPlaced(Set<Long> cells) {
        placed.clear();
        if (cells != null) {
            placed.addAll(cells);
        }
        repaint();
    }

    public EditorHit hover() {
        return hover;
    }

    public EditorHit pick(int sx, int sy) {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        double[] o = new double[3];
        double[] d = new double[3];
        camera.ray(sx, sy, 0, 0, w, h, o, d);
        return EditorHit.trace(o, d, blueprint.cells(), layerY);
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] pix = new int[w * h];
        float[] zbuf = new float[w * h];
        int bgRgb = bg.getRGB() & 0xffffff;
        for (int i = 0; i < pix.length; i++) {
            pix[i] = bgRgb;
            zbuf[i] = Float.POSITIVE_INFINITY;
        }
        drawGrid(w, h, pix, zbuf);
        int lo = layerY;
        int hi = layerY + layers - 1;
        for (Blueprint.Cell c : blueprint.cells()) {
            boolean current = c.y() >= lo && c.y() <= hi;
            if (!showAll && !current) {
                continue;
            }
            boolean done = live && placed.contains(Blueprint.cellKey(c.x(), c.y(), c.z()));
            int rgb = BlockColors.color(c.block());
            if (rgb == 0) {
                rgb = BlockColors.OTHER;
            }
            float alpha = !current ? 0.35f : (live && !done ? 0.55f : 1f);
            drawCube(c.x(), c.y(), c.z(), rgb, alpha, w, h, pix, zbuf);
        }
        if (hover.kind == EditorHit.Kind.CELL) {
            drawCube(hover.x, hover.y, hover.z, 0xFFFFFF, 0.35f, w, h, pix, zbuf);
        } else if (hover.kind == EditorHit.Kind.LAYER && hover.canPlace) {
            drawCube(hover.placeX, hover.placeY, hover.placeZ, 0xFFFFFF, 0.2f, w, h, pix, zbuf);
        }
        img.setRGB(0, 0, w, h, pix, 0, w);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(img, 0, 0, null);
        g2.setColor(text);
        String line = blueprint.name() + "  " + blueprint.size() + " blocks  v" + blueprint.version()
                + "  y=" + layerY + (live ? "  live" : "  design");
        if (!hover.missed()) {
            line += "  " + (hover.kind == EditorHit.Kind.CELL
                    ? hover.x + "," + hover.y + "," + hover.z
                    : "place " + hover.placeX + "," + hover.placeY + "," + hover.placeZ);
        }
        g2.drawString(line, 10, h - 12);
    }

    private void drawGrid(int w, int h, int[] pix, float[] zbuf) {
        int[] b = blueprint.bounds();
        int cx = b == null ? 0 : (b[0] + b[3]) / 2;
        int cz = b == null ? 0 : (b[2] + b[5]) / 2;
        int span = b == null ? 8 : Math.max(8, 2 + Math.max(b[3] - b[0], b[5] - b[2]));
        int rgb = grid.getRGB() & 0xffffff;
        double[] a = new double[3];
        double[] d = new double[3];
        for (int x = cx - span; x <= cx + span; x++) {
            camera.project(x, layerY, cz - span, 0, 0, w, h, a);
            camera.project(x, layerY, cz + span, 0, 0, w, h, d);
            line(a, d, rgb, w, h, pix, zbuf);
        }
        for (int z = cz - span; z <= cz + span; z++) {
            camera.project(cx - span, layerY, z, 0, 0, w, h, a);
            camera.project(cx + span, layerY, z, 0, 0, w, h, d);
            line(a, d, rgb, w, h, pix, zbuf);
        }
    }

    private void drawCube(int x, int y, int z, int rgb, float alpha, int w, int h, int[] pix, float[] zbuf) {
        double[] e = new double[3];
        camera.eye(e);
        // 6 faces: +Y -Y +X -X +Z -Z, each 4 corners
        int[][] faces = {
                {0, 1, 2, 3, 0, 1, 0}, // +Y
                {4, 5, 6, 7, 0, -1, 0},
                {1, 5, 6, 2, 1, 0, 0},
                {0, 3, 7, 4, -1, 0, 0},
                {3, 2, 6, 7, 0, 0, 1},
                {0, 1, 5, 4, 0, 0, -1}
        };
        double[][] p = new double[8][3];
        // corners: 0 (0,1,0) 1 (1,1,0) 2 (1,1,1) 3 (0,1,1) 4 (0,0,0) 5 (1,0,0) 6 (1,0,1) 7 (0,0,1)
        int[] cx = {0, 1, 1, 0, 0, 1, 1, 0};
        int[] cy = {1, 1, 1, 1, 0, 0, 0, 0};
        int[] cz = {0, 0, 1, 1, 0, 0, 1, 1};
        for (int i = 0; i < 8; i++) {
            camera.project(x + cx[i], y + cy[i], z + cz[i], 0, 0, w, h, p[i]);
        }
        for (int[] f : faces) {
            double fnx = f[4], fny = f[5], fnz = f[6];
            double fx = x + 0.5 + fnx * 0.5;
            double fy = y + 0.5 + fny * 0.5;
            double fz = z + 0.5 + fnz * 0.5;
            if ((e[0] - fx) * fnx + (e[1] - fy) * fny + (e[2] - fz) * fnz <= 0) {
                continue; // back face
            }
            float shade = fny > 0 ? 1f : (fny < 0 ? 0.55f : (fnx != 0 ? 0.8f : 0.7f));
            int col = shadeRgb(rgb, shade, alpha);
            fillTri(p[f[0]], p[f[1]], p[f[2]], col, w, h, pix, zbuf);
            fillTri(p[f[0]], p[f[2]], p[f[3]], col, w, h, pix, zbuf);
        }
    }

    private static int shadeRgb(int rgb, float shade, float alpha) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xff) * shade));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xff) * shade));
        int b = Math.min(255, (int) ((rgb & 0xff) * shade));
        if (alpha >= 0.99f) {
            return (r << 16) | (g << 8) | b;
        }
        // fade toward a mid grey so ghosts stay visible
        int gr = 40, gg = 40, gb = 44;
        r = (int) (r * alpha + gr * (1 - alpha));
        g = (int) (g * alpha + gg * (1 - alpha));
        b = (int) (b * alpha + gb * (1 - alpha));
        return (r << 16) | (g << 8) | b;
    }

    private static void fillTri(double[] a, double[] b, double[] c, int rgb, int w, int h, int[] pix, float[] zbuf) {
        if (a[2] < 0.2 || b[2] < 0.2 || c[2] < 0.2) {
            return;
        }
        int minx = (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0])));
        int maxx = (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0])));
        int miny = (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1])));
        int maxy = (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1])));
        minx = Math.max(0, minx);
        maxx = Math.min(w - 1, maxx);
        miny = Math.max(0, miny);
        maxy = Math.min(h - 1, maxy);
        double area = edge(a[0], a[1], b[0], b[1], c[0], c[1]);
        if (Math.abs(area) < 1e-6) {
            return;
        }
        for (int y = miny; y <= maxy; y++) {
            for (int x = minx; x <= maxx; x++) {
                double w0 = edge(b[0], b[1], c[0], c[1], x + 0.5, y + 0.5) / area;
                double w1 = edge(c[0], c[1], a[0], a[1], x + 0.5, y + 0.5) / area;
                double w2 = edge(a[0], a[1], b[0], b[1], x + 0.5, y + 0.5) / area;
                if (w0 < 0 || w1 < 0 || w2 < 0) {
                    continue;
                }
                float z = (float) (w0 * a[2] + w1 * b[2] + w2 * c[2]);
                int i = y * w + x;
                if (z > 0.2f && z < zbuf[i]) {
                    zbuf[i] = z;
                    pix[i] = rgb;
                }
            }
        }
    }

    private static double edge(double ax, double ay, double bx, double by, double cx, double cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }

    private static void line(double[] a, double[] b, int rgb, int w, int h, int[] pix, float[] zbuf) {
        if (a[2] < 0.2 || b[2] < 0.2) {
            return;
        }
        int x0 = (int) Math.round(a[0]), y0 = (int) Math.round(a[1]);
        int x1 = (int) Math.round(b[0]), y1 = (int) Math.round(b[1]);
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps == 0) {
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(x0 + (x1 - x0) * t);
            int y = (int) Math.round(y0 + (y1 - y0) * t);
            if (x < 0 || y < 0 || x >= w || y >= h) {
                continue;
            }
            float z = (float) (a[2] * (1 - t) + b[2] * t);
            int p = y * w + x;
            if (z > 0.2f && z <= zbuf[p]) {
                zbuf[p] = z;
                pix[p] = rgb;
            }
        }
    }
}
