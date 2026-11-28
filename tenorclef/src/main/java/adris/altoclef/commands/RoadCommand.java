package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.args.Arg;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code @road} (thin high-level TenorClef wrapper delegating strictly through {@code baritone.api.process.IRoadProcess}
 * to Ostinato's terrain road planner, preview, guarded builder, and physical verifier).
 * <pre>
 * @road plan hall village protect=10,-4:20,4 budget=48
 * @road preview
 * @road accept
 * @road build hall village protect=10,-4:20,4 budget=48
 * @road verify
 * @road status
 * @road cancel
 * </pre>
 */
public class RoadCommand extends Command {

    public RoadCommand() {
        super("road", "Plan, preview, build, and verify a level 2-block-wide road via Ostinato.",
                new StringArg("action", "status"),
                new RestStringArg("args", ""));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String action = parser.get(String.class);
        String rest = parser.get(String.class);
        String sub = action == null ? "status" : action.trim().toLowerCase(Locale.ROOT);
        //#if MC < 260000
        Debug.logWarning("@road is only supported on Minecraft 26.3 with Ostinato IRoadProcess.");
        finish();
        //#else
        //$$ baritone.api.process.IRoadProcess road = mod.getClientBaritone().getRoadProcess();
        //$$ if (road == null) {
        //$$     Debug.logWarning("Ostinato road process is not available.");
        //$$     finish();
        //$$     return;
        //$$ }
        //$$ String out;
        //$$ switch (sub) {
        //$$     case "plan" -> out = road.plan(rest);
        //$$     case "preview", "bom" -> out = road.preview();
        //$$     case "accept", "confirm", "go" -> out = road.accept();
        //$$     case "build" -> out = road.planAndBuild(rest);
        //$$     case "verify", "check" -> out = road.verify();
        //$$     case "status" -> out = road.status();
        //$$     case "cancel", "stop" -> out = road.cancel();
        //$$     default -> out = "Usage: @road plan|preview|accept|build|verify|status|cancel ...";
        //$$ }
        //$$ if (out != null && !out.isEmpty()) {
        //$$     for (String line : out.split("\n")) {
        //$$         Debug.logMessage(line);
        //$$     }
        //$$ }
        //$$ finish();
        //#endif
    }

    private static final class RestStringArg extends Arg<String> {
        RestStringArg(String name, String defaultValue) {
            super(name, defaultValue);
        }

        @Override
        public Stream<String> getSuggestions(StringReader reader) {
            return Stream.empty();
        }

        @Override
        protected StringParser<String> getParser() {
            return reader -> {
                StringBuilder sb = new StringBuilder();
                while (reader.hasNext()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(reader.next());
                }
                return sb.toString();
            };
        }

        @Override
        public String getTypeName() {
            return "RoadArgs";
        }

        @Override
        public Class<String> getType() {
            return String.class;
        }
    }
}
