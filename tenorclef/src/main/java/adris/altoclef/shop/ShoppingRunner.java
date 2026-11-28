package adris.altoclef.shop;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.allblocks.AllBlocksPlan;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes multi-item shopping lists requested from Ostinato's in-game Shop menu, Command Center Shop panel,
 * {@code #shop gather ...}, or TenorClef's {@code @shop gather ...}.
 */
public final class ShoppingRunner {

    private static Task currentTask;
    private static List<ShoppingSpec.Entry> currentList = List.of();
    private static volatile Map<String, Item> registryById;

    private ShoppingRunner() {}

    public static String gather(String spec) {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || !AltoClef.inGame()) {
            return "shop: not in a world";
        }
        List<ShoppingSpec.Entry> entries = ShoppingSpec.parse(spec);
        if (entries.isEmpty()) {
            return "shop: shopping list is empty (example: shop gather oak_log:32 diamond:5)";
        }

        List<ItemTarget> targets = new ArrayList<>();
        List<ShoppingSpec.Entry> accepted = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();

        for (ShoppingSpec.Entry entry : entries) {
            ItemTarget target = resolveTarget(entry.id(), entry.count());
            if (target != null) {
                targets.add(target);
                accepted.add(entry);
            } else {
                String why = AllBlocksPlan.exclusion(entry.id());
                unsupported.add(why != null ? entry.id() + " (" + why + ")" : entry.id());
            }
        }

        if (targets.isEmpty()) {
            return "shop: none of the requested items have a gathering path: " + String.join(", ", unsupported);
        }

        Task task = targets.size() == 1
                ? TaskCatalogue.getItemTask(targets.get(0))
                : TaskCatalogue.getSquashedItemTask(targets.toArray(new ItemTarget[0]));
        if (task == null) {
            return "shop: could not construct gathering task for " + ShoppingSpec.format(accepted);
        }

        currentTask = task;
        currentList = List.copyOf(accepted);
        mod.runUserTask(task, () -> {
            if (currentTask == task && task.isFinished()) {
                Debug.logMessage("[shop] Finished gathering shopping list (" + accepted.size() + " item types).");
            }
        });

        int totalItems = accepted.stream().mapToInt(ShoppingSpec.Entry::count).sum();
        StringBuilder msg = new StringBuilder();
        msg.append("shop: gathering ").append(accepted.size())
                .append(accepted.size() == 1 ? " item type" : " item types")
                .append(" (").append(totalItems).append(" total): ")
                .append(ShoppingSpec.format(accepted));
        if (!unsupported.isEmpty()) {
            msg.append(" | skipped unobtainable/uncatalogued: ").append(String.join(", ", unsupported));
        }
        return msg.toString();
    }

    public static String status() {
        AltoClef mod = AltoClef.getInstance();
        if (currentTask == null || currentList.isEmpty()) {
            return "shop: idle (no active shopping list)";
        }
        boolean active = currentTask.isActive() && !currentTask.isFinished();
        String state = currentTask.isFinished() ? "finished" : (active ? "gathering" : "stopped");
        StringBuilder sb = new StringBuilder("shop: ").append(state).append(" [");
        for (int i = 0; i < currentList.size(); i++) {
            ShoppingSpec.Entry e = currentList.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            if (mod != null && AltoClef.inGame()) {
                Item item = lookupItem(e.id());
                int have = item != null ? mod.getItemStorage().getItemCount(item)
                        : mod.getItemStorage().getItemCount(TaskCatalogue.getItemMatches(e.id()));
                sb.append(e.id()).append(' ').append(have).append('/').append(e.count());
            } else {
                sb.append(e.id()).append(':').append(e.count());
            }
        }
        sb.append(']');
        return sb.toString();
    }

    public static String stop() {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null && currentTask != null && currentTask.isActive()) {
            mod.stopTasks();
            currentTask = null;
            return "shop: stopped gathering";
        }
        currentTask = null;
        return "shop: not running";
    }

    public static boolean isGatherable(String itemId) {
        String norm = ShoppingSpec.normalizeId(itemId);
        if (norm.isEmpty()) {
            return false;
        }
        try {
            if (TaskCatalogue.taskExists(norm)) {
                return true;
            }
            Item item = lookupItem(norm);
            return item != null && item != Items.AIR && TaskCatalogue.taskExists(item);
        } catch (Throwable t) {
            return false;
        }
    }

    private static ItemTarget resolveTarget(String id, int count) {
        if (TaskCatalogue.taskExists(id)) {
            return new ItemTarget(id, count);
        }
        Item item = lookupItem(id);
        if (item != null && item != Items.AIR && TaskCatalogue.taskExists(item)) {
            return new ItemTarget(item, count);
        }
        return null;
    }

    private static Item lookupItem(String normId) {
        Map<String, Item> index = registryById;
        if (index == null || index.isEmpty()) {
            Map<String, Item> built = new HashMap<>();
            for (Identifier key : Registries.ITEM.getIds()) {
                Item item = Registries.ITEM.get(key);
                if (item == null || item == Items.AIR) {
                    continue;
                }
                if ("minecraft".equals(key.getNamespace())) {
                    built.put(key.getPath(), item);
                }
                built.put(key.toString(), item);
            }
            registryById = built;
            index = built;
        }
        return index.get(normId);
    }
}
