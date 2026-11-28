package baritone.swarm.center.app;

import baritone.swarm.rts.Blueprint;

import java.util.Collection;

/**
 * Ostinato: a ray against the editor volume. A hit on a cell also names the empty neighbour the click
 * would place into (the entered face). A miss can still land on the current layer plane.
 */
public final class EditorHit {

    public enum Kind { MISS, CELL, LAYER }

    public final Kind kind;
    public final int x;
    public final int y;
    public final int z;
    public final int placeX;
    public final int placeY;
    public final int placeZ;
    public final boolean canPlace;
    public final double t;

    private EditorHit(Kind kind, int x, int y, int z, int placeX, int placeY, int placeZ, boolean canPlace, double t) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.placeX = placeX;
        this.placeY = placeY;
        this.placeZ = placeZ;
        this.canPlace = canPlace;
        this.t = t;
    }

    public static EditorHit miss() {
        return new EditorHit(Kind.MISS, 0, 0, 0, 0, 0, 0, false, Double.POSITIVE_INFINITY);
    }

    public boolean missed() {
        return kind == Kind.MISS;
    }

    /**
     * Nearest cell the ray enters, else the current layer plane at {@code layerY}. {@code dir} is normalised
     * here. Cells are unit cubes {@code [x,x+1) × [y,y+1) × [z,z+1)}.
     */
    public static EditorHit trace(double[] origin, double[] dir, Collection<Blueprint.Cell> cells, int layerY) {
        double ox = origin[0], oy = origin[1], oz = origin[2];
        double dx = dir[0], dy = dir[1], dz = dir[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) {
            return miss();
        }
        dx /= len;
        dy /= len;
        dz /= len;
        EditorHit best = miss();
        double bestT = Double.POSITIVE_INFINITY;
        if (cells != null) {
            for (Blueprint.Cell c : cells) {
                double[] hit = aabb(ox, oy, oz, dx, dy, dz, c.x(), c.y(), c.z());
                if (hit == null || hit[0] < 0 || hit[0] >= bestT) {
                    continue;
                }
                int nx = (int) hit[1], ny = (int) hit[2], nz = (int) hit[3];
                bestT = hit[0];
                best = new EditorHit(Kind.CELL, c.x(), c.y(), c.z(), c.x() + nx, c.y() + ny, c.z() + nz, true, hit[0]);
            }
        }
        if (Math.abs(dy) > 1e-9) {
            double t = (layerY - oy) / dy;
            if (t > 1e-4 && t < bestT) {
                int px = (int) Math.floor(ox + dx * t);
                int pz = (int) Math.floor(oz + dz * t);
                // a cell already occupies this layer cell: the cube hit above wins
                best = new EditorHit(Kind.LAYER, px, layerY, pz, px, layerY, pz, true, t);
            }
        }
        return best;
    }

    /**
     * Entry time and outward face normal of the unit cube at {@code (x,y,z)}, or null on a miss.
     * {@code out} is {@code {t, nx, ny, nz}}.
     */
    static double[] aabb(double ox, double oy, double oz, double dx, double dy, double dz, int x, int y, int z) {
        double tmin = 0;
        double tmax = 1e6;
        int nx = 0, ny = 0, nz = 0;
        double[] xs = slab(ox, dx, x, x + 1, tmin, tmax);
        if (xs == null) {
            return null;
        }
        if (xs[0] > tmin) {
            tmin = xs[0];
            nx = dx > 0 ? -1 : 1;
            ny = 0;
            nz = 0;
        }
        tmax = xs[1];
        double[] ys = slab(oy, dy, y, y + 1, tmin, tmax);
        if (ys == null) {
            return null;
        }
        if (ys[0] > tmin) {
            tmin = ys[0];
            nx = 0;
            ny = dy > 0 ? -1 : 1;
            nz = 0;
        }
        tmax = ys[1];
        double[] zs = slab(oz, dz, z, z + 1, tmin, tmax);
        if (zs == null) {
            return null;
        }
        if (zs[0] > tmin) {
            tmin = zs[0];
            nx = 0;
            ny = 0;
            nz = dz > 0 ? -1 : 1;
        }
        tmax = zs[1];
        if (tmax < tmin) {
            return null;
        }
        return new double[]{tmin, nx, ny, nz};
    }

    private static double[] slab(double o, double d, double min, double max, double tmin, double tmax) {
        if (Math.abs(d) < 1e-12) {
            return (o >= min && o <= max) ? new double[]{tmin, tmax} : null;
        }
        double a = (min - o) / d;
        double b = (max - o) / d;
        if (a > b) {
            double s = a;
            a = b;
            b = s;
        }
        tmin = Math.max(tmin, a);
        tmax = Math.min(tmax, b);
        return tmin <= tmax ? new double[]{tmin, tmax} : null;
    }
}
