package adris.altoclef.eventbus;

import adris.altoclef.headless.TenorClefBotContext;
import adris.altoclef.util.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * A static class to solve dependency issues. Lets us send and receive events globally, decoupling our codebase.
 * <p>
 * Technically `ConfigHelper` does something like this, but here is a more general case.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class EventBus {

    private static final HashMap<Class, List<Subscription>> topics = new HashMap<>();
    private static final List<Pair<Class, Subscription>> toAdd = new ArrayList<>();
    private static boolean lock;

    private static HashMap<Class, List<Subscription>> currentTopics() {
        TenorClefBotContext tc = TenorClefBotContext.currentOrNull();
        return tc != null ? tc.eventTopics : topics;
    }

    private static List<Pair<Class, Subscription>> currentToAdd() {
        TenorClefBotContext tc = TenorClefBotContext.currentOrNull();
        return tc != null ? tc.eventToAdd : toAdd;
    }

    private static boolean isLocked() {
        TenorClefBotContext tc = TenorClefBotContext.currentOrNull();
        return tc != null ? tc.eventLock : lock;
    }

    private static void setLocked(boolean value) {
        TenorClefBotContext tc = TenorClefBotContext.currentOrNull();
        if (tc != null) {
            tc.eventLock = value;
        } else {
            lock = value;
        }
    }

    public static <T> void publish(T event) {
        Class type = event.getClass();
        HashMap<Class, List<Subscription>> activeTopics = currentTopics();
        List<Pair<Class, Subscription>> activeToAdd = currentToAdd();

        // Add all subscriptions we need to add
        for (Pair<Class, Subscription> pending : activeToAdd) {
            subscribeInternal(pending.getLeft(), pending.getRight());
        }
        activeToAdd.clear();

        if (activeTopics.containsKey(type)) {
            List<Subscription> subscribers = activeTopics.get(type);

            // Subscriptions can be deleted while they're called
            List<Subscription> toDelete = new ArrayList<>();

            // Go through our subscription list. We shouldn't modify the list while we're iterating it.
            setLocked(true);
            for (Subscription subRaw : subscribers) {
                Subscription<T> sub;
                try {
                    sub = (Subscription<T>) subRaw;
                    if (sub.shouldDelete()) {
                        toDelete.add(sub);
                    } else {
                        sub.accept(event);
                    }
                } catch (ClassCastException e) {
                    System.err.println("TRIED PUBLISHING MISMAPPED EVENT: " + event);
                    e.printStackTrace();
                }
            }
            // Delete all subscriptions
            setLocked(false);
        }
    }

    private static <T> void subscribeInternal(Class<T> type, Subscription<T> sub) {
        HashMap<Class, List<Subscription>> activeTopics = currentTopics();
        if (!activeTopics.containsKey(type)) {
            activeTopics.put(type, new ArrayList<>());
        }
        activeTopics.get(type).add(sub);
    }

    public static <T> Subscription<T> subscribe(Class<T> type, Consumer<T> consumeEvent) {
        Subscription<T> sub = new Subscription<>(consumeEvent);
        if (isLocked()) {
            currentToAdd().add(new Pair<>(type, sub));
        } else {
            subscribeInternal(type, sub);
        }
        return sub;
    }

    public static <T> void unsubscribe(Subscription<T> subscription) {
        if (subscription != null)
            subscription.delete();
    }
}
