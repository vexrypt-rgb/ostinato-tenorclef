package adris.altoclef.tasks.trading;

import adris.altoclef.util.ItemComponentSpec;

import java.util.List;
import java.util.Objects;

/**
 * Pure-Java parser for extended {@code @get} item-target syntax carrying exact component/enchantment
 * specifiers and optional trade constraints, e.g.:
 * <ul>
 *     <li>{@code enchanted_book{mending} 1 max_emeralds=20}</li>
 *     <li>{@code enchanted_book{mending=1} max_emeralds=20 villager=<uuid-or-id>}</li>
 *     <li>{@code oak_planks 16} (plain catalogue item — unchanged)</li>
 * </ul>
 */
public final class TradeRequestParser {

    private TradeRequestParser() {
    }

    public record ParsedItemToken(String baseName, ItemComponentSpec componentSpec) {
    }

    public record ParsedModifiers(int maxEmeralds, String preferredMerchantId, int tokensConsumed) {
    }

    public record ParsedGetTarget(String baseName,
                                  ItemComponentSpec componentSpec,
                                  int count,
                                  int maxEmeralds,
                                  String preferredMerchantId) {
        public VillagerTradeRequest toTradeRequest() {
            return new VillagerTradeRequest(baseName, componentSpec, count, maxEmeralds, preferredMerchantId);
        }
    }

    /**
     * Splits an item token such as {@code enchanted_book{mending}} into its catalogue name
     * and optional {@link ItemComponentSpec}. Plain names return {@code null} componentSpec.
     */
    public static ParsedItemToken parseItemToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Item token cannot be empty");
        }
        int open = rawToken.indexOf('{');
        if (open < 0) {
            if (rawToken.indexOf('}') >= 0) {
                throw new IllegalArgumentException("Unexpected '}' in item token '" + rawToken + "'");
            }
            return new ParsedItemToken(rawToken, null);
        }
        String baseName = rawToken.substring(0, open).trim();
        if (baseName.isEmpty()) {
            throw new IllegalArgumentException("Missing item name before '{' in '" + rawToken + "'");
        }
        if (!rawToken.endsWith("}")) {
            throw new IllegalArgumentException("Unclosed '{' in item token '" + rawToken + "'");
        }
        String specRaw = rawToken.substring(open);
        ItemComponentSpec spec = ItemComponentSpec.parse(specRaw);
        return new ParsedItemToken(baseName, spec);
    }

    /**
     * Parses recognized trailing {@code key=value} modifiers from {@code tokens} starting at {@code startIndex}.
     * Stops at the first unrecognized token without consuming it.
     */
    public static ParsedModifiers parseModifiers(List<String> tokens, int startIndex) {
        int maxEmeralds = VillagerTradeRequest.UNCAPPED_EMERALDS;
        String preferredMerchant = null;
        int consumed = 0;
        if (tokens != null) {
            for (int i = startIndex; i < tokens.size(); i++) {
                String tok = tokens.get(i);
                if (tok == null) break;
                if (tok.startsWith("max_emeralds=") || tok.startsWith("budget=")) {
                    String val = tok.substring(tok.indexOf('=') + 1).trim();
                    if (val.isEmpty()) {
                        throw new IllegalArgumentException("Missing integer value in '" + tok + "'");
                    }
                    int parsed;
                    try {
                        parsed = Integer.parseInt(val);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid emerald cap '" + val + "' in '" + tok + "'", e);
                    }
                    if (parsed < 0) {
                        throw new IllegalArgumentException("Emerald cap must be >= 0, got " + parsed);
                    }
                    maxEmeralds = parsed;
                    consumed++;
                } else if (tok.startsWith("villager=") || tok.startsWith("merchant=")) {
                    String val = tok.substring(tok.indexOf('=') + 1).trim();
                    if (val.isEmpty()) {
                        throw new IllegalArgumentException("Missing villager/merchant identifier in '" + tok + "'");
                    }
                    preferredMerchant = val;
                    consumed++;
                } else {
                    break;
                }
            }
        }
        return new ParsedModifiers(maxEmeralds, preferredMerchant, consumed);
    }

    /** Convenience parser for a full space-separated item-target expression (used in tests and tooling). */
    public static ParsedGetTarget parseExpression(String expression) {
        Objects.requireNonNull(expression, "expression");
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty item target expression");
        }
        List<String> tokens = List.of(trimmed.split("\\s+"));
        ParsedItemToken itemTok = parseItemToken(tokens.get(0));
        int idx = 1;
        int count = 1;
        if (idx < tokens.size()) {
            String maybeCount = tokens.get(idx);
            if (maybeCount.matches("-?\\d+")) {
                count = Integer.parseInt(maybeCount);
                if (count < 1) {
                    throw new IllegalArgumentException("Item count must be >= 1, got " + count);
                }
                idx++;
            }
        }
        ParsedModifiers mods = parseModifiers(tokens, idx);
        if (idx + mods.tokensConsumed() != tokens.size()) {
            throw new IllegalArgumentException("Unexpected token '" + tokens.get(idx + mods.tokensConsumed()) + "'");
        }
        return new ParsedGetTarget(itemTok.baseName(), itemTok.componentSpec(), count,
                mods.maxEmeralds(), mods.preferredMerchantId());
    }
}
