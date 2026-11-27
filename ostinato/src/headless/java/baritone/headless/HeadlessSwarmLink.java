/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.headless;

import baritone.api.swarm.ISwarm;
import baritone.api.swarm.ISwarmGroup;
import baritone.api.swarm.SwarmPriority;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-bot {@link ISwarm} bridge backed by {@link HeadlessSwarmBus}.
 * Inbound messages are queued and drained only on the owning bot's tick thread
 * inside its {@code BotContextScope}.
 */
public final class HeadlessSwarmLink implements ISwarm {

    private static final Set<String> RESERVED_TYPES = Set.of(
            "PING", "PONG", "SWB", "SWS", "SWX", "SWC", "SWA", "SWH", "STS");

    private final HeadlessSwarmBus bus;
    private final String self;
    private final ConcurrentLinkedQueue<HeadlessSwarmBus.Envelope> inbox;
    private final HeadlessFleetController fleet;
    private final Map<String, SwarmHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, String> submittedCommandBots = new ConcurrentHashMap<>();
    private volatile boolean closed;

    HeadlessSwarmLink(
            HeadlessSwarmBus bus,
            String self,
            ConcurrentLinkedQueue<HeadlessSwarmBus.Envelope> inbox,
            HeadlessFleetController fleet) {
        this.bus = bus;
        this.self = self;
        this.inbox = inbox;
        this.fleet = fleet;
    }

    void markClosed() {
        closed = true;
    }

    public HeadlessSwarmBus bus() {
        return bus;
    }

    /**
     * Drains all queued messages for this bot on its own tick thread.
     * Must be invoked inside {@code BotContextScope.runWith(botContext, ...)}.
     */
    public void drainInbox() {
        if (closed) {
            return;
        }
        HeadlessSwarmBus.Envelope env;
        while ((env = inbox.poll()) != null) {
            SwarmHandler handler = handlers.get(env.type());
            if (handler != null) {
                try {
                    handler.onMessage(env.from(), env.group(), env.body());
                } catch (RuntimeException e) {
                    System.err.println("[HeadlessSwarmLink " + self + "] handler error for "
                            + env.type() + ": " + e.getMessage());
                }
            }
        }
    }

    @Override
    public boolean isOnline() {
        return !closed && bus.isRegistered(self);
    }

    @Override
    public String state() {
        return isOnline() ? "online" : "offline";
    }

    @Override
    public String self() {
        return isOnline() ? self : null;
    }

    @Override
    public List<ISwarmGroup> myGroups() {
        if (!isOnline()) {
            return List.of();
        }
        return List.of(groupView());
    }

    @Override
    public ISwarmGroup group(String id) {
        if (!isOnline() || id == null || !id.equals(bus.groupId())) {
            return null;
        }
        return groupView();
    }

    @Override
    public boolean send(String group, String to, String type, String body, SwarmPriority priority) {
        if (!isOnline()) {
            return false;
        }
        return bus.send(self, group, to, type, body, priority);
    }

    @Override
    public boolean registerHandler(String type, SwarmHandler handler) {
        if (!isOnline() || type == null || handler == null) {
            return false;
        }
        if (RESERVED_TYPES.contains(type)) {
            throw new IllegalArgumentException("reserved swarm message type " + type);
        }
        handlers.put(type, handler);
        return true;
    }

    @Override
    public String submitCommand(String target, String commandLine) {
        if (fleet == null || target == null || target.isBlank() || commandLine == null || commandLine.isBlank()) {
            return "swarm: command refused";
        }
        String cmdId = UUID.randomUUID().toString();
        try {
            fleet.dispatchCommand(target, commandLine, self, cmdId);
            submittedCommandBots.put(cmdId, target);
            return "swarm: sent " + cmdId + " to " + target;
        } catch (RuntimeException e) {
            return "swarm: " + e.getMessage();
        }
    }

    @Override
    public String commandOutcome(String commandId) {
        String bot = commandId == null ? null : submittedCommandBots.get(commandId);
        return bot == null ? "swarm: unknown command " + commandId : "swarm: " + commandId + " running on " + bot;
    }

    @Override
    public String cancelCommand(String commandId) {
        String bot = commandId == null ? null : submittedCommandBots.get(commandId);
        if (fleet == null || bot == null) {
            return "swarm: unknown command " + commandId;
        }
        try {
            fleet.dispatchCommand(bot, "cancel", self, commandId);
            return "swarm: canceled " + commandId + " on " + bot;
        } catch (RuntimeException e) {
            return "swarm: " + e.getMessage();
        }
    }

    private ISwarmGroup groupView() {
        return new ISwarmGroup() {
            @Override
            public String id() {
                return bus.groupId();
            }

            @Override
            public String lead() {
                return bus.leader();
            }

            @Override
            public List<String> members() {
                return bus.memberNames();
            }

            @Override
            public boolean has(String member) {
                return member != null && bus.isRegistered(member);
            }
        };
    }
}
