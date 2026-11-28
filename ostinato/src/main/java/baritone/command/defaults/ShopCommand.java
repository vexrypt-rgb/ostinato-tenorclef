package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.ArgDecl;
import baritone.api.command.Command;
import baritone.api.command.CommandPermission;
import baritone.api.command.FormDecl;
import baritone.api.command.FormPlacement;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.shop.IShoppingRunner;
import baritone.api.shop.ShoppingRunners;
import baritone.gui.screen.OstinatoScreen;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Ostinato: public {@code #shop} command.
 * <ul>
 *     <li>{@code #shop} / {@code #shop menu} — opens the in-game Shopping List menu</li>
 *     <li>{@code #shop gather <item:count ...>} — gathers every item in the list via the registered {@link IShoppingRunner} (TenorClef)</li>
 *     <li>{@code #shop status} — reports current shopping list progress</li>
 *     <li>{@code #shop stop} — stops active shopping list gathering</li>
 * </ul>
 */
public class ShopCommand extends Command {

    private static final List<String> ACTIONS = Arrays.asList("menu", "gather", "status", "stop");

    public ShopCommand(IBaritone baritone) {
        super(baritone, "shop", "shopping");
    }

    @Override
    public CommandPermission getPermission() {
        return CommandPermission.SWARM;
    }

    @Override
    public List<FormDecl> forms() {
        return List.of(
                FormDecl.of("shop-gather", "Gather shopping list", "shop gather", FormPlacement.TILE,
                        ArgDecl.rest("items", true)),
                FormDecl.of("shop-status", "Shop status", "shop status", FormPlacement.TILE),
                FormDecl.of("shop-stop", "Stop shopping", "shop stop", FormPlacement.TILE)
        );
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.US) : "menu";
        if (action.equals("menu") || action.equals("open") || action.equals("gui")) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                throw new CommandInvalidStateException("#shop menu needs an active Minecraft client");
            }
            mc.execute(() -> mc.gui.setScreen(OstinatoScreen.openShop()));
            logDirect("Opened Shopping List menu");
            return;
        }
        IShoppingRunner runner = ShoppingRunners.get();
        if (runner == null) {
            throw new CommandInvalidStateException("#shop gather needs TenorClef: no shopping runner is registered");
        }
        String result;
        switch (action) {
            case "gather", "buy", "get" -> {
                List<String> rest = new ArrayList<>();
                while (args.hasAny()) {
                    rest.add(args.getString());
                }
                if (rest.isEmpty()) {
                    throw new CommandInvalidTypeException(args.consumed(), "gather <item:count ...>");
                }
                result = runner.gather(String.join(" ", rest));
            }
            case "status" -> result = runner.status();
            case "stop", "cancel" -> result = runner.stop();
            default -> {
                // Allow shorthand: #shop oak_log:32 cobblestone:64
                List<String> rest = new ArrayList<>();
                rest.add(action);
                while (args.hasAny()) {
                    rest.add(args.getString());
                }
                result = runner.gather(String.join(" ", rest));
            }
        }
        if (result != null && !result.isEmpty()) {
            for (String line : result.split("\n")) {
                logDirect(line);
            }
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasExactlyOne()) return Stream.empty();
        String pre = args.peekString().toLowerCase(Locale.US);
        return ACTIONS.stream().filter(s -> s.startsWith(pre));
    }

    @Override
    public String getShortDesc() {
        return "Open the item shopping list menu or gather a shopping list";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Browse every item in the game, build a shopping list with quantities, and gather everything.",
                "",
                "Usage:",
                "> shop - open the in-game Shopping List menu",
                "> shop gather <item:count ...> - gather every item and amount via TenorClef",
                "> shop status - show active shopping list progress",
                "> shop stop - stop active shopping list gathering"
        );
    }
}
