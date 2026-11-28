package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.args.Arg;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.shop.ShoppingRunner;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code @shop} (mirrors Ostinato's {@code #shop}): gather a multi-item shopping list in survival.
 * <pre>
 * @shop gather oak_log:32 cobblestone:64 diamond:5
 * @shop oak_log:32 cobblestone:64
 * @shop status
 * @shop stop
 * </pre>
 */
public class ShopCommand extends Command {

    public ShopCommand() {
        super("shop", "Gather a multi-item shopping list. gather <item:count ...> | status | stop",
                new StringArg("action", "status"),
                new RestStringArg("items", ""));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String action = parser.get(String.class);
        String rest = parser.get(String.class);
        String sub = action == null ? "status" : action.trim().toLowerCase(Locale.ROOT);
        String out;
        switch (sub) {
            case "gather", "start", "buy", "get" -> out = ShoppingRunner.gather(rest);
            case "stop", "cancel" -> out = ShoppingRunner.stop();
            case "status" -> out = ShoppingRunner.status();
            default -> {
                String combined = (action == null ? "" : action) + (rest == null || rest.isBlank() ? "" : " " + rest);
                out = ShoppingRunner.gather(combined);
            }
        }
        for (String line : out.split("\n")) {
            Debug.logMessage(line);
        }
        finish();
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
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(reader.next());
                }
                return sb.toString();
            };
        }

        @Override
        public String getTypeName() {
            return "Items";
        }

        @Override
        public Class<String> getType() {
            return String.class;
        }
    }
}
