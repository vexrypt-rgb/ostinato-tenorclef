package baritone.swarm.center.app;

import baritone.swarm.center.feed.TerrainFeed;

/**
 * Ostinato: 3D orthographic 2:1 isometric camera for the command-center War room / RTS map.
 * Shares {@code centerX}, {@code centerZ}, {@code zoom}, and viewport size with {@link MapCamera} so switching
 * between 2D top-down and 3D isometric keeps the same focus point and scale.
 *
 * <p>Projection for rotated world offset {@code (rx, rz)} and elevation {@code dy = y - refY}:
 * <pre>
 *   sx = width / 2  + (rx - rz) * zoom
 *   sy = height / 2 + (rx + rz) * (zoom * 0.5) - dy * zoom
 * </pre>
 * Because vertical scale equals {@code 2 * halfTileHeight}, the view ray is exact {@code (-1, -1, -1)} in
 * {@code (rx, y, rz)}, which gives an exact back-to-front painter's order (increasing {@code rx}, {@code rz},
 * {@code y}) with no z-buffer and exact invertible plane / terrain surface picking.
 */
public final class IsoMapCamera {

    private final MapCamera base;
    /** 0..3 quarter-turns clockwise. 0: +X down-right, +Z down-left (camera looking NW). */
    private int rotation;
    /** Reference world Y placed at screen vertical center. */
    private double refY = 64.0;
    private boolean refYSet;

    public IsoMapCamera(MapCamera base) {
        this.base = base;
    }

    public MapCamera base() {
        return base;
    }

    public int rotation() {
        return rotation;
    }

    public void setRotation(int rot) {
        this.rotation = ((rot % 4) + 4) % 4;
    }

    public void rotateCW() {
        rotation = (rotation + 1) & 3;
    }

    public void rotateCCW() {
        rotation = (rotation + 3) & 3;
    }

    /** Compass direction that points UP on screen (the look direction). */
    public String lookLabel() {
        return switch (rotation) {
            case 0 -> "NW";
            case 1 -> "NE";
            case 2 -> "SE";
            default -> "SW";
        };
    }

    public double refY() {
        return refY;
    }

    public boolean isRefYSet() {
        return refYSet;
    }

    public void setRefY(double y) {
        this.refY = y;
        this.refYSet = true;
    }

    public void defaultRefY(double y) {
        if (!refYSet) {
            this.refY = y;
        }
    }

    /** Rotate world delta (dx, dz) into camera-space (rx, rz). */
    public double rotX(double dx, double dz) {
        return switch (rotation) {
            case 0 -> dx;
            case 1 -> dz;
            case 2 -> -dx;
            default -> -dz;
        };
    }

    /** Rotate world delta (dx, dz) into camera-space (rx, rz). */
    public double rotZ(double dx, double dz) {
        return switch (rotation) {
            case 0 -> dz;
            case 1 -> -dx;
            case 2 -> -dz;
            default -> dx;
        };
    }

    /** Inverse rotation from camera-space (rx, rz) back to world delta (dx, dz). */
    public double unrotX(double rx, double rz) {
        return switch (rotation) {
            case 0 -> rx;
            case 1 -> -rz;
            case 2 -> -rx;
            default -> rz;
        };
    }

    /** Inverse rotation from camera-space (rx, rz) back to world delta (dx, dz). */
    public double unrotZ(double rx, double rz) {
        return switch (rotation) {
            case 0 -> rz;
            case 1 -> rx;
            case 2 -> -rz;
            default -> -rx;
        };
    }

    /** Integer world X from integer rotated grid coordinates (gx, gz). */
    public int worldXFromRot(int gx, int gz) {
        return switch (rotation) {
            case 0 -> gx;
            case 1 -> -gz - 1;
            case 2 -> -gx - 1;
            default -> gz;
        };
    }

    /** Integer world Z from integer rotated grid coordinates (gx, gz). */
    public int worldZFromRot(int gx, int gz) {
        return switch (rotation) {
            case 0 -> gz;
            case 1 -> gx;
            case 2 -> -gz - 1;
            default -> -gx - 1;
        };
    }

    /** Integer rotated grid X of block (wx, wz). */
    public int rotGridX(int wx, int wz) {
        return switch (rotation) {
            case 0 -> wx;
            case 1 -> wz;
            case 2 -> -wx - 1;
            default -> -wz - 1;
        };
    }

    /** Integer rotated grid Z of block (wx, wz). */
    public int rotGridZ(int wx, int wz) {
        return switch (rotation) {
            case 0 -> wz;
            case 1 -> -wx - 1;
            case 2 -> -wz - 1;
            default -> wx;
        };
    }

    /** World step for (+1, 0) in rotated grid space: the front-right neighbor of a voxel. */
    public int frontRightDx() {
        return switch (rotation) {
            case 0 -> 1;
            case 1 -> 0;
            case 2 -> -1;
            default -> 0;
        };
    }

    public int frontRightDz() {
        return switch (rotation) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 0;
            default -> -1;
        };
    }

    /** World step for (0, +1) in rotated grid space: the front-left neighbor of a voxel. */
    public int frontLeftDx() {
        return switch (rotation) {
            case 0 -> 0;
            case 1 -> -1;
            case 2 -> 0;
            default -> 1;
        };
    }

    public int frontLeftDz() {
        return switch (rotation) {
            case 0 -> 1;
            case 1 -> 0;
            case 2 -> -1;
            default -> 0;
        };
    }

    /** Half-width of one block diamond in screen pixels. */
    public double halfW() {
        return base.zoom();
    }

    /** Half-height of one block diamond in screen pixels. */
    public double halfH() {
        return base.zoom() * 0.5;
    }

    /** Screen pixels per vertical block (+Y). Equals {@code 2 * halfH()}. */
    public double vertScale() {
        return base.zoom();
    }

    public double screenX(double wx, double wz) {
        double dx = wx - base.centerX();
        double dz = wz - base.centerZ();
        double rx = rotX(dx, dz);
        double rz = rotZ(dx, dz);
        return base.width() * 0.5 + (rx - rz) * halfW();
    }

    public double screenY(double wx, double wy, double wz) {
        double dx = wx - base.centerX();
        double dz = wz - base.centerZ();
        double rx = rotX(dx, dz);
        double rz = rotZ(dx, dz);
        return base.height() * 0.5 + (rx + rz) * halfH() - (wy - refY) * vertScale();
    }

    public double[] worldToScreen(double wx, double wy, double wz) {
        double dx = wx - base.centerX();
        double dz = wz - base.centerZ();
        double rx = rotX(dx, dz);
        double rz = rotZ(dx, dz);
        return new double[]{
                base.width() * 0.5 + (rx - rz) * halfW(),
                base.height() * 0.5 + (rx + rz) * halfH() - (wy - refY) * vertScale()
        };
    }

    /** Inverse projection onto the horizontal plane {@code y = wy}. Returns {@code {wx, wz}}. */
    public double[] screenToWorldAtY(double sx, double sy, double wy) {
        double u = (sx - base.width() * 0.5) / halfW();
        double v = (sy - base.height() * 0.5 + (wy - refY) * vertScale()) / halfH();
        double rx = 0.5 * (u + v);
        double rz = 0.5 * (v - u);
        return new double[]{
                base.centerX() + unrotX(rx, rz),
                base.centerZ() + unrotZ(rx, rz)
        };
    }

    /** Inverse projection onto the reference plane {@code y = refY}. */
    public double[] screenToWorld(double sx, double sy) {
        return screenToWorldAtY(sx, sy, refY);
    }

    /**
     * Exact front-to-back ray-pick against the terrain surface grid. Returns {@code {blockX, surfaceY, blockZ}}.
     * Falls back to {@code (floor(wx), round(refY), floor(wz))} on {@code y = refY} when no terrain column is hit.
     */
    public int[] pickSurface(double sx, double sy, MapTerrain.Grid grid) {
        if (grid != null && !grid.isEmpty()) {
            // Walk down from top of terrain (maxY) to minY along the view ray.
            // Check the top face (y + 1.0) first so horizontal surfaces pick cleanly in all 4 rotations,
            // then check mid-block height (y + 0.5) so vertical cliff faces also pick their column.
            for (int y = grid.maxY; y >= grid.minY; y--) {
                double[] top = screenToWorldAtY(sx, sy, y + 1.0);
                int tx = (int) Math.floor(top[0]);
                int tz = (int) Math.floor(top[1]);
                int th = grid.heightAt(tx, tz);
                if (th != TerrainFeed.UNKNOWN_Y && th >= y) {
                    return new int[]{tx, th, tz};
                }
                double[] mid = screenToWorldAtY(sx, sy, y + 0.5);
                int mx = (int) Math.floor(mid[0]);
                int mz = (int) Math.floor(mid[1]);
                int mh = grid.heightAt(mx, mz);
                if (mh != TerrainFeed.UNKNOWN_Y && mh >= y) {
                    return new int[]{mx, mh, mz};
                }
            }
        }
        double[] flat = screenToWorldAtY(sx, sy, refY);
        return new int[]{(int) Math.floor(flat[0]), (int) Math.round(refY), (int) Math.floor(flat[1])};
    }

    /** Pan the camera by screen pixels so the world point under the cursor follows the pointer. */
    public void panPixels(double dxPx, double dyPx) {
        double du = -dxPx / halfW();
        double dv = -dyPx / halfH();
        double drx = 0.5 * (du + dv);
        double drz = 0.5 * (dv - du);
        base.setCenter(base.centerX() + unrotX(drx, drz), base.centerZ() + unrotZ(drx, drz));
    }

    /** Zoom by {@code steps} keeping the world point on {@code y = refY} under {@code (sx, sy)} fixed. */
    public void zoomAt(double sx, double sy, int steps) {
        if (steps == 0) {
            return;
        }
        double[] before = screenToWorldAtY(sx, sy, refY);
        int next = Math.max(MapCamera.MIN_ZOOM, Math.min(MapCamera.MAX_ZOOM, base.zoom() + steps));
        if (next == base.zoom()) {
            base.clamp();
            return;
        }
        base.setZoom(next);
        double[] after = screenToWorldAtY(sx, sy, refY);
        base.setCenter(base.centerX() + (before[0] - after[0]), base.centerZ() + (before[1] - after[1]));
    }

    /** Normalized screen direction vector for a world horizontal vector (wx, wz). */
    public double[] screenDir(double wx, double wz) {
        double rx = rotX(wx, wz);
        double rz = rotZ(wx, wz);
        double sx = rx - rz;
        double sy = (rx + rz) * 0.5;
        double len = Math.hypot(sx, sy);
        return len < 1e-9 ? new double[]{0, 1} : new double[]{sx / len, sy / len};
    }

    /** Normalized screen direction vector for a Minecraft yaw (0 = +Z, +90 = -X). */
    public double[] facingScreenDir(float yaw) {
        double rad = Math.toRadians(yaw);
        return screenDir(-Math.sin(rad), Math.cos(rad));
    }

    /** Normalized screen direction vector pointing North (-Z). */
    public double[] northScreenDir() {
        return screenDir(0, -1);
    }

    /** Depth key for painter's-algorithm sorting of markers/objects (larger = closer to viewer). */
    public double depth(double wx, double wy, double wz) {
        double dx = wx - base.centerX();
        double dz = wz - base.centerZ();
        return rotX(dx, dz) + rotZ(dx, dz) + (wy - refY) * 0.01;
    }

    /**
     * Exact screen-frustum bounds in rotated block coordinates {@code {gxMin, gxMax, gzMin, gzMax}} covering
     * all columns whose elevation lies in {@code [minY, maxY]}.
     */
    public int[] visibleRotGridBounds(int minY, int maxY) {
        int w = base.width();
        int h = base.height();
        double[][] corners = {
                screenToWorldAtY(0, 0, maxY + 2),
                screenToWorldAtY(w, 0, maxY + 2),
                screenToWorldAtY(0, h, minY - 4),
                screenToWorldAtY(w, h, minY - 4),
                screenToWorldAtY(0, 0, minY - 4),
                screenToWorldAtY(w, 0, minY - 4),
                screenToWorldAtY(0, h, maxY + 2),
                screenToWorldAtY(w, h, maxY + 2)
        };
        int gxMin = Integer.MAX_VALUE, gxMax = Integer.MIN_VALUE;
        int gzMin = Integer.MAX_VALUE, gzMax = Integer.MIN_VALUE;
        for (double[] c : corners) {
            int wx = (int) Math.floor(c[0]);
            int wz = (int) Math.floor(c[1]);
            int gx = rotGridX(wx, wz);
            int gz = rotGridZ(wx, wz);
            if (gx < gxMin) gxMin = gx;
            if (gx > gxMax) gxMax = gx;
            if (gz < gzMin) gzMin = gz;
            if (gz > gzMax) gzMax = gz;
        }
        return new int[]{gxMin - 2, gxMax + 2, gzMin - 2, gzMax + 2};
    }
}
