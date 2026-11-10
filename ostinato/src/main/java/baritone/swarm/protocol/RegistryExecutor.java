/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.swarm.protocol;

import baritone.api.IBaritone;
import baritone.api.command.CommandPermission;
import baritone.api.command.ICommand;
import baritone.api.command.manager.ICommandManager;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.process.IBaritoneProcess;
import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ExternalCommandHook;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Ostinato: runs a swarm command through {@link ICommandManager#execute}, the single registry entry point.
 * TenorClef {@code @} commands go through {@link ExternalCommandHook} only when the registry has no match.
 * This class does not cancel survival or an owner pause; it only reports whether a process is still active.
 */
public final class RegistryExecutor implements CommandProtocol.Executor {

    private final IBaritone baritone;
    private final Supplier<ExternalCommandHook> hook;
    private final Map<UUID, String> started = new ConcurrentHashMap<>();
    private final Map<String, CommandCatalog.Entry> atCommands;

    public RegistryExecutor(IBaritone baritone, Supplier<ExternalCommandHook> hook) {
        this(baritone, hook, null);
    }

    public RegistryExecutor(IBaritone baritone, Supplier<ExternalCommandHook> hook, String atCatalog) {
        this.baritone = baritone;
        this.hook = hook == null ? () -> null : hook;
        this.atCommands = CommandCatalog.index(CommandCatalog.parse(atCatalog));
    }

    private ICommandManager commands() {
        return baritone.getCommandManager();
    }

    @Override
    public CommandPermission permissionOf(String name) {
        CommandCatalog.Entry entry = describe(name);
        return entry == null ? null : entry.permission;
    }

    @Override
    public CommandCatalog.Entry describe(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        ICommand command = commands().getCommand(name);
        if (command != null) {
            return CommandCatalog.infer(name, command.getPermission());
        }
        CommandCatalog.Entry at = atCommands.get(name.toLowerCase(java.util.Locale.ROOT));
        if (at != null) {
            return at;
        }
        ExternalCommandHook extra = hook.get();
        if (extra != null && extra.knows(name)) {
            return CommandCatalog.at(name);
        }
        return null;
    }

    @Override
    public boolean start(UUID id, String name, String args) {
        String line = args == null || args.isEmpty() ? name : name + " " + args;
        ICommand command = commands().getCommand(name);
        if (command != null) {
            boolean ok = commands().execute(line);
            if (ok) {
                started.put(id, name);
            }
            return ok;
        }
        ExternalCommandHook extra = hook.get();
        if (extra != null && extra.knows(name)) {
            boolean ok = extra.start(id, name, args == null ? "" : args);
            if (ok) {
                started.put(id, name);
            }
            return ok;
        }
        return false;
    }

    @Override
    public boolean confirmStopped(UUID id) {
        String name = started.get(id);
        ExternalCommandHook extra = hook.get();
        if (name != null && extra != null && commands().getCommand(name) == null && extra.knows(name)) {
            return extra.confirmStopped(id);
        }
        try {
            IPathingControlManager mgr = baritone.getPathingControlManager();
            if (mgr == null) {
                return false;
            }
            Optional<IBaritoneProcess> cur = mgr.mostRecentInControl();
            if (cur.isPresent() && cur.get().isActive()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    @Override
    public ExecutionState poll(UUID id) {
        // Idle is not a verified success. The caller marks succeeded only when this returns a terminal state.
        return null;
    }
}
