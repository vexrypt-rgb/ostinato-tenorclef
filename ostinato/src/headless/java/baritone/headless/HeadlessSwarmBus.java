/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.headless;

import baritone.api.swarm.SwarmPriority;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.lease.ReservationKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-JVM swarm transport, cooperative claim table, and observation bus for bots sharing a
 * {@link HeadlessFleetController}. Messages are enqueued into the target bot's inbox and drained
 * strictly on that bot's tick thread inside its {@code BotContextScope}, preserving per-bot
 * isolation with zero server-chat latency or spam.
 */
public final class HeadlessSwarmBus {

    public static final String DEFAULT_GROUP = "local";
    private static final int MAX_OBSERVATIONS = 200;

    /** Optional fallback for peers outside this single-JVM fleet (e.g., remote chat/relay). */
    @FunctionalInterface
    public interface RemoteFallback {
        boolean send(String from, String group, String to, String type, String body, SwarmPriority priority);
    }

    public record Envelope(String from, String group, String type, String body, long sentAtMs) {
    }

    public record ResourceClaim(String resource, String owner, String purpose, long expiresAtMs) {
    }

    private record TrackedAssignment(
            String id,
            String bot,
            String item,
            int count,
            String state,
            int progress,
            String reason,
            long updatedAtMs) {
    }

    private static final class MemberEntry {
        final String id;
        final ConcurrentLinkedQueue<Envelope> inbox = new ConcurrentLinkedQueue<>();
        final HeadlessSwarmLink link;

        MemberEntry(String id, HeadlessSwarmBus bus, HeadlessFleetController fleet) {
            this.id = id;
            this.link = new HeadlessSwarmLink(bus, id, this.inbox, fleet);
        }
    }

    private final String groupId;
    private final Map<String, MemberEntry> members = new ConcurrentHashMap<>();
    private final Map<String, ResourceClaim> claims = new LinkedHashMap<>();
    private final Map<String, CoordinatorFeed.Observation> observations = new LinkedHashMap<>();
    private final Map<String, TrackedAssignment> assignments = new LinkedHashMap<>();
    private final Object stateLock = new Object();

    private volatile String leader;
    private volatile RemoteFallback remoteFallback;

    private final AtomicLong localMessagesSent = new AtomicLong();
    private final AtomicLong localMessagesDelivered = new AtomicLong();
    private final AtomicLong remoteFallbackSent = new AtomicLong();

    public HeadlessSwarmBus() {
        this(DEFAULT_GROUP);
    }

    public HeadlessSwarmBus(String groupId) {
        this.groupId = (groupId == null || groupId.isBlank()) ? DEFAULT_GROUP : groupId.trim();
    }

    public String groupId() {
        return groupId;
    }

    public void setRemoteFallback(RemoteFallback fallback) {
        this.remoteFallback = fallback;
    }

    public HeadlessSwarmLink register(String botId, HeadlessFleetController fleet) {
        Objects.requireNonNull(botId, "botId");
        String id = botId.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("botId must not be blank");
        }
        MemberEntry entry = new MemberEntry(id, this, fleet);
        synchronized (stateLock) {
            members.put(id.toLowerCase(Locale.ROOT), entry);
            if (leader == null || leader.isBlank()) {
                leader = id;
            }
        }
        return entry.link;
    }

    public void unregister(String botId) {
        if (botId == null || botId.isBlank()) {
            return;
        }
        String key = botId.trim().toLowerCase(Locale.ROOT);
        MemberEntry removed;
        synchronized (stateLock) {
            removed = members.remove(key);
            releaseAllClaimsLocked(botId);
            if (leader != null && leader.equalsIgnoreCase(botId)) {
                leader = members.values().stream()
                        .map(m -> m.id)
                        .sorted()
                        .findFirst()
                        .orElse(null);
            }
        }
        if (removed != null) {
            removed.link.markClosed();
        }
    }

    public boolean isRegistered(String botId) {
        return botId != null && members.containsKey(botId.trim().toLowerCase(Locale.ROOT));
    }

    public String leader() {
        return leader;
    }

    public void setLeader(String botId) {
        if (botId != null && !botId.isBlank()) {
            this.leader = botId.trim();
        }
    }

    public List<String> memberNames() {
        List<String> names = new ArrayList<>(members.size());
        for (MemberEntry m : members.values()) {
            names.add(m.id);
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    public int memberCount() {
        return members.size();
    }

    public boolean send(String from, String group, String to, String type, String body, SwarmPriority priority) {
        if (from == null || to == null || type == null || body == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        String useGroup = (group == null || group.isBlank()) ? groupId : group;
        if ("TCS".equals(type)) {
            observeTcs(from, to, body, now);
        }
        if ("*".equals(to)) {
            int delivered = 0;
            for (MemberEntry target : members.values()) {
                if (!target.id.equalsIgnoreCase(from)) {
                    target.inbox.add(new Envelope(from, useGroup, type, body, now));
                    delivered++;
                }
            }
            localMessagesSent.incrementAndGet();
            localMessagesDelivered.addAndGet(delivered);
            RemoteFallback fb = remoteFallback;
            if (fb != null) {
                fb.send(from, useGroup, to, type, body, priority);
            }
            return true;
        }
        MemberEntry localTarget = members.get(to.trim().toLowerCase(Locale.ROOT));
        if (localTarget != null) {
            localTarget.inbox.add(new Envelope(from, useGroup, type, body, now));
            localMessagesSent.incrementAndGet();
            localMessagesDelivered.incrementAndGet();
            return true;
        }
        RemoteFallback fb = remoteFallback;
        if (fb != null) {
            remoteFallbackSent.incrementAndGet();
            return fb.send(from, useGroup, to, type, body, priority);
        }
        return false;
    }

    /** Cooperative resource claim supporting {@link ReservationKey} box conflict detection. */
    public boolean tryClaim(String botId, String resourceKey, long ttlMs, String purpose) {
        if (botId == null || botId.isBlank() || resourceKey == null || resourceKey.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expires = ttlMs <= 0 ? Long.MAX_VALUE : now + ttlMs;
        synchronized (stateLock) {
            pruneExpiredClaimsLocked(now);
            for (ResourceClaim existing : claims.values()) {
                if (ReservationKey.conflicts(existing.resource(), resourceKey)) {
                    if (!existing.owner().equalsIgnoreCase(botId)) {
                        return false;
                    }
                    claims.remove(existing.resource());
                    break;
                }
            }
            claims.put(resourceKey, new ResourceClaim(resourceKey, botId, purpose == null ? "" : purpose, expires));
            return true;
        }
    }

    public boolean releaseClaim(String botId, String resourceKey) {
        if (botId == null || resourceKey == null) {
            return false;
        }
        synchronized (stateLock) {
            ResourceClaim c = claims.get(resourceKey);
            if (c != null && c.owner().equalsIgnoreCase(botId)) {
                claims.remove(resourceKey);
                return true;
            }
            return false;
        }
    }

    public List<String> releaseAllClaims(String botId) {
        synchronized (stateLock) {
            return releaseAllClaimsLocked(botId);
        }
    }

    private List<String> releaseAllClaimsLocked(String botId) {
        List<String> released = new ArrayList<>();
        claims.values().removeIf(c -> {
            if (c.owner().equalsIgnoreCase(botId)) {
                released.add(c.resource());
                return true;
            }
            return false;
        });
        return released;
    }

    private void pruneExpiredClaimsLocked(long now) {
        claims.values().removeIf(c -> c.expiresAtMs() < now);
    }

    public void recordObservation(String botId, String key, String kind, char dim, int[] box, String detail, int confidence) {
        if (key == null || key.isBlank()) {
            return;
        }
        synchronized (stateLock) {
            observations.put(key, new CoordinatorFeed.Observation(
                    key,
                    kind == null ? "block" : kind,
                    dim,
                    box,
                    botId == null ? "" : botId,
                    Math.max(0, Math.min(100, confidence)),
                    detail == null ? "" : detail,
                    "confirmed",
                    false,
                    0L));
            while (observations.size() > MAX_OBSERVATIONS) {
                String oldest = observations.keySet().iterator().next();
                observations.remove(oldest);
            }
        }
    }

    private void observeTcs(String from, String to, String body, long now) {
        Map<String, String> kv = parseS1(body);
        String op = kv.get("_op");
        if (op == null) {
            return;
        }
        synchronized (stateLock) {
            switch (op) {
                case "reg" -> leader = (leader == null) ? to : leader;
                case "offer" -> {
                    String aid = kv.get("a");
                    String item = kv.getOrDefault("item", "");
                    int count = parseInt(kv.get("n"), 1);
                    if (aid != null && !aid.isBlank()) {
                        assignments.put(aid, new TrackedAssignment(aid, to, item, count, "offered", 0, "", now));
                        claims.put("tcs:" + aid, new ResourceClaim(
                                "tcs:" + aid, to, "acquire " + item + " x" + count, now + 600_000L));
                    }
                }
                case "accept" -> {
                    String aid = kv.get("a");
                    TrackedAssignment prev = aid == null ? null : assignments.get(aid);
                    if (prev != null) {
                        assignments.put(aid, new TrackedAssignment(
                                prev.id, from, prev.item, prev.count, "granted", prev.progress, "", now));
                    }
                }
                case "prog" -> {
                    String aid = kv.get("a");
                    int pct = parseInt(kv.get("pct"), 0);
                    TrackedAssignment prev = aid == null ? null : assignments.get(aid);
                    if (prev != null) {
                        assignments.put(aid, new TrackedAssignment(
                                prev.id, from, prev.item, prev.count, "granted", pct, "", now));
                    }
                }
                case "reject" -> {
                    String aid = kv.get("a");
                    TrackedAssignment prev = aid == null ? null : assignments.get(aid);
                    if (prev != null) {
                        assignments.put(aid, new TrackedAssignment(
                                prev.id, from, prev.item, prev.count, "released", prev.progress,
                                kv.getOrDefault("why", "rejected"), now));
                    }
                    if (aid != null) {
                        claims.remove("tcs:" + aid);
                    }
                }
                case "result" -> {
                    String aid = kv.get("a");
                    String st = kv.getOrDefault("status", "FAILED");
                    String reason = kv.getOrDefault("reason", "");
                    TrackedAssignment prev = aid == null ? null : assignments.get(aid);
                    if (prev != null) {
                        assignments.put(aid, new TrackedAssignment(
                                prev.id, from, prev.item, prev.count,
                                "SUCCEEDED".equals(st) ? "released" : "expired",
                                "SUCCEEDED".equals(st) ? 100 : prev.progress,
                                reason.isEmpty() ? st.toLowerCase(Locale.ROOT) : reason,
                                now));
                    }
                    if (aid != null) {
                        claims.remove("tcs:" + aid);
                    }
                }
                case "cancel" -> {
                    String aid = kv.get("a");
                    TrackedAssignment prev = aid == null ? null : assignments.get(aid);
                    if (prev != null) {
                        assignments.put(aid, new TrackedAssignment(
                                prev.id, prev.bot, prev.item, prev.count, "released", prev.progress, "canceled", now));
                    }
                    if (aid != null) {
                        claims.remove("tcs:" + aid);
                    }
                }
                case "obs" -> {
                    String key = kv.get("key");
                    String val = kv.get("value");
                    if (key != null && val != null) {
                        int conf = (int) Math.round(parseDouble(kv.get("conf"), 0.7) * 100.0);
                        recordObservation(from, key, "block", 'o', null, val, conf);
                    }
                }
                default -> {
                }
            }
        }
    }

    public List<BotFeed.HeldLease> heldLeases(String botId, long nowMs) {
        List<BotFeed.HeldLease> out = new ArrayList<>();
        synchronized (stateLock) {
            pruneExpiredClaimsLocked(nowMs);
            for (ResourceClaim c : claims.values()) {
                if (c.owner().equalsIgnoreCase(botId)) {
                    long left = c.expiresAtMs() == Long.MAX_VALUE ? -1L : Math.max(0L, c.expiresAtMs() - nowMs);
                    boolean isTcs = c.resource().startsWith("tcs:");
                    String leaseId = isTcs ? c.resource().substring(4) : c.resource();
                    String resLabel = isTcs && !c.purpose().isBlank() ? c.purpose() : c.resource();
                    out.add(new BotFeed.HeldLease(leaseId, 1L, resLabel, "valid", left, true));
                }
            }
        }
        return out;
    }

    public BotFeed.Link linkFor(String botId) {
        boolean up = isRegistered(botId);
        int peers = up ? Math.max(0, memberCount() - 1) : 0;
        return new BotFeed.Link(up, peers, up ? "" : "not registered");
    }

    public CoordinatorFeed toCoordinatorFeed(String fallbackSelf, long nowMs, long seq, String world) {
        List<CoordinatorFeed.Lease> leases = new ArrayList<>();
        List<CoordinatorFeed.Observation> obs;
        synchronized (stateLock) {
            pruneExpiredClaimsLocked(nowMs);
            for (TrackedAssignment a : assignments.values()) {
                String exec = "released".equals(a.state) && a.progress == 100 ? "succeeded"
                        : ("expired".equals(a.state) ? "failed" : ("offered".equals(a.state) ? "accepted" : "running"));
                leases.add(new CoordinatorFeed.Lease(
                        a.id,
                        a.item + "x" + a.count,
                        "key",
                        'o',
                        null,
                        a.bot,
                        1L,
                        a.state,
                        "@swarm acquire " + a.item + " " + a.count,
                        exec,
                        "confirmed",
                        a.reason));
            }
            for (ResourceClaim c : claims.values()) {
                if (c.resource().startsWith("tcs:")) {
                    continue;
                }
                ReservationKey rk = ReservationKey.parse(c.resource());
                leases.add(new CoordinatorFeed.Lease(
                        c.resource(),
                        c.resource(),
                        rk == null ? "key" : rk.typeName(),
                        rk == null ? '?' : rk.dim,
                        rk == null ? null : new int[]{rk.x1, rk.y1, rk.z1, rk.x2, rk.y2, rk.z2},
                        c.owner(),
                        1L,
                        "granted",
                        c.purpose(),
                        "running",
                        "confirmed",
                        ""));
            }
            obs = new ArrayList<>(observations.values());
        }
        String lead = leader != null ? leader : (fallbackSelf == null ? "" : fallbackSelf);
        return new CoordinatorFeed(
                lead,
                nowMs,
                seq,
                members.isEmpty() ? "idle" : "active",
                world == null ? "" : world,
                leases,
                obs,
                obs.size());
    }

    public JsonObject statusJson() {
        long now = System.currentTimeMillis();
        JsonObject root = new JsonObject();
        root.addProperty("group", groupId);
        root.addProperty("leader", leader == null ? "" : leader);
        root.addProperty("localMessagesSent", localMessagesSent.get());
        root.addProperty("localMessagesDelivered", localMessagesDelivered.get());
        root.addProperty("remoteFallbackSent", remoteFallbackSent.get());

        JsonArray memberArr = new JsonArray();
        for (String m : memberNames()) {
            memberArr.add(m);
        }
        root.add("members", memberArr);

        JsonArray claimArr = new JsonArray();
        JsonArray assignArr = new JsonArray();
        synchronized (stateLock) {
            pruneExpiredClaimsLocked(now);
            for (ResourceClaim c : claims.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("resource", c.resource());
                o.addProperty("owner", c.owner());
                o.addProperty("purpose", c.purpose());
                claimArr.add(o);
            }
            assignments.values().stream()
                    .sorted(Comparator.comparing(TrackedAssignment::id))
                    .forEach(a -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("id", a.id());
                        o.addProperty("bot", a.bot());
                        o.addProperty("item", a.item());
                        o.addProperty("count", a.count());
                        o.addProperty("state", a.state());
                        o.addProperty("progress", a.progress());
                        o.addProperty("reason", a.reason());
                        assignArr.add(o);
                    });
        }
        root.add("claims", claimArr);
        root.add("assignments", assignArr);
        return root;
    }

    public long localMessagesSent() {
        return localMessagesSent.get();
    }

    public long localMessagesDelivered() {
        return localMessagesDelivered.get();
    }

    public long remoteFallbackSent() {
        return remoteFallbackSent.get();
    }

    private static Map<String, String> parseS1(String line) {
        Map<String, String> out = new LinkedHashMap<>();
        if (line == null) {
            return out;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2 || !"s1".equals(parts[0])) {
            return out;
        }
        out.put("_op", parts[1].toLowerCase(Locale.ROOT));
        for (int i = 2; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                out.put(parts[i].substring(0, eq), unescape(parts[i].substring(eq + 1)));
            }
        }
        return out;
    }

    private static String unescape(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            b.append(c);
        }
        return b.toString();
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return s == null ? def : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
