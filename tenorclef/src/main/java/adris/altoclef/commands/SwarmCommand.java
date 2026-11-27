package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.swarm.Assignment;
import adris.altoclef.swarm.Capability;
import adris.altoclef.swarm.Objective;
import adris.altoclef.swarm.SwarmAdapter;
import adris.altoclef.swarm.SwarmCoordinator;
import adris.altoclef.swarm.SwarmLedger;
import adris.altoclef.swarm.SwarmRuntime;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@code @swarm} drives TenorClef's Swarm Coordinator and Ostinato's swarm link.
 */
public class SwarmCommand extends Command {

    public SwarmCommand() {
        super("swarm", "Drive the Swarm coordinator and Ostinato swarm link",
                new StringArg("sub", "status"),
                new StringArg("arg1", ""),
                new StringArg("arg2", ""),
                new StringArg("arg3", ""),
                new StringArg("arg4", ""),
                new StringArg("arg5", ""));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String tok = parser.get(String.class);
            if (tok == null || tok.isBlank()) {
                break;
            }
            tokens.add(tok.trim());
        }
        String raw = tokens.isEmpty() ? "status" : String.join(" ", tokens);

        String[] p = raw.split("\\s+");
        String sub = p[0].toLowerCase();
        boolean coordOp = sub.equals("lead") || sub.equals("join") || sub.equals("leave")
                || sub.equals("acquire") || sub.equals("split") || sub.equals("jobs")
                || sub.equals("coord") || sub.equals("why") || sub.equals("local");

        if (!coordOp && !SwarmAdapter.available() && SwarmRuntime.problem() != null) {
            Debug.logWarning("SWARM " + SwarmAdapter.detail());
            finish();
            return;
        }

        switch (sub) {
            case "lead" -> {
                try {
                    SwarmRuntime.lead(mod);
                    Debug.logMessage("SWARM leading as " + SwarmRuntime.link().self());
                } catch (RuntimeException e) {
                    Debug.logWarning("SWARM lead failed: " + e.getMessage());
                }
            }
            case "join" -> {
                try {
                    Set<Capability> caps = p.length >= 2 ? Capability.decode(p[1]) : EnumSet.allOf(Capability.class);
                    if (caps.isEmpty()) caps = EnumSet.allOf(Capability.class);
                    SwarmRuntime.join(mod, caps);
                    Debug.logMessage("SWARM joined leader " + SwarmRuntime.link().leader() + " caps=" + caps);
                } catch (RuntimeException e) {
                    Debug.logWarning("SWARM join failed: " + e.getMessage());
                }
            }
            case "leave" -> {
                SwarmRuntime.leave();
                Debug.logMessage("SWARM left");
            }
            case "acquire", "split" -> {
                SwarmCoordinator leader = SwarmRuntime.leader();
                if (leader == null) {
                    Debug.logWarning("SWARM not leading (run @swarm lead first)");
                    break;
                }
                if (p.length < 2) {
                    Debug.logWarning("SWARM @swarm " + sub + " <item> [count]");
                    break;
                }
                String item = p[1];
                int count = 1;
                if (p.length >= 3) {
                    try {
                        count = Math.max(1, Integer.parseInt(p[2]));
                    } catch (NumberFormatException e) {
                        Debug.logWarning("SWARM invalid count: " + p[2]);
                        break;
                    }
                }
                Objective obj = Objective.acquire(item, count);
                if (sub.equals("split") || (p.length >= 4 && "split".equalsIgnoreCase(p[3]))) {
                    int ready = Math.max(1, (int) leader.agents().stream()
                            .filter(a -> !a.lifecycle().isGone()).count());
                    int parts = Math.min(ready, count);
                    int base = count / parts;
                    int rem = count % parts;
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < parts; i++) {
                        int share = base + (i < rem ? 1 : 0);
                        if (share > 0) {
                            Objective partObj = Objective.acquire(item, share);
                            Assignment a = leader.submit(leader.create(partObj, 5, partObj.defaultCapabilities()), now);
                            Debug.logMessage("SWARM submitted " + a.id + " (" + partObj.describe() + ")");
                        }
                    }
                } else {
                    Assignment a = leader.submit(leader.create(obj, 5, EnumSet.noneOf(Capability.class)), System.currentTimeMillis());
                    Debug.logMessage("SWARM submitted " + a.id + " (" + obj.describe() + ")");
                }
            }
            case "jobs", "coord" -> {
                SwarmCoordinator leader = SwarmRuntime.leader();
                if (leader != null) {
                    for (String line : leader.status()) {
                        Debug.logMessage("SWARM " + line);
                    }
                } else if (SwarmRuntime.worker() != null) {
                    Debug.logMessage("SWARM worker=" + SwarmRuntime.worker().id()
                            + " current=" + SwarmRuntime.worker().currentAssignment());
                } else {
                    Debug.logMessage("SWARM neither leading nor joined");
                }
            }
            case "why" -> {
                SwarmCoordinator leader = SwarmRuntime.leader();
                if (leader == null) {
                    Debug.logWarning("SWARM not leading");
                    break;
                }
                if (p.length >= 2) {
                    String story = leader.ledger().why(p[1]);
                    Debug.logMessage(story.isEmpty() ? "SWARM no events for " + p[1] : story);
                } else {
                    var events = leader.ledger().all();
                    int from = Math.max(0, events.size() - 10);
                    for (int i = from; i < events.size(); i++) {
                        Debug.logMessage("SWARM " + events.get(i));
                    }
                }
            }
            case "local" -> {
                SwarmCoordinator leader = SwarmRuntime.local(mod);
                if (p.length >= 2) {
                    int count = p.length >= 3 ? Math.max(1, Integer.parseInt(p[2])) : 1;
                    Objective obj = Objective.acquire(p[1], count);
                    Assignment a = leader.submit(leader.create(obj, 5, EnumSet.noneOf(Capability.class)), System.currentTimeMillis());
                    Debug.logMessage("SWARM local " + a.id + " (" + obj.describe() + ")");
                } else {
                    Debug.logMessage("SWARM local loopback ready");
                }
            }
            case "on" -> {
                SwarmAdapter.setEnabled(true);
                Debug.logMessage("SWARM on (roster=" + SwarmAdapter.getString("swarmRosterFile") + ")");
            }
            case "off" -> {
                SwarmAdapter.setEnabled(false);
                Debug.logMessage("SWARM off");
            }
            case "menu" -> {
                adris.altoclef.tasks.speedrun.testrun2.gui.T2MenuScreen.openTab(6);
            }
            case "set" -> {
                if (p.length < 3) {
                    Debug.logWarning("SWARM @swarm set <setting> <value>");
                } else {
                    String name = p[1].startsWith("swarm") ? p[1] : "swarm" + Character.toUpperCase(p[1].charAt(0)) + p[1].substring(1);
                    String err = SwarmAdapter.set(name, raw.substring(raw.indexOf(p[1]) + p[1].length()).trim());
                    Debug.logMessage(err == null ? "SWARM " + name + " = " + SwarmAdapter.getString(name) : "SWARM " + err);
                }
            }
            case "status" -> {
                SwarmCoordinator leader = SwarmRuntime.leader();
                if (leader != null) {
                    for (String line : leader.status()) {
                        Debug.logMessage("SWARM " + line);
                    }
                } else if (SwarmRuntime.worker() != null) {
                    Debug.logMessage("SWARM worker=" + SwarmRuntime.worker().id()
                            + " current=" + SwarmRuntime.worker().currentAssignment());
                }
                if (SwarmAdapter.available()) {
                    SwarmAdapter.run(raw);
                }
            }
            case "ping", "reload", "build", "stop", "spawn", "despawn", "bots" -> { // Ostinato: unified OWNER command.
                if (!SwarmAdapter.run(raw)) {
                    Debug.logWarning("SWARM '" + raw + "' was not accepted");
                }
            }
            case "send" -> {
                if (p.length < 3) {
                    Debug.logWarning("SWARM @swarm send <bot> <command...>");
                } else {
                    String command = raw.substring(raw.indexOf(p[1]) + p[1].length()).trim();
                    Debug.logMessage("SWARM " + protocolCall("submitCommand", p[1], command));
                }
            }
            case "outcome" -> {
                if (p.length < 2) {
                    Debug.logWarning("SWARM @swarm outcome <command-id>");
                } else {
                    Debug.logMessage("SWARM " + protocolCall("commandOutcome", p[1]));
                }
            }
            case "cancel" -> {
                SwarmCoordinator leader = SwarmRuntime.leader();
                if (leader != null && (p.length < 2 || leader.assignment(p[1]) != null)) {
                    long now = System.currentTimeMillis();
                    if (p.length >= 2) {
                        Debug.logMessage("SWARM cancel " + p[1] + " -> " + leader.cancel(p[1], now));
                    } else {
                        int cancelled = 0;
                        for (Assignment a : leader.assignments()) {
                            if (leader.cancel(a.id, now)) cancelled++;
                        }
                        Debug.logMessage("SWARM cancelled " + cancelled + " assignment(s)");
                    }
                } else if (p.length < 2) {
                    Debug.logWarning("SWARM @swarm cancel <command-id>");
                } else {
                    Debug.logMessage("SWARM " + protocolCall("cancelCommand", p[1]));
                }
            }
            default -> Debug.logWarning("SWARM lead|join|leave|acquire|split|jobs|why|local|status|on|off|ping|reload|build|stop|set|menu|send|outcome|cancel|spawn|despawn|bots");
        }
        finish();
    }

    /**
     * Phase 1 methods are invoked by name so this source still compiles against a Baritone jar that
     * does not have them. When the flag is off the implementation returns without sending.
     */
    private static String protocolCall(String method, String... args) {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone == null || baritone.getSwarm() == null) {
                return "swarm: no baritone";
            }
            Object swarm = baritone.getSwarm();
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = String.class;
            }
            Object result = swarm.getClass().getMethod(method, types).invoke(swarm, (Object[]) args);
            return result == null ? "" : String.valueOf(result);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            String message = cause.getMessage();
            return "swarm: " + (message == null ? cause.getClass().getSimpleName() : message);
        }
    }
}
