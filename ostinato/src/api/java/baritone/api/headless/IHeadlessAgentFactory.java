/*
 * Ostinato: ServiceLoader factory for attaching an external agent (such as TenorClef) to each headless bot.
 */
package baritone.api.headless;

import baritone.api.context.BotContext;

public interface IHeadlessAgentFactory {

    IHeadlessAgent create(BotContext context, IHeadlessHost host);
}
