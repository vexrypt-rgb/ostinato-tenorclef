package baritone.swarm.center.app;

/**
 * Ostinato: orbit camera for the command-center 3D schematic editor. Same math as the in-game
 * {@code StudioCamera}, kept here so {@code center-app} stays free of Minecraft types. Yaw 0 with pitch 0
 * sits on +Z looking toward the target. Screen Y grows downward.
 */
public final class EditorCamera {

    public double targetX;
    public double targetY = -60;
    public double targetZ;
    public double yaw = 0.75;
    public double pitch = 0.55;
    public double distance = 28;
    public double fov = 0.9;

    public void lookAt(double x, double y, double z) {
        targetX = x;
        targetY = y;
        targetZ = z;
    }

    /** Frame a world-space box (x1,y1,z1,x2,y2,z2), or the default pad when {@code box} is null. */
    public void fit(int[] box) {
        if (box == null) {
            lookAt(0, -60, 0);
            distance = 28;
            return;
        }
        lookAt((box[0] + box[3] + 1) * 0.5, (box[1] + box[4] + 1) * 0.5, (box[2] + box[5] + 1) * 0.5);
        int span = 1 + Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2]));
        distance = clamp(span * 2.2 + 8, 8, 120);
    }

    public void orbit(double dyaw, double dpitch) {
        yaw += dyaw;
        pitch = clamp(pitch + dpitch, -1.25, 1.25);
    }

    public void dolly(double steps) {
        distance = clamp(distance * Math.pow(0.9, steps), 6, 160);
    }

    /** Shift the target by a right/up offset in world units. */
    public void pan(double rightAmount, double upAmount) {
        double[] r = new double[3];
        double[] u = new double[3];
        double[] f = new double[3];
        double[] e = new double[3];
        basis(r, u, f, e);
        targetX += r[0] * rightAmount + u[0] * upAmount;
        targetY += r[1] * rightAmount + u[1] * upAmount;
        targetZ += r[2] * rightAmount + u[2] * upAmount;
    }

    public void eye(double[] out) {
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double sy = Math.sin(yaw);
        double cy = Math.cos(yaw);
        out[0] = targetX + distance * cp * sy;
        out[1] = targetY + distance * sp;
        out[2] = targetZ + distance * cp * cy;
    }

    /** Right, up, and forward (unit) plus the eye position. */
    public void basis(double[] right, double[] up, double[] forward, double[] eye) {
        eye(eye);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double sy = Math.sin(yaw);
        double cy = Math.cos(yaw);
        forward[0] = -cp * sy;
        forward[1] = -sp;
        forward[2] = -cp * cy;
        double rx = -forward[2];
        double rz = forward[0];
        double rl = Math.hypot(rx, rz);
        if (rl < 1e-8) {
            rx = 1;
            rz = 0;
            rl = 1;
        }
        right[0] = rx / rl;
        right[1] = 0;
        right[2] = rz / rl;
        up[0] = right[1] * forward[2] - right[2] * forward[1];
        up[1] = right[2] * forward[0] - right[0] * forward[2];
        up[2] = right[0] * forward[1] - right[1] * forward[0];
    }

    public double pixelsPerUnit(float viewH) {
        return (viewH * 0.5) / Math.tan(fov * 0.5);
    }

    /** {@code out} is screen x, screen y, and depth along the view. Depth below 0 is behind the eye. */
    public void project(double wx, double wy, double wz, float vx, float vy, float vw, float vh, double[] out) {
        double[] r = new double[3];
        double[] u = new double[3];
        double[] f = new double[3];
        double[] e = new double[3];
        basis(r, u, f, e);
        double dx = wx - e[0];
        double dy = wy - e[1];
        double dz = wz - e[2];
        double depth = dx * f[0] + dy * f[1] + dz * f[2];
        double xCam = dx * r[0] + dy * r[1] + dz * r[2];
        double yCam = dx * u[0] + dy * u[1] + dz * u[2];
        double s = pixelsPerUnit(vh);
        out[0] = vx + vw * 0.5 + (depth > 1e-4 ? xCam / depth * s : 0);
        out[1] = vy + vh * 0.5 - (depth > 1e-4 ? yCam / depth * s : 0);
        out[2] = depth;
    }

    /** Ray from the eye through a GUI point. {@code dir} is not normalised. */
    public void ray(float mx, float my, float vx, float vy, float vw, float vh, double[] origin, double[] dir) {
        double[] r = new double[3];
        double[] u = new double[3];
        double[] f = new double[3];
        basis(r, u, f, origin);
        double s = pixelsPerUnit(vh);
        double nx = (mx - (vx + vw * 0.5)) / s;
        double ny = -((my - (vy + vh * 0.5)) / s);
        dir[0] = f[0] + r[0] * nx + u[0] * ny;
        dir[1] = f[1] + r[1] * nx + u[1] * ny;
        dir[2] = f[2] + r[2] * nx + u[2] * ny;
    }

    public EditorCamera copy() {
        EditorCamera c = new EditorCamera();
        c.targetX = targetX;
        c.targetY = targetY;
        c.targetZ = targetZ;
        c.yaw = yaw;
        c.pitch = pitch;
        c.distance = distance;
        c.fov = fov;
        return c;
    }

    public void set(EditorCamera o) {
        if (o == null) {
            return;
        }
        targetX = o.targetX;
        targetY = o.targetY;
        targetZ = o.targetZ;
        yaw = o.yaw;
        pitch = o.pitch;
        distance = o.distance;
        fov = o.fov;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
