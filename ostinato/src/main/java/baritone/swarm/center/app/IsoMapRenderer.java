package baritone.swarm.center.app;

import baritone.swarm.center.TileStatus;
import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.center.feed.TerrainFeed;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Ostinato: 3D isometric software renderer for the command-center War room / RTS map.
 * Draws the same feed data as the 2D top-down painter — terrain columns with elevation and cliff skirts,
 * chunk grid, 3D leases, observations, area boxes, stacked blueprint/ghost voxels, 3D paths, depth-sorted
 * bot pawns, compass, and rotated-frustum minimap — using {@link IsoMapCamera}'s exact painter's order.
 */
final class IsoMapRenderer {

    private static final int DIRT_RGB = 0x866043;
    private static final int STONE_RGB = 0x7A7A7A;
    private static final int MAX_EDGE_SKIRT = 4;
    private static final int MAX_CLIFF_SKIRT = 24;

    private IsoMapRenderer() {}

    static void render(BufferedImage buffer, AppStyle style, IsoMapCamera cam, SwarmView view, char dim,
                       MapTerrain.Grid grid, MapCanvas canvas) {
        int w = buffer.getWidth();
        int h = buffer.getHeight();
        int[] pix = ((DataBufferInt) buffer.getRaster().getDataBuffer()).getData();
        Arrays.fill(pix, style.theme.background());

        if (canvas.showTerrain && grid != null && !grid.isEmpty()) {
            rasterTerrain(pix, w, h, cam, grid);
        }

        Graphics2D g = buffer.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(canvas.getFont());

        drawChunkGrid(g, w, h, style, cam, grid);
        if (canvas.showLeases) {
            drawLeases(g, w, h, style, cam, view, dim);
        }
        if (canvas.showPaths) {
            drawPaths(g, style, cam, view, dim, grid);
        }
        if (canvas.showObservations) {
            drawObservations(g, style, cam, view, dim, grid);
        }
        if (canvas.showDanger) {
            drawDanger(g, style, cam, view, dim, grid);
        }
        drawArea(g, style, cam, canvas);
        drawGhost(g, style, cam, canvas);
        drawBlueprint(g, style, cam, canvas);
        drawPaintCursor(g, style, cam, canvas);
        drawBots(g, style, cam, view, dim, canvas);
        drawSelectionBox(g, style, canvas);
        drawCompass(g, w, style, cam);
        drawMinimap(g, w, h, style, cam, view, dim);
        g.dispose();
    }

    // -------------------------------------------------------------------------
    // 1. Voxel Terrain Rasterizer (direct int[] backbuffer, exact painter order)
    // -------------------------------------------------------------------------

    private static void rasterTerrain(int[] pix, int w, int h, IsoMapCamera cam, MapTerrain.Grid grid) {
        int zoom = cam.base().zoom();
        double hw = cam.halfW();
        double hh = cam.halfH();
        double vy = cam.vertScale();

        int[] vis = cam.visibleRotGridBounds(grid.minY, grid.maxY);
        // Also compute rotated bounds of the terrain grid itself so we never iterate outside cached slices.
        int gx0 = Integer.MAX_VALUE, gx1 = Integer.MIN_VALUE;
        int gz0 = Integer.MAX_VALUE, gz1 = Integer.MIN_VALUE;
        int[] wxCorners = {grid.minX, grid.minX + grid.width - 1};
        int[] wzCorners = {grid.minZ, grid.minZ + grid.depth - 1};
        for (int wx : wxCorners) {
            for (int wz : wzCorners) {
                int gx = cam.rotGridX(wx, wz);
                int gz = cam.rotGridZ(wx, wz);
                if (gx < gx0) gx0 = gx;
                if (gx > gx1) gx1 = gx;
                if (gz < gz0) gz0 = gz;
                if (gz > gz1) gz1 = gz;
            }
        }
        gx0 = Math.max(gx0, vis[0]);
        gx1 = Math.min(gx1, vis[1]);
        gz0 = Math.max(gz0, vis[2]);
        gz1 = Math.min(gz1, vis[3]);
        if (gx0 > gx1 || gz0 > gz1) {
            return;
        }

        int frDx = cam.frontRightDx(), frDz = cam.frontRightDz();
        int flDx = cam.frontLeftDx(), flDz = cam.frontLeftDz();
        double refY = cam.refY();
        boolean drawBevel = zoom >= 8;

        int iHw = Math.max(1, (int) Math.round(hw));
        int iHh = Math.max(1, (int) Math.ceil(hh));

        // Exact back-to-front order: increasing gx (outer), increasing gz (inner).
        for (int gx = gx0; gx <= gx1; gx++) {
            for (int gz = gz0; gz <= gz1; gz++) {
                int wx = cam.worldXFromRot(gx, gz);
                int wz = cam.worldZFromRot(gx, gz);
                if (!grid.contains(wx, wz)) {
                    continue;
                }
                int idx = (wz - grid.minZ) * grid.width + (wx - grid.minX);
                int baseRgb = grid.rgb[idx];
                if (baseRgb == 0) {
                    continue;
                }
                int y = grid.height[idx];
                if (y == TerrainFeed.UNKNOWN_Y) {
                    y = grid.medianY;
                }
                byte kind = grid.kind[idx];
                double topY = kind == MapTerrain.Grid.KIND_WATER ? y + 0.85 : y + 1.0;

                double sxD = cam.screenX(wx + 0.5, wz + 0.5);
                double syD = cam.screenY(wx + 0.5, topY, wz + 0.5);
                int cx = (int) Math.round(sxD);
                int cy = (int) Math.round(syD);
                if (cx + iHw < 0 || cx - iHw >= w || cy - iHh >= h) {
                    continue;
                }

                // Height-based subtle tint so hills and valleys pop even on uniform grass/stone.
                float elevMul = clamp(1.0f + (float) ((y - refY) * 0.008), 0.84f, 1.16f);
                int topColor = shade(baseRgb, elevMul);

                // Neighbor heights on the two visible front sides (+rz left, +rx right).
                int leftNy = grid.heightAt(wx + flDx, wz + flDz);
                int rightNy = grid.heightAt(wx + frDx, wz + frDz);
                int leftBlocks = skirtDepth(y, leftNy);
                int rightBlocks = skirtDepth(y, rightNy);
                int maxSkirtPx = (int) Math.ceil(Math.max(leftBlocks, rightBlocks) * vy);
                if (cy + iHh + maxSkirtPx < 0) {
                    continue;
                }

                // Draw visible left-front (+rz) and right-front (+rx) vertical cliff faces only where exposed.
                if (leftBlocks > 0) {
                    int pxH = Math.max(2, (int) Math.ceil(leftBlocks * vy) + 1);
                    drawSideFace(pix, w, h, cx, cy, iHw, iHh, pxH, true, baseRgb, kind, elevMul * 0.78f, zoom);
                }
                if (rightBlocks > 0) {
                    int pxH = Math.max(2, (int) Math.ceil(rightBlocks * vy) + 1);
                    drawSideFace(pix, w, h, cx, cy, iHw, iHh, pxH, false, baseRgb, kind, elevMul * 0.60f, zoom);
                }

                // Draw top diamond face (+Y) cleanly over the top seam of the side faces.
                fillDiamond(pix, w, h, cx, cy, iHw, iHh, topColor, drawBevel ? shade(topColor, 0.92f) : 0);
            }
        }
    }

    private static int skirtDepth(int y, int neighborY) {
        if (neighborY == TerrainFeed.UNKNOWN_Y) {
            return MAX_EDGE_SKIRT;
        }
        int diff = y - neighborY;
        if (diff <= 0) {
            return 0; // Flush or recessed against front neighbor: no dark side face needed
        }
        return Math.min(MAX_CLIFF_SKIRT, diff);
    }

    private static void fillDiamond(int[] pix, int w, int h, int cx, int cy, int hw, int hh, int rgb, int edgeRgb) {
        int y0 = Math.max(0, cy - hh);
        int y1 = Math.min(h - 1, cy + hh);
        for (int py = y0; py <= y1; py++) {
            int dy = Math.abs(py - cy);
            // Exact half-span with +0.5 bias tiles seamlessly at both even and odd zoom levels.
            int span = hh <= 0 ? hw : Math.min(hw, (int) Math.floor(hw * (1.0 - dy / (double) (hh + 0.5))) + 1);
            int x0 = Math.max(0, cx - span);
            int x1 = Math.min(w - 1, cx + span);
            int row = py * w;
            if (edgeRgb != 0 && (py == cy - hh || py == cy + hh)) {
                for (int px = x0; px <= x1; px++) {
                    pix[row + px] = edgeRgb;
                }
            } else {
                for (int px = x0; px <= x1; px++) {
                    pix[row + px] = rgb;
                }
                if (edgeRgb != 0 && span > 0) {
                    if (cx - span >= 0 && cx - span < w) pix[row + cx - span] = edgeRgb;
                    if (cx + span >= 0 && cx + span < w) pix[row + cx + span] = edgeRgb;
                }
            }
        }
    }

    /**
     * Fill either the left-front ({@code left == true}, from {@code cx - hw} to {@code cx}) or right-front
     * ({@code left == false}, from {@code cx} to {@code cx + hw}) vertical face of a column.
     * Grass columns transition from a thin grass rim to dirt and deeper stone on cliffs.
     */
    private static void drawSideFace(int[] pix, int w, int h, int cx, int cy, int hw, int hh, int pxH,
                                     boolean left, int baseRgb, byte kind, float shadeMul, int zoom) {
        int xStart = left ? cx - hw : cx;
        int xEnd = left ? cx : cx + hw;
        int c0 = Math.max(0, xStart);
        int c1 = Math.min(w - 1, xEnd);
        if (c0 > c1) {
            return;
        }
        int rimColor = shade(baseRgb, shadeMul);
        int dirtColor = kind == MapTerrain.Grid.KIND_GRASS ? shade(DIRT_RGB, shadeMul) : rimColor;
        int stoneColor = kind == MapTerrain.Grid.KIND_GRASS ? shade(STONE_RGB, shadeMul) : shade(baseRgb, shadeMul * 0.90f);
        int grassLip = Math.max(1, zoom / 4);
        int dirtBand = Math.max(2, zoom * 3);

        for (int px = c0; px <= c1; px++) {
            double t = left ? (px - (cx - hw)) / (double) hw : ((cx + hw) - px) / (double) hw;
            int topRow = cy + (int) Math.floor(t * hh);
            int y0 = Math.max(0, topRow);
            int y1 = Math.min(h - 1, topRow + pxH);
            for (int py = y0; py <= y1; py++) {
                int d = py - topRow;
                int col = kind == MapTerrain.Grid.KIND_GRASS
                        ? (d < grassLip ? rimColor : (d < dirtBand ? dirtColor : stoneColor))
                        : rimColor;
                pix[py * w + px] = col;
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. Isometric Chunk Grid (draped over terrain where cached)
    // -------------------------------------------------------------------------

    private static void drawChunkGrid(Graphics2D g, int w, int h, AppStyle style, IsoMapCamera cam,
                                      MapTerrain.Grid grid) {
        int minY = grid != null && !grid.isEmpty() ? grid.minY : (int) cam.refY();
        int maxY = grid != null && !grid.isEmpty() ? grid.maxY : (int) cam.refY();
        int[] vis = cam.visibleRotGridBounds(minY, maxY);

        int wxMin = Integer.MAX_VALUE, wxMax = Integer.MIN_VALUE;
        int wzMin = Integer.MAX_VALUE, wzMax = Integer.MIN_VALUE;
        for (int gx : new int[]{vis[0], vis[1]}) {
            for (int gz : new int[]{vis[2], vis[3]}) {
                int wx = cam.worldXFromRot(gx, gz);
                int wz = cam.worldZFromRot(gx, gz);
                if (wx < wxMin) wxMin = wx;
                if (wx > wxMax) wxMax = wx;
                if (wz < wzMin) wzMin = wz;
                if (wz > wzMax) wzMax = wz;
            }
        }
        int x0 = wxMin & ~15;
        int z0 = wzMin & ~15;
        // Guard against extreme zoom-out drawing too many chunk lines.
        int step = (wxMax - wxMin) > 768 ? 32 : 16;

        Color lineCol = alpha(style.theme.border(), 135);
        Color labelCol = new Color(style.theme.faint());
        g.setStroke(new BasicStroke(1f));
        g.setColor(lineCol);
        double baseY = cam.refY() + 0.05;

        for (int x = x0; x <= wxMax + step; x += step) {
            double[] a = cam.worldToScreen(x, sampleY(grid, x, z0, baseY), z0);
            for (int z = z0 + 16; z <= wzMax + step; z += 16) {
                double[] b = cam.worldToScreen(x, sampleY(grid, x, z, baseY), z);
                if (segmentVisible(a, b, w, h)) {
                    g.drawLine((int) Math.round(a[0]), (int) Math.round(a[1]),
                            (int) Math.round(b[0]), (int) Math.round(b[1]));
                }
                a = b;
            }
        }
        for (int z = z0; z <= wzMax + step; z += step) {
            double[] a = cam.worldToScreen(x0, sampleY(grid, x0, z, baseY), z);
            for (int x = x0 + 16; x <= wxMax + step; x += 16) {
                double[] b = cam.worldToScreen(x, sampleY(grid, x, z, baseY), z);
                if (segmentVisible(a, b, w, h)) {
                    g.drawLine((int) Math.round(a[0]), (int) Math.round(a[1]),
                            (int) Math.round(b[0]), (int) Math.round(b[1]));
                }
                if (cam.base().zoom() >= 3 && b[0] >= 8 && b[0] < w - 44 && b[1] >= 14 && b[1] < h - 8) {
                    g.setColor(labelCol);
                    g.drawString((x >> 4) + "," + (z >> 4), (int) Math.round(b[0]) + 3, (int) Math.round(b[1]) - 3);
                    g.setColor(lineCol);
                }
                a = b;
            }
        }
    }

    private static double sampleY(MapTerrain.Grid grid, int x, int z, double fallback) {
        if (grid == null || grid.isEmpty()) {
            return fallback;
        }
        int h = grid.heightAt(x, z);
        return h == TerrainFeed.UNKNOWN_Y ? fallback : h + 1.02;
    }

    private static boolean segmentVisible(double[] a, double[] b, int w, int h) {
        return !(Math.max(a[0], b[0]) < 0 || Math.min(a[0], b[0]) > w
                || Math.max(a[1], b[1]) < 0 || Math.min(a[1], b[1]) > h);
    }

    // -------------------------------------------------------------------------
    // 3. 3D Leases & Observations
    // -------------------------------------------------------------------------

    private static void drawLeases(Graphics2D g, int w, int h, AppStyle style, IsoMapCamera cam,
                                   SwarmView view, char dim) {
        for (MapOverlay.LeaseMark lease : MapOverlay.leases(view.coordinator(), dim)) {
            int[] box = lease.box();
            if (box == null || box.length < 6) {
                continue;
            }
            int rgb = switch (lease.type()) {
                case "vein" -> style.theme.warn();
                case "chest" -> style.theme.ok();
                default -> style.theme.accent();
            };
            int edgeAlpha = lease.live() ? 235 : 110;
            int fillAlpha = lease.live() ? 42 : 20;
            Stroke stroke = "vein".equals(lease.type())
                    ? new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f)
                    : new BasicStroke(2f);

            if ("chest".equals(lease.type())) {
                int cx = (box[0] + box[3]) / 2;
                int cy = (box[1] + box[4]) / 2;
                int cz = (box[2] + box[5]) / 2;
                drawVoxelCube(g, cam, cx, cy, cz, rgb, lease.live() ? 230 : 110, alpha(rgb, edgeAlpha));
            } else {
                drawWireBox(g, cam, box[0], box[1], box[2], box[3] + 1, box[4] + 1, box[5] + 1,
                        alpha(rgb, edgeAlpha), alpha(rgb, fillAlpha), stroke);
            }

            MapOverlay.Rect r = screenBoundsOfBox(cam, box);
            int[] at = MapOverlay.labelAt(r, w, h, 8, 12);
            drawTag(g, style, lease.label(), at[0], at[1], style.theme.text());
        }
    }

    private static void drawObservations(Graphics2D g, AppStyle style, IsoMapCamera cam, SwarmView view,
                                         char dim, MapTerrain.Grid grid) {
        g.setStroke(new BasicStroke(2f));
        Color accent = new Color(style.theme.accent());
        Color stem = alpha(style.theme.accent(), 120);
        for (MapOverlay.ObsMark obs : MapOverlay.observations(view.coordinator(), dim)) {
            int[] box = obs.box();
            if (box == null || box.length < 6) {
                continue;
            }
            double wx = (box[0] + box[3] + 1) * 0.5;
            double wy = (box[1] + box[4] + 1) * 0.5;
            double wz = (box[2] + box[5] + 1) * 0.5;
            double[] pt = cam.worldToScreen(wx, wy, wz);
            int sx = (int) Math.round(pt[0]);
            int sy = (int) Math.round(pt[1]);

            if (grid != null && !grid.isEmpty()) {
                int sh = grid.heightAt((int) Math.floor(wx), (int) Math.floor(wz));
                if (sh != TerrainFeed.UNKNOWN_Y && Math.abs((sh + 1.0) - wy) > 1.5) {
                    double[] ground = cam.worldToScreen(wx, sh + 1.0, wz);
                    g.setColor(stem);
                    g.drawLine(sx, sy, (int) Math.round(ground[0]), (int) Math.round(ground[1]));
                }
            }
            g.setColor(accent);
            MapCanvas.drawObsShape(g, obs.kind(), obs.state(), obs.suspect(), sx, sy);
        }
    }

    private static void drawDanger(Graphics2D g, AppStyle style, IsoMapCamera cam, SwarmView view,
                                   char dim, MapTerrain.Grid grid) {
        Color dangerCol = new Color(style.theme.danger());
        Color warnCol = new Color(style.theme.warn());
        if (grid != null && !grid.isEmpty()) {
            int[] vis = cam.visibleRotGridBounds(grid.minY, grid.maxY);
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
            int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int gx : new int[]{vis[0], vis[1]}) {
                for (int gz : new int[]{vis[2], vis[3]}) {
                    int wx = cam.worldXFromRot(gx, gz);
                    int wz = cam.worldZFromRot(gx, gz);
                    if (wx < x0) x0 = wx;
                    if (wx > x1) x1 = wx;
                    if (wz < z0) z0 = wz;
                    if (wz > z1) z1 = wz;
                }
            }
            for (MapDanger.Hazard hz : MapDanger.terrainHazards(grid, x0, z0, x1, z1)) {
                double[] pt = cam.worldToScreen(hz.x(), hz.y() + 0.05, hz.z());
                int sx = (int) Math.round(pt[0]);
                int sy = (int) Math.round(pt[1]);
                if (hz.kind() == MapDanger.Kind.LAVA) {
                    int hw = Math.max(2, (int) Math.round(cam.halfW()));
                    int hh = Math.max(1, (int) Math.round(cam.halfH()));
                    int[] xs = {sx, sx + hw, sx, sx - hw};
                    int[] ys = {sy - hh, sy, sy + hh, sy};
                    g.setColor(alpha(style.theme.danger(), 150));
                    g.fillPolygon(xs, ys, 4);
                    g.setColor(dangerCol);
                    g.setStroke(new BasicStroke(1.2f));
                    g.drawPolygon(xs, ys, 4);
                } else {
                    g.setColor(warnCol);
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawLine(sx - 3, sy - 3, sx + 3, sy + 3);
                    g.drawLine(sx - 3, sy + 3, sx + 3, sy - 3);
                }
            }
        }
        for (MapDanger.Hazard hz : MapDanger.dynamicHazards(view, dim)) {
            double[] pt = cam.worldToScreen(hz.x(), hz.y(), hz.z());
            int sx = (int) Math.round(pt[0]);
            int sy = (int) Math.round(pt[1]);
            g.setColor(dangerCol);
            g.setStroke(new BasicStroke(2f));
            if (hz.kind() == MapDanger.Kind.BOT_DANGER) {
                g.drawOval(sx - 16, sy - 9, 32, 18);
            } else {
                double[] head = cam.worldToScreen(hz.x(), hz.y() + 1.4, hz.z());
                int hx = (int) Math.round(head[0]);
                int hy = (int) Math.round(head[1]);
                g.drawLine(sx, sy, hx, hy);
                int[] xs = {hx, hx - 7, hx + 7};
                int[] ys = {hy - 7, hy + 5, hy + 5};
                g.fillPolygon(xs, ys, 3);
                drawTag(g, style, "! " + hz.label(), hx + 9, hy + 4, style.theme.danger());
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. 3D Area Drag, Schematic Ghost, RTS Live Blueprint, & Paint Cursor
    // -------------------------------------------------------------------------

    private static void drawArea(Graphics2D g, AppStyle style, IsoMapCamera cam, MapCanvas canvas) {
        Color accent = new Color(style.theme.accent());
        Color fill = alpha(style.theme.accent(), 36);
        g.setStroke(new BasicStroke(2f));

        if (canvas.isAreaDraggingMoved()) {
            int[] live = canvas.liveAreaWorldBounds();
            if (live != null) {
                int y = (int) Math.round(cam.refY());
                drawGroundQuad(g, cam, live[0], live[1], live[2] + 1, live[3] + 1, y + 1.05, accent, fill);
            }
        }
        int[] area = canvas.areaBounds();
        if (area == null) {
            return;
        }
        AreaOrders.Box full = canvas.areaBox();
        int y1 = full != null ? full.y1() : (int) Math.round(cam.refY());
        int y2 = full != null ? full.y2() : y1;
        drawWireBox(g, cam, area[0], y1, area[1], area[2] + 1, y2 + 1, area[3] + 1,
                accent, fill, new BasicStroke(2f));

        double[] top = cam.worldToScreen((area[0] + area[2] + 1) * 0.5, y2 + 1.2, (area[1] + area[3] + 1) * 0.5);
        String dimLabel = (area[2] - area[0] + 1) + " x " + (area[3] - area[1] + 1)
                + (y2 > y1 ? " x " + (y2 - y1 + 1) : "");
        drawTag(g, style, dimLabel, (int) Math.round(top[0]) - 18, Math.max(14, (int) Math.round(top[1]) - 6),
                style.theme.text());
    }

    private static void drawGhost(Graphics2D g, AppStyle style, IsoMapCamera cam, MapCanvas canvas) {
        List<int[]> cells = canvas.ghostCells();
        if (cells.isEmpty()) {
            return;
        }
        int defaultY = (int) Math.round(cam.refY());
        List<int[]> sorted = sortCells(cam, cells, defaultY, 3);
        Color edge = alpha(style.theme.accent(), 180);
        for (int[] c : sorted) {
            int x = c[0];
            int z = c[1];
            int rgb = c[2] == 0 ? style.theme.accent() : c[2];
            int y = c.length > 3 ? c[3] : defaultY;
            drawVoxelCube(g, cam, x, y, z, rgb, 155, edge);
        }
    }

    private static void drawBlueprint(Graphics2D g, AppStyle style, IsoMapCamera cam, MapCanvas canvas) {
        List<int[]> cells = canvas.blueprintCells();
        if (cells.isEmpty()) {
            return;
        }
        int defaultY = canvas.paintLayerY();
        List<int[]> sorted = sortCells(cam, cells, defaultY, 4);
        Color pendingEdge = new Color(style.theme.accent());
        Color solidEdge = new Color(0, 0, 0, 75);
        for (int[] c : sorted) {
            int x = c[0];
            int z = c[1];
            int rgb = c[2] == 0 ? style.theme.accent() : c[2];
            boolean placed = c.length > 3 && c[3] == 1;
            int y = c.length > 4 ? c[4] : defaultY;
            drawVoxelCube(g, cam, x, y, z, rgb, placed ? 250 : 120, placed ? solidEdge : pendingEdge);
        }
    }

    private static void drawPaintCursor(Graphics2D g, AppStyle style, IsoMapCamera cam, MapCanvas canvas) {
        if (!canvas.paintArmed || !canvas.hasHoverBlock()) {
            return;
        }
        int bx = canvas.hoverBlockX();
        int bz = canvas.hoverBlockZ();
        int y0 = canvas.paintLayerY();
        int h = Math.max(1, canvas.paintLayers());
        drawWireBox(g, cam, bx, y0, bz, bx + 1, y0 + h, bz + 1,
                new Color(style.theme.accent()), alpha(style.theme.accent(), 55), new BasicStroke(2f));
    }

    private static List<int[]> sortCells(IsoMapCamera cam, List<int[]> cells, int defaultY, int yIndex) {
        List<int[]> sorted = new ArrayList<>(cells);
        sorted.sort((a, b) -> {
            int gxa = cam.rotGridX(a[0], a[1]);
            int gxb = cam.rotGridX(b[0], b[1]);
            if (gxa != gxb) return Integer.compare(gxa, gxb);
            int gza = cam.rotGridZ(a[0], a[1]);
            int gzb = cam.rotGridZ(b[0], b[1]);
            if (gza != gzb) return Integer.compare(gza, gzb);
            int ya = a.length > yIndex ? a[yIndex] : defaultY;
            int yb = b.length > yIndex ? b[yIndex] : defaultY;
            return Integer.compare(ya, yb);
        });
        return sorted;
    }

    // -------------------------------------------------------------------------
    // 5. 3D Paths & Depth-Sorted Bot Pawns
    // -------------------------------------------------------------------------

    private static void drawPaths(Graphics2D g, AppStyle style, IsoMapCamera cam, SwarmView view,
                                  char dim, MapTerrain.Grid grid) {
        for (SwarmView.BotState b : view.bots()) {
            BotFeed.Pos p = b.feed().pos();
            if (p == null || p.dim() != dim) {
                continue;
            }
            BotFeed.Route path = b.feed().path();
            int idRgb = style.theme.identity(b.name());
            Color col = new Color(idRgb);
            double[] prev = cam.worldToScreen(p.x(), p.y() + 0.25, p.z());

            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int[] node : path.nodes()) {
                if (node == null || node.length < 3) {
                    continue;
                }
                double[] next = cam.worldToScreen(node[0] + 0.5, node[1] + 0.25, node[2] + 0.5);
                // Subtle drop shadow under elevated path segments
                g.setColor(new Color(0, 0, 0, 80));
                g.drawLine((int) Math.round(prev[0]), (int) Math.round(prev[1]) + 2,
                        (int) Math.round(next[0]), (int) Math.round(next[1]) + 2);
                g.setColor(col);
                g.drawLine((int) Math.round(prev[0]), (int) Math.round(prev[1]),
                        (int) Math.round(next[0]), (int) Math.round(next[1]));
                prev = next;
            }

            int[] goal = path.goalPos();
            if (goal != null && goal.length >= 3) {
                double[] gp = cam.worldToScreen(goal[0] + 0.5, goal[1] + 0.15, goal[2] + 0.5);
                double[] gt = cam.worldToScreen(goal[0] + 0.5, goal[1] + 1.6, goal[2] + 0.5);
                int gx = (int) Math.round(gp[0]);
                int gy = (int) Math.round(gp[1]);
                g.setColor(new Color(style.theme.accent()));
                g.setStroke(new BasicStroke(2f));
                int[] dxs = {gx, gx - 8, gx, gx + 8};
                int[] dys = {gy - 4, gy, gy + 4, gy};
                g.drawPolygon(dxs, dys, 4);
                g.drawLine(gx, gy, (int) Math.round(gt[0]), (int) Math.round(gt[1]));
            }
        }
    }

    private static void drawBots(Graphics2D g, AppStyle style, IsoMapCamera cam, SwarmView view,
                                 char dim, MapCanvas canvas) {
        List<SwarmView.BotState> bots = new ArrayList<>();
        for (SwarmView.BotState b : view.bots()) {
            BotFeed.Pos p = b.feed().pos();
            if (p != null && p.dim() == dim) {
                bots.add(b);
            }
        }
        bots.sort(Comparator.comparingDouble(b -> {
            BotFeed.Pos p = b.feed().pos();
            return cam.depth(p.x(), p.y(), p.z());
        }));

        int zoom = cam.base().zoom();
        for (SwarmView.BotState b : bots) {
            BotFeed.Pos p = b.feed().pos();
            double[] feet = cam.worldToScreen(p.x(), p.y(), p.z());
            double[] head = cam.worldToScreen(p.x(), p.y() + 1.8, p.z());
            int fx = (int) Math.round(feet[0]);
            int fy = (int) Math.round(feet[1]);
            int hx = (int) Math.round(head[0]);
            int hy = (int) Math.round(head[1]);

            int idRgb = style.theme.identity(b.name());
            Color idColor = new Color(idRgb);
            TileStatus status = Alerts.status(b, view.alertsFor(b.name()));

            // 1. Ground shadow ellipse at bot's feet
            g.setColor(new Color(0, 0, 0, 95));
            g.fillOval(fx - 8, fy - 4, 16, 8);

            // 2. Isometric selection ring at feet
            if (canvas.selection.contains(b.name())) {
                g.setColor(new Color(style.theme.accent()));
                g.setStroke(new BasicStroke(2.2f));
                g.drawOval(fx - 13, fy - 7, 26, 14);
            }

            // 3. 3D projected facing arrow at feet
            double[] dir = cam.facingScreenDir(p.yaw());
            g.setColor(idColor);
            g.setStroke(new BasicStroke(2f));
            int arrowLen = 15;
            g.drawLine(fx, fy, fx + (int) Math.round(dir[0] * arrowLen), fy + (int) Math.round(dir[1] * arrowLen));

            // 4. Shaded 3D pawn body standing vertically from feet to head
            int bodyHalfW = Math.max(3, Math.min(7, zoom / 2 + 2));
            int bodyTop = Math.min(fy - 8, hy);
            int bodyH = Math.max(8, fy - bodyTop);
            // Left shaded half and right darker half for 3D cylindrical/prismatic look
            g.setColor(new Color(shade(idRgb, 1.05f)));
            g.fillRect(fx - bodyHalfW, bodyTop, bodyHalfW, bodyH);
            g.setColor(new Color(shade(idRgb, 0.72f)));
            g.fillRect(fx, bodyTop, bodyHalfW, bodyH);
            g.setColor(new Color(0, 0, 0, 140));
            g.setStroke(new BasicStroke(1f));
            g.drawRect(fx - bodyHalfW, bodyTop, bodyHalfW * 2, bodyH);

            // 5. Status glyph icon floating cleanly above the pawn head
            int iconY = bodyTop - 7;
            g.setColor(idColor);
            g.setStroke(new BasicStroke(2f));
            MapCanvas.drawShape(g, status.level(), hx, iconY);

            // 6. Readable role + name badge pill
            String role = b.feed().role();
            String tag = role == null || "idle".equals(role) ? "" : "[" + MapCanvas.roleLetter(role) + "] ";
            String label = status.level().glyph + " " + tag + b.name();
            drawTag(g, style, label, hx + 10, iconY + 4, style.theme.text());
        }
    }

    // -------------------------------------------------------------------------
    // 6. Selection Drag Box, Compass, & Isometric Minimap
    // -------------------------------------------------------------------------

    private static void drawSelectionBox(Graphics2D g, AppStyle style, MapCanvas canvas) {
        int[] box = canvas.selectionBoxScreen();
        if (box == null) {
            return;
        }
        g.setColor(new Color(style.theme.accent()));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f));
        g.drawRect(box[0], box[1], box[2], box[3]);
    }

    private static void drawCompass(Graphics2D g, int w, AppStyle style, IsoMapCamera cam) {
        int cx = w - 34;
        int cy = 32;
        if (cx < 40) {
            return;
        }
        g.setColor(alpha(style.theme.surface(), 215));
        g.fillOval(cx - 22, cy - 22, 44, 44);
        g.setColor(new Color(style.theme.border()));
        g.setStroke(new BasicStroke(1.2f));
        g.drawOval(cx - 22, cy - 22, 44, 44);

        double[] n = cam.northScreenDir();
        // South tail (muted) + North pointer (accent)
        g.setColor(new Color(style.theme.faint()));
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(cx, cy, cx - (int) Math.round(n[0] * 10), cy - (int) Math.round(n[1] * 10));
        int nx = cx + (int) Math.round(n[0] * 10);
        int ny = cy + (int) Math.round(n[1] * 10);
        g.setColor(new Color(style.theme.accent()));
        g.setStroke(new BasicStroke(2.2f));
        g.drawLine(cx, cy, nx, ny);
        g.fillOval(nx - 2, ny - 2, 5, 5);

        g.setColor(new Color(style.theme.text()));
        g.setFont(AppStyle.font(Font.BOLD, 10f));
        FontMetrics nfm = g.getFontMetrics();
        int lx = cx + (int) Math.round(n[0] * 15) - nfm.stringWidth("N") / 2;
        int ly = cy + (int) Math.round(n[1] * 15) + nfm.getAscent() / 2 - 1;
        g.drawString("N", lx, ly);

        g.setFont(AppStyle.font(Font.PLAIN, 10f));
        g.setColor(new Color(style.theme.muted()));
        String badge = "ISO " + cam.lookLabel() + " · Q/E";
        FontMetrics fm = g.getFontMetrics();
        int bw = fm.stringWidth(badge) + 10;
        g.setColor(alpha(style.theme.surface(), 200));
        g.fillRect(cx - 28 - bw, cy - 9, bw, 18);
        g.setColor(new Color(style.theme.border()));
        g.drawRect(cx - 28 - bw, cy - 9, bw, 18);
        g.setColor(new Color(style.theme.muted()));
        g.drawString(badge, cx - 28 - bw + 5, cy + 4);
    }

    private static void drawMinimap(Graphics2D g, int w, int h, AppStyle style, IsoMapCamera cam,
                                    SwarmView view, char dim) {
        int mw = 168;
        int mh = 112;
        int mx = w - mw - 8;
        int my = h - mh - 8;
        if (mx < 0 || my < 0) {
            return;
        }
        g.setColor(new Color(style.theme.surface()));
        g.fillRect(mx, my, mw, mh);
        g.setColor(new Color(style.theme.border()));
        g.setStroke(new BasicStroke(1f));
        g.drawRect(mx, my, mw, mh);

        double[][] corners = {
                cam.screenToWorld(0, 0),
                cam.screenToWorld(w, 0),
                cam.screenToWorld(w, h),
                cam.screenToWorld(0, h)
        };
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (double[] c : corners) {
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minZ = Math.min(minZ, c[1]);
            maxZ = Math.max(maxZ, c[1]);
        }
        for (SwarmView.BotState bot : view.bots()) {
            BotFeed.Pos p = bot.feed().pos();
            if (p == null || p.dim() != dim) {
                continue;
            }
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        double spanX = Math.max(16, maxX - minX);
        double spanZ = Math.max(16, maxZ - minZ);
        double scale = Math.min((mw - 12) / spanX, (mh - 12) / spanZ);

        int[] px = new int[4];
        int[] py = new int[4];
        for (int i = 0; i < 4; i++) {
            px[i] = mx + 6 + (int) Math.round((corners[i][0] - minX) * scale);
            py[i] = my + 6 + (int) Math.round((corners[i][1] - minZ) * scale);
        }
        g.setColor(new Color(style.theme.accent()));
        g.drawPolygon(px, py, 4);

        for (SwarmView.BotState bot : view.bots()) {
            BotFeed.Pos p = bot.feed().pos();
            if (p == null || p.dim() != dim) {
                continue;
            }
            int sx = mx + 6 + (int) Math.round((p.x() - minX) * scale);
            int sy = my + 6 + (int) Math.round((p.z() - minZ) * scale);
            g.setColor(new Color(style.theme.identity(bot.name())));
            g.fillRect(sx - 2, sy - 2, 4, 4);
        }
    }

    // -------------------------------------------------------------------------
    // 7. 3D Geometry Primitives (Voxel Cubes, Wire Boxes, Ground Quads, Tags)
    // -------------------------------------------------------------------------

    static void drawVoxelCube(Graphics2D g, IsoMapCamera cam, int x, int y, int z, int rgb, int alphaVal, Color edge) {
        double[] c = cam.worldToScreen(x + 0.5, y + 1.0, z + 0.5);
        int cx = (int) Math.round(c[0]);
        int cy = (int) Math.round(c[1]);
        int hw = Math.max(1, (int) Math.round(cam.halfW()));
        int hh = Math.max(1, (int) Math.round(cam.halfH()));
        int vh = Math.max(1, (int) Math.round(cam.vertScale()));

        // Top face (+Y)
        int[] tx = {cx, cx + hw, cx, cx - hw};
        int[] ty = {cy - hh, cy, cy + hh, cy};
        g.setColor(alpha(rgb, alphaVal));
        g.fillPolygon(tx, ty, 4);

        // Left-front face (+rz)
        int[] lx = {cx - hw, cx, cx, cx - hw};
        int[] ly = {cy, cy + hh, cy + hh + vh, cy + vh};
        g.setColor(alpha(shade(rgb, 0.78f), alphaVal));
        g.fillPolygon(lx, ly, 4);

        // Right-front face (+rx)
        int[] rx = {cx, cx + hw, cx + hw, cx};
        int[] ry = {cy + hh, cy, cy + vh, cy + hh + vh};
        g.setColor(alpha(shade(rgb, 0.58f), alphaVal));
        g.fillPolygon(rx, ry, 4);

        if (edge != null && hw >= 3) {
            g.setColor(edge);
            g.setStroke(new BasicStroke(1f));
            g.drawPolygon(tx, ty, 4);
            g.drawPolygon(lx, ly, 4);
            g.drawPolygon(rx, ry, 4);
        }
    }

    static void drawWireBox(Graphics2D g, IsoMapCamera cam, double x1, double y1, double z1,
                            double x2, double y2, double z2, Color edge, Color floorFill, Stroke stroke) {
        double[][] v = {
                cam.worldToScreen(x1, y1, z1),
                cam.worldToScreen(x2, y1, z1),
                cam.worldToScreen(x2, y1, z2),
                cam.worldToScreen(x1, y1, z2),
                cam.worldToScreen(x1, y2, z1),
                cam.worldToScreen(x2, y2, z1),
                cam.worldToScreen(x2, y2, z2),
                cam.worldToScreen(x1, y2, z2)
        };
        if (floorFill != null) {
            int[] fx = {(int) Math.round(v[0][0]), (int) Math.round(v[1][0]),
                    (int) Math.round(v[2][0]), (int) Math.round(v[3][0])};
            int[] fy = {(int) Math.round(v[0][1]), (int) Math.round(v[1][1]),
                    (int) Math.round(v[2][1]), (int) Math.round(v[3][1])};
            g.setColor(floorFill);
            g.fillPolygon(fx, fy, 4);
        }
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        g.setColor(edge);
        g.setStroke(stroke);
        for (int[] e : edges) {
            g.drawLine((int) Math.round(v[e[0]][0]), (int) Math.round(v[e[0]][1]),
                    (int) Math.round(v[e[1]][0]), (int) Math.round(v[e[1]][1]));
        }
    }

    private static void drawGroundQuad(Graphics2D g, IsoMapCamera cam, double x1, double z1,
                                       double x2, double z2, double y, Color edge, Color fill) {
        double[][] v = {
                cam.worldToScreen(x1, y, z1),
                cam.worldToScreen(x2, y, z1),
                cam.worldToScreen(x2, y, z2),
                cam.worldToScreen(x1, y, z2)
        };
        int[] xs = new int[4];
        int[] ys = new int[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = (int) Math.round(v[i][0]);
            ys[i] = (int) Math.round(v[i][1]);
        }
        if (fill != null) {
            g.setColor(fill);
            g.fillPolygon(xs, ys, 4);
        }
        g.setColor(edge);
        g.drawPolygon(xs, ys, 4);
    }

    static MapOverlay.Rect screenBoundsOfBox(IsoMapCamera cam, int[] box) {
        if (cam == null || box == null || box.length < 6) {
            return new MapOverlay.Rect(0, 0, 1, 1);
        }
        int x1 = Math.min(box[0], box[3]);
        int y1 = Math.min(box[1], box[4]);
        int z1 = Math.min(box[2], box[5]);
        int x2 = Math.max(box[0], box[3]) + 1;
        int y2 = Math.max(box[1], box[4]) + 1;
        int z2 = Math.max(box[2], box[5]) + 1;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int x : new int[]{x1, x2}) {
            for (int y : new int[]{y1, y2}) {
                for (int z : new int[]{z1, z2}) {
                    double[] p = cam.worldToScreen(x, y, z);
                    if (p[0] < minX) minX = p[0];
                    if (p[0] > maxX) maxX = p[0];
                    if (p[1] < minY) minY = p[1];
                    if (p[1] > maxY) maxY = p[1];
                }
            }
        }
        int rx = (int) Math.round(minX);
        int ry = (int) Math.round(minY);
        int rw = Math.max(1, (int) Math.round(maxX - minX));
        int rh = Math.max(1, (int) Math.round(maxY - minY));
        return new MapOverlay.Rect(rx, ry, rw, rh);
    }

    private static void drawTag(Graphics2D g, AppStyle style, String text, int x, int y, int fgRgb) {
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        int th = fm.getAscent();
        g.setColor(alpha(style.theme.surface(), 185));
        g.fillRoundRect(x - 3, y - th, tw + 6, th + 4, 4, 4);
        g.setColor(new Color(fgRgb));
        g.drawString(text, x, y);
    }

    static int shade(int rgb, float mul) {
        int r = Math.max(0, Math.min(255, (int) (((rgb >> 16) & 0xFF) * mul)));
        int g = Math.max(0, Math.min(255, (int) (((rgb >> 8) & 0xFF) * mul)));
        int b = Math.max(0, Math.min(255, (int) ((rgb & 0xFF) * mul)));
        return (r << 16) | (g << 8) | b;
    }

    private static Color alpha(int rgb, int a) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, Math.max(0, Math.min(255, a)));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
