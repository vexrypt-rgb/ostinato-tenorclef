package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.ArgDecl;
import baritone.api.command.Command;
import baritone.api.command.CommandPermission;
import baritone.api.command.FormDecl;
import baritone.api.command.FormPlacement;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.headless.ViewCapability;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Ostinato: P4 Bot POV switching command ({@code #pov show|hide|status|return|next}).
 * Drives {@link RenderSwap#requestPresent()} / {@link RenderSwap#releasePresent()} for Hidden-mode bots without
 * modifying the stored {@code runMode}, and advertises {@link ViewCapability#canStreamView} for Headless bots.
 */
public class PovCommand extends Command {

    public PovCommand(IBaritone baritone) {
        super(baritone, "pov");
    }

    @Override
    public CommandPermission getPermission() {
        return CommandPermission.OWNER;
    }

    public static List<ArgDecl> declaredSchema() {
        return List.of(ArgDecl.rest("action", false));
    }

    public static List<FormDecl> windowForms() {
        return List.of(
                FormDecl.of("pov-show", "Watch POV", "pov show", FormPlacement.TILE),
                FormDecl.of("pov-hide", "Return POV", "pov hide", FormPlacement.TILE)
        );
    }

    @Override
    public List<ArgDecl> argumentSchema() {
        return declaredSchema();
    }

    @Override
    public List<FormDecl> forms() {
        return windowForms();
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        RunMode.Value mode = RunMode.current();
        switch (action) {
            case "show", "present", "watch" -> {
                if (ViewCapability.canRenderSwap(mode)) {
                    RenderSwap.requestPresent();
                    logDirect("pov: presenting hidden client window (render-swap active, no focus stolen)");
                } else if (ViewCapability.canStreamView(mode)) {
                    logDirect("pov: headless bot supports stream view only (open in command center)");
                } else {
                    logDirect("pov: client is already visible (" + mode.name().toLowerCase(Locale.ROOT) + ")");
                }
            }
            case "hide", "release", "return", "off" -> {
                RenderSwap.releasePresent();
                logDirect("pov: released presentation (mode=" + mode.name().toLowerCase(Locale.ROOT) + ")");
            }
            case "status" -> {
                logDirect("pov: mode=" + mode.name().toLowerCase(Locale.ROOT)
                        + " presenting=" + RenderSwap.isPresenting()
                        + " canRenderSwap=" + ViewCapability.canRenderSwap(mode)
                        + " canStreamView=" + ViewCapability.canStreamView(mode));
            }
            default -> logDirect("pov: usage: #pov show | hide | return | status");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("show", "hide", "return", "status");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Bot POV switching (render-swap for hidden bots, stream capability status)";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Controls Bot POV switching for Phase 4.",
                "Usage:",
                "> pov show - present this hidden bot window without stealing focus",
                "> pov hide - return this hidden bot to reduced hidden mode",
                "> pov status - report render-swap and stream-view capabilities"
        );
    }
}
