package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.compose.DemoCommandParser;
import adris.altoclef.compose.DemoService;

import java.io.IOException;
import java.util.List;

/**
 * Normal player command entry point for teach-by-demonstration routines:
 * <ul>
 *     <li>{@code @teach start [sourceStation] [destStation]} (aliases: {@code record}, {@code watch})</li>
 *     <li>{@code @teach stop} — stop recording and show editable summary (or stop active replay)</li>
 *     <li>{@code @teach summary} — inspect draft/saved summary and last replay outcome</li>
 *     <li>{@code @teach edit <step#> <station|item|count|action> <value>}</li>
 *     <li>{@code @teach edit <step#> <withdraw|deposit> <count> <item> <station>}</li>
 *     <li>{@code @teach confirm <step#> [<withdraw|deposit> <count> <item> <station>]}</li>
 *     <li>{@code @teach remove <step#>}</li>
 *     <li>{@code @teach save <routineName>}</li>
 *     <li>{@code @teach load <routineName>}</li>
 *     <li>{@code @teach list}</li>
 *     <li>{@code @teach bind <stationName> [<x> <y> <z>]}</li>
 *     <li>{@code @teach bindings}</li>
 *     <li>{@code @teach run <routineName>}</li>
 *     <li>{@code @teach cancel}</li>
 * </ul>
 */
public class TeachCommand extends Command {

    private String rawArgs = "";

    public TeachCommand() {
        super(List.of("teach", "demo"),
                "Teach container transfer routines by demonstration: @teach <start|stop|summary|edit|confirm|save|bind|run|list|cancel>");
    }

    @Override
    public void run(AltoClef mod, String line, Runnable onFinish) throws CommandException {
        String trimmed = line == null ? "" : line.trim();
        int space = trimmed.indexOf(' ');
        rawArgs = space >= 0 ? trimmed.substring(space + 1).trim() : "";
        super.run(mod, space >= 0 ? trimmed.substring(0, space) : trimmed, onFinish);
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        DemoCommandParser.ParsedCommand parsed;
        try {
            parsed = DemoCommandParser.parse(rawArgs);
        } catch (IllegalArgumentException e) {
            Debug.logWarning("TEACH: " + e.getMessage());
            finish();
            return;
        }

        DemoService svc = DemoService.INSTANCE;
        try {
            String output = switch (parsed.action()) {
                case START -> svc.startRecording(mod, parsed.hints());
                case STOP -> svc.stopRecordingOrReplay(mod);
                case CANCEL -> svc.cancel(mod);
                case SUMMARY -> svc.summary(mod);
                case EDIT_FIELD -> svc.editField(parsed.stepIndex(), parsed.field(), parsed.value());
                case EDIT_FULL -> svc.editFull(parsed.stepIndex(), parsed.stepAction(),
                        parsed.count(), parsed.itemId(), parsed.stationName(), false);
                case CONFIRM -> svc.confirmStep(parsed.stepIndex(), parsed);
                case REMOVE -> svc.removeStep(parsed.stepIndex());
                case SAVE -> svc.saveDraft(parsed.name());
                case LOAD -> svc.loadRoutine(parsed.name());
                case LIST -> svc.listRoutines();
                case BIND -> svc.bindStation(mod, parsed.stationName(), parsed.x(), parsed.y(), parsed.z());
                case BINDINGS -> svc.listBindings();
                case RUN -> svc.runRoutine(mod, parsed.name());
            };
            if (output != null && !output.isBlank()) {
                Debug.logMessage("TEACH: " + output);
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            Debug.logWarning("TEACH: " + e.getMessage());
        }
        finish();
    }
}
