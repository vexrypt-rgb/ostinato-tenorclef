/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api;

import baritone.api.cache.IWorldScanner;
import baritone.api.command.ICommandSystem;
import baritone.api.context.BotContext;
import baritone.api.context.BotContextScope;
import baritone.api.run.RunMode;
import baritone.api.schematic.ISchematicSystem;
import baritone.api.utils.SettingsUtil;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Exposes the {@link IBaritoneProvider} instance and the {@link Settings} instance for API usage.
 *
 * @author Brady
 * @since 9/23/2018
 */
public final class BaritoneAPI {

    private static final Settings settings;

    static {
        settings = new Settings();
        SettingsUtil.readAndApply(settings, SettingsUtil.SETTINGS_DEFAULT_NAME);
        // Ostinato: -Dostinato.runMode wins the initial mode. #set runMode applies after this.
        RunMode.installStartup(settings);
    }

    // Ostinato: the provider (which creates the primary Baritone from Minecraft.getInstance()) now lives in a lazy holder
    // instead of the static block above, so reading settings no longer needs a running client (headless pathfinding
    // benchmark/tests). It is still created once, on the first getProvider() call.
    private static final class ProviderHolder {
        static final IBaritoneProvider provider;

        static {
            try {
                provider = (IBaritoneProvider) Class.forName("baritone.BaritoneProvider").newInstance();
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public static IBaritoneProvider getProvider() {
        BotContext ctx = BotContextScope.currentOrNull();
        if (ctx != null && ctx.baritone() != null) {
            return new ContextBaritoneProvider(ctx);
        }
        return ProviderHolder.provider;
    }

    public static Settings getSettings() {
        BotContext ctx = BotContextScope.currentOrNull();
        if (ctx != null && ctx.settings() != null) {
            return ctx.settings();
        }
        return BaritoneAPI.settings;
    }

    private static final class ContextBaritoneProvider implements IBaritoneProvider {
        private final BotContext ctx;
        private final List<IBaritone> all;

        ContextBaritoneProvider(BotContext ctx) {
            this.ctx = ctx;
            this.all = List.of(ctx.baritone());
        }

        @Override
        public IBaritone getPrimaryBaritone() {
            return ctx.baritone();
        }

        @Override
        public List<IBaritone> getAllBaritones() {
            return all;
        }

        @Override
        public IBaritone createBaritone(Minecraft minecraft) {
            return ctx.baritone();
        }

        @Override
        public boolean destroyBaritone(IBaritone baritone) {
            return false;
        }

        @Override
        public IWorldScanner getWorldScanner() {
            return ctx.headless() ? null : ProviderHolder.provider.getWorldScanner();
        }

        @Override
        public ICommandSystem getCommandSystem() {
            return ctx.headless() ? null : ProviderHolder.provider.getCommandSystem();
        }

        @Override
        public ISchematicSystem getSchematicSystem() {
            return ctx.headless() ? null : ProviderHolder.provider.getSchematicSystem();
        }
    }
}
