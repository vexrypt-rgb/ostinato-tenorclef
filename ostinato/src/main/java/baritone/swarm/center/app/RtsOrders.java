package baritone.swarm.center.app;

import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.rts.Role;
import baritone.swarm.rts.RoleItems;
import baritone.swarm.rts.RoleOrder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: RTS mode, the window's order lines. Pure: the host runs a line for itself as is and sends it to any other
 * bot as {@code swarm send <bot> <line>} (local channel first), the same routing the tile goto uses.
 */
public final class RtsOrders {

    private RtsOrders() {}

    /** One {@code line, bot} pair per selected bot. */
    public record Order(String bot, String line) {}

    public static String route(String bot, String host, String line) {
        if (bot == null || bot.isBlank() || bot.equalsIgnoreCase(host)) {
            return line;
        }
        return "swarm send " + bot + " " + line;
    }

    public static List<Order> role(List<String> bots, String host, RoleOrder order) {
        List<Order> out = new ArrayList<>();
        for (String b : bots) {
            out.add(new Order(b, route(b, host, order.line())));
        }
        return out;
    }

    /** Right-click move: every selected bot to the clicked column, at its own height (goto x z). */
    public static List<Order> move(List<String> bots, String host, int x, int z) {
        List<Order> out = new ArrayList<>();
        for (String b : bots) {
            out.add(new Order(b, route(b, host, "goto " + x + " " + z)));
        }
        return out;
    }

    public static Order blueprint(String bot, String host, String name) {
        return new Order(bot, route(bot, host, "blueprint live " + name));
    }

    /** The economy HUD line: bots per role, then crops, ores, food and free slots summed over every inventory feed. */
    public static String economy(SwarmView view) {
        Map<Role, Integer> roles = new EnumMap<>(Role.class);
        int crops = 0, ores = 0, food = 0, free = 0, depositing = 0, waiting = 0, known = 0;
        for (SwarmView.BotState b : view.bots()) {
            BotFeed f = b.feed();
            Role r = Role.parse(f.role());
            roles.merge(r == null ? Role.IDLE : r, 1, Integer::sum);
            String[] w = f.roleState().split(" ", 3); // "<role> <phase> ..."
            String phase = w.length > 1 ? w[1] : "";
            if (phase.equals("deposit")) {
                depositing++;
            }
            if (phase.equals("wait")) {
                waiting++;
            }
            BotFeed.Inventory inv = f.inventory();
            if (inv.freeSlots() >= 0) {
                free += inv.freeSlots();
                known++;
            }
            for (BotFeed.Item it : inv.counts()) {
                if (RoleItems.CROPS.contains(it.id())) {
                    crops += it.count();
                } else if (RoleItems.isOre(it.id())) {
                    ores += it.count();
                }
                if (RoleItems.isFood(it.id())) {
                    food += it.count();
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Role r : Role.values()) {
            Integer n = roles.get(r);
            if (n != null) {
                sb.append(r.letter).append(' ').append(r.id).append(' ').append(n).append("  ");
            }
        }
        if (sb.length() == 0) {
            sb.append("no bots  ");
        }
        sb.append("| crops ").append(crops).append(" · ores ").append(ores).append(" · food ").append(food)
                .append(" · free slots ").append(known == 0 ? "n/a" : String.valueOf(free));
        if (depositing > 0 || waiting > 0) {
            sb.append(" | depositing ").append(depositing).append(" · waiting ").append(waiting);
        }
        return sb.toString();
    }
}
