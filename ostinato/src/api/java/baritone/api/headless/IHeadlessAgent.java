/*
 * Ostinato: lifecycle interface for an agent (such as AltoClef) attached to a headless protocol bot.
 */
package baritone.api.headless;

import baritone.api.context.BotContext;

public interface IHeadlessAgent {

    void onAttach(BotContext context, IHeadlessHost host);

    void onTick();

    default void onChat(String sender, String message) {}

    default void onCommand(String line) {}

    default String currentTask() { return "idle"; }

    default void onShutdown() {}
}
