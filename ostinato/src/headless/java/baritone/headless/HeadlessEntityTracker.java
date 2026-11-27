
/*
 * Ostinato: fully headless 26.3 protocol bot. New file, replaces no Baritone code.
 *
 * Tracks nearby entities from play packets so combat can look, walk in, and swing.
 * No client world entity getter exists headlessly.
 */
package baritone.headless;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HeadlessEntityTracker {

    /** Vanilla living-entity death event. */
    private static final byte DEATH_EVENT = 3;

    public static final class Tracked {
        public final int id;
        public final UUID uuid;
        public final EntityType<?> type;
        public final VecDeltaCodec codec = new VecDeltaCodec();
        public double x, y, z;
        // Ostinato: the position before the latest move packet. Until the server's ping after that packet comes
        // back, Grim still allows (and only allows) the hitbox anywhere between the two, so a hit has to land in both.
        public double px, py, pz;
        private boolean placed;
        public double vx, vy, vz;
        public float yaw, pitch;
        public boolean onGround;
        public boolean alive = true;
        public float health = -1f;
        Entity entity;

        Tracked(int id, UUID uuid, EntityType<?> type, double x, double y, double z, float yaw, float pitch) {
            this.id = id;
            this.uuid = uuid;
            this.type = type;
            setPos(x, y, z);
            this.yaw = yaw;
            this.pitch = pitch;
        }

        void setPos(double x, double y, double z) {
            this.px = placed ? this.x : x;
            this.py = placed ? this.y : y;
            this.pz = placed ? this.z : z;
            placed = true;
            this.x = x;
            this.y = y;
            this.z = z;
            codec.setBase(new Vec3(x, y, z));
            syncEntity();
        }

        void syncEntity() {
            Entity e = this.entity;
            if (e != null) {
                e.setPosRaw(x, y, z);
                e.setBoundingBox(e.getDimensions(e.getPose()).makeBoundingBox(x, y, z));
                e.setYRot(yaw);
                e.setXRot(pitch);
                e.setDeltaMovement(vx, vy, vz);
                e.setOnGround(onGround);
                if (!alive && !e.isRemoved()) {
                    e.discard();
                }
            }
        }

        public AABB box() {
            EntityDimensions dimensions = type.getDimensions();
            return dimensions.makeBoundingBox(x, y, z);
        }

        public AABB previousBox() {
            return type.getDimensions().makeBoundingBox(px, py, pz);
        }

        /** Ostinato: aim between the last two known eye positions, so the crosshair is in both boxes. */
        public Vec3 aimPoint() {
            return new Vec3((x + px) / 2, (y + py) / 2 + type.getDimensions().eyeHeight(), (z + pz) / 2);
        }

        public Vec3 position() {
            return new Vec3(x, y, z);
        }

        public Vec3 eye() {
            return new Vec3(x, y + type.getDimensions().eyeHeight(), z);
        }

        public String typeId() {
            return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        }
    }

    private final Map<Integer, Tracked> entities = new ConcurrentHashMap<>();
    private volatile Level boundLevel;

    public void bindLevel(Level level) {
        this.boundLevel = level;
        if (level != null) {
            for (Tracked tracked : entities.values()) {
                if (tracked.entity == null) {
                    tracked.entity = createEntity(level, tracked);
                    tracked.syncEntity();
                }
            }
        }
    }

    public Iterable<Entity> commonEntities() {
        List<Entity> out = new ArrayList<>(entities.size());
        for (Tracked tracked : entities.values()) {
            if (tracked.alive && tracked.entity != null) {
                out.add(tracked.entity);
            }
        }
        return out;
    }

    public Entity commonEntity(int id) {
        Tracked tracked = entities.get(id);
        return tracked == null ? null : tracked.entity;
    }

    private static Entity createEntity(Level level, Tracked tracked) {
        if (level == null || tracked == null) {
            return null;
        }
        try {
            Entity e;
            if ("player".equals(tracked.typeId())) {
                e = new RemoteHeadlessPlayer(level, new GameProfile(tracked.uuid, "player-" + tracked.id));
            } else {
                e = tracked.type.create(level, EntitySpawnReason.LOAD);
            }
            if (e != null) {
                e.setId(tracked.id);
                e.setUUID(tracked.uuid);
                e.setPos(tracked.x, tracked.y, tracked.z);
                e.setYRot(tracked.yaw);
                e.setXRot(tracked.pitch);
                e.setDeltaMovement(tracked.vx, tracked.vy, tracked.vz);
                e.setOnGround(tracked.onGround);
            }
            return e;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class RemoteHeadlessPlayer extends Player {
        RemoteHeadlessPlayer(Level level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public GameType gameMode() {
            return GameType.SURVIVAL;
        }
    }

    /**
     * @return true when this packet is an entity-tracking packet (consumed even if it
     * referred to the bot itself and was ignored here).
     */
    public boolean handle(Object packet, int selfId) {
        if (packet instanceof ClientboundAddEntityPacket add) {
            if (add.getId() != selfId) {
                add(add);
            }
            return true;
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            for (int id : remove.entityIds()) {
                if (id != selfId) {
                    entities.remove(id);
                }
            }
            return true;
        }
        if (packet instanceof ClientboundMoveEntityPacket move) {
            int id = HeadlessPacketAccess.moveEntityId(move);
            if (id != selfId) {
                Tracked tracked = entities.get(id);
                if (tracked != null) {
                    applyMove(tracked, move);
                }
            }
            return true;
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            if (teleport.id() != selfId) {
                Tracked tracked = entities.get(teleport.id());
                if (tracked != null) {
                    PositionMoveRotation change = teleport.change();
                    Vec3 pos = change.position();
                    tracked.setPos(pos.x, pos.y, pos.z);
                    tracked.vx = change.deltaMovement().x;
                    tracked.vy = change.deltaMovement().y;
                    tracked.vz = change.deltaMovement().z;
                    tracked.yaw = change.yRot();
                    tracked.pitch = change.xRot();
                    tracked.onGround = teleport.onGround();
                    tracked.syncEntity();
                }
            }
            return true;
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            if (sync.id() != selfId) {
                Tracked tracked = entities.get(sync.id());
                if (tracked != null) {
                    Vec3 pos = sync.position().endPosition();
                    tracked.setPos(pos.x, pos.y, pos.z);
                    tracked.yaw = sync.yRot();
                    tracked.pitch = sync.xRot();
                    tracked.onGround = sync.onGround();
                    tracked.syncEntity();
                }
            }
            return true;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            if (motion.id() != selfId) {
                Tracked tracked = entities.get(motion.id());
                if (tracked != null) {
                    Vec3 v = motion.movement();
                    tracked.vx = v.x;
                    tracked.vy = v.y;
                    tracked.vz = v.z;
                    tracked.syncEntity();
                }
            }
            return true;
        }
        if (packet instanceof ClientboundSetEntityDataPacket data) {
            if (data.id() != selfId) {
                Tracked tracked = entities.get(data.id());
                if (tracked != null && tracked.entity != null) {
                    try {
                        tracked.entity.getEntityData().assignValues(data.packedItems());
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            return true;
        }
        if (packet instanceof ClientboundEntityEventPacket event) {
            int id = HeadlessPacketAccess.entityEventId(event);
            if (id != selfId && event.getEventId() == DEATH_EVENT) {
                Tracked tracked = entities.get(id);
                if (tracked != null) {
                    tracked.alive = false;
                    tracked.health = 0f;
                    tracked.syncEntity();
                }
            }
            return true;
        }
        if (packet instanceof ClientboundTakeItemEntityPacket take) {
            // Ostinato: vanilla auto-collect (and explicit pickup) arrives as TakeItemEntity.
            // Drop the item entity so pickup does not keep pathing at a ghost, and remember
            // the take so a command started after the scoop can still succeed.
            lastTakeEntityId = take.getItemId();
            lastTakePlayerId = take.getPlayerId();
            lastTakeAmount = take.getAmount();
            lastTakeAtNanos = System.nanoTime();
            entities.remove(take.getItemId());
            return true;
        }
        return false;
    }

    private int lastTakeEntityId = -1;
    private int lastTakePlayerId = -1;
    private int lastTakeAmount;
    private long lastTakeAtNanos;

    boolean tookItemRecently(int playerId, long windowNanos) {
        return tookItemRecently(playerId, -1, windowNanos);
    }

    boolean tookItemRecently(int playerId, int entityId, long windowNanos) {
        return lastTakePlayerId == playerId
                && lastTakeEntityId != -1
                && (entityId < 0 || lastTakeEntityId == entityId)
                && System.nanoTime() - lastTakeAtNanos <= windowNanos;
    }

    private void add(ClientboundAddEntityPacket packet) {
        Tracked tracked = new Tracked(
                packet.getId(), packet.getUUID(), packet.getType(),
                packet.getX(), packet.getY(), packet.getZ(),
                packet.getYRot(), packet.getXRot());
        Vec3 movement = packet.getMovement();
        tracked.vx = movement.x;
        tracked.vy = movement.y;
        tracked.vz = movement.z;
        Level level = boundLevel;
        if (level != null) {
            tracked.entity = createEntity(level, tracked);
        }
        tracked.syncEntity();
        entities.put(tracked.id, tracked);
    }

    private static void applyMove(Tracked tracked, ClientboundMoveEntityPacket packet) {
        if (packet.hasPosition()) {
            PositionPath path = packet.getPositionDelta().decode(tracked.codec);
            Vec3 end = path.endPosition();
            tracked.setPos(end.x, end.y, end.z);
        }
        if (packet.hasRotation()) {
            tracked.yaw = packet.getYRot();
            tracked.pitch = packet.getXRot();
        }
        tracked.onGround = packet.isOnGround();
        tracked.syncEntity();
    }

    public Tracked get(int id) {
        return entities.get(id);
    }

    public int size() {
        return entities.size();
    }

    public void clear() {
        entities.clear();
    }

    /**
     * Nearest living target matching an optional type path ({@code zombie}) or any monster
     * when {@code typeFilter} is empty.
     */
    public Tracked nearest(Vec3 from, String typeFilter, double maxDistance) {
        String want = typeFilter == null ? "" : typeFilter.toLowerCase(Locale.ROOT);
        if (want.startsWith("minecraft:")) {
            want = want.substring("minecraft:".length());
        }
        Tracked best = null;
        double bestDist = maxDistance * maxDistance;
        for (Tracked tracked : entities.values()) {
            if (!tracked.alive || !isCombatTarget(tracked, want)) {
                continue;
            }
            double d = from.distanceToSqr(tracked.position());
            if (d < bestDist) {
                bestDist = d;
                best = tracked;
            }
        }
        return best;
    }

    public List<Tracked> matching(String typeFilter) {
        String want = typeFilter == null ? "" : typeFilter.toLowerCase(Locale.ROOT);
        if (want.startsWith("minecraft:")) {
            want = want.substring("minecraft:".length());
        }
        List<Tracked> out = new ArrayList<>();
        for (Tracked tracked : entities.values()) {
            if (tracked.alive && isCombatTarget(tracked, want)) {
                out.add(tracked);
            }
        }
        return out;
    }

    static boolean isCombatTarget(Tracked tracked, String typeFilter) {
        if (!typeFilter.isEmpty()) {
            return tracked.typeId().equals(typeFilter);
        }
        return tracked.type.getCategory() == MobCategory.MONSTER;
    }
}
