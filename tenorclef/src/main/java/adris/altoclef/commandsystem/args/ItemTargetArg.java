package adris.altoclef.commandsystem.args;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.commandsystem.exception.BadCommandSyntaxException;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.CommandNotFinishedException;
import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.tasks.trading.TradeRequestParser;
import adris.altoclef.util.ItemTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class ItemTargetArg extends Arg<ItemTarget> {

    public ItemTargetArg(String name) {
        super(name);
    }

    public ItemTargetArg(String name, ItemTarget defaultValue) {
        super(name, defaultValue);
    }

    public ItemTargetArg(String name, ItemTarget defaultValue, boolean showDefault) {
        super(name, defaultValue, showDefault);
    }


    private static ItemTarget parse(StringReader reader) throws CommandException {
        String rawToken = reader.next();
        TradeRequestParser.ParsedItemToken parsedToken;
        try {
            parsedToken = TradeRequestParser.parseItemToken(rawToken);
        } catch (IllegalArgumentException e) {
            if (rawToken.contains("{") && !rawToken.endsWith("}")) {
                throw new CommandNotFinishedException(e.getMessage());
            }
            throw new BadCommandSyntaxException(e.getMessage());
        }

        String item = parsedToken.baseName();
        if (!TaskCatalogue.taskExists(item)) {
            boolean begins = CataloguedItemArg.listSuggestions().anyMatch(s -> s.startsWith(item));
            String errorMsg = "No catalogued item named '" + item + "'";
            if (begins) {
                throw new CommandNotFinishedException(errorMsg);
            } else {
                throw new BadCommandSyntaxException(errorMsg);
            }
        }

        int count = 1;
        Integer parsedCount = Arg.parseIfSupplied(reader, IntArg::parse);
        if (parsedCount != null) {
            count = parsedCount;
        }

        List<String> remaining = new ArrayList<>();
        StringReader lookahead = reader.copy();
        while (lookahead.hasNext()) {
            remaining.add(lookahead.next());
        }

        TradeRequestParser.ParsedModifiers modifiers;
        try {
            modifiers = TradeRequestParser.parseModifiers(remaining, 0);
        } catch (IllegalArgumentException e) {
            throw new BadCommandSyntaxException(e.getMessage());
        }
        for (int i = 0; i < modifiers.tokensConsumed(); i++) {
            reader.next();
        }

        ItemTarget target = new ItemTarget(item, count);
        if (parsedToken.componentSpec() != null) {
            target = target.withComponentSpec(parsedToken.componentSpec());
        }
        if (modifiers.maxEmeralds() >= 0) {
            target = target.withMaxEmeraldCost(modifiers.maxEmeralds());
        }
        if (modifiers.preferredMerchantId() != null) {
            target = target.withPreferredMerchantId(modifiers.preferredMerchantId());
        }
        return target;
    }

    @Override
    public Class<ItemTarget> getType() {
        return ItemTarget.class;
    }

    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        if (getSupplied(reader, CataloguedItemArg::parse) == ParseResult.NOT_FINISHED) {
            return CataloguedItemArg.listSuggestions();
        }
        return Stream.empty();
    }

    @Override
    public String getTypeName() {
        return "Item Target";
    }

    @Override
    protected StringParser<ItemTarget> getParser() {
        return ItemTargetArg::parse;
    }
}
