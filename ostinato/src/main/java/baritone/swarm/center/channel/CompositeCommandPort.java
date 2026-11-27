package baritone.swarm.center.channel;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Ostinato: routes {@link CenterRequest} to either the headless control port or the graphical {@link CenterClient}
 * based on which platform owns the target bot.
 */
public final class CompositeCommandPort implements CommandPort {

    private final CommandPort headless;
    private final CommandPort graphical;
    private final Predicate<String> isHeadlessBot;
    private final BooleanSupplier hasGraphicalHost;

    public CompositeCommandPort(
            CommandPort headless,
            CommandPort graphical,
            Predicate<String> isHeadlessBot,
            BooleanSupplier hasGraphicalHost) {
        this.headless = headless;
        this.graphical = graphical;
        this.isHeadlessBot = isHeadlessBot;
        this.hasGraphicalHost = hasGraphicalHost;
    }

    @Override
    public CenterReply send(CenterRequest request) {
        boolean useHeadless = (isHeadlessBot != null && isHeadlessBot.test(request.bot()))
                || (hasGraphicalHost != null && !hasGraphicalHost.getAsBoolean());
        CommandPort chosen = useHeadless ? headless : graphical;
        if (chosen == null) {
            chosen = headless != null ? headless : graphical;
        }
        return chosen.send(request);
    }
}
