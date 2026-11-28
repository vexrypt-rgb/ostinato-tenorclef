package adris.altoclef.ui;

import adris.altoclef.Debug;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.util.time.BaseTimer;
import adris.altoclef.util.time.TimerReal;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * We can't send messages immediately as the server will kick us.
 * As such, we will send messages in a delayed queued fashion.
 */
public class MessageSender {

    // How many messages can we send quickly before giving a little pause?
    private static final int FAST_LIMIT = 6;
    private static final int SLOW_LIMIT = 3;

    private final PriorityQueue<BaseMessage> whisperQueue = new PriorityQueue<>(
            Comparator.comparingInt((BaseMessage msg) -> msg.priority.getImportance()).reversed()
                    .thenComparingInt(msg -> msg.index)
    );

    private final BaseTimer fastSendTimer = new TimerReal(0.3f);
    private final BaseTimer bigSendTimer = new TimerReal(3.5);
    private final BaseTimer bigBigSendTimer = new TimerReal(10);

    private int messageCounter = 0;

    private int fastCount;
    private int slowCount;

    public void tick() {
        if (canSendMessage()) {
            if (!whisperQueue.isEmpty()) {
                BaseMessage msg = whisperQueue.poll();
                assert msg != null;
                sendChatUpdateTimers(msg);
            }
        }
    }

    public void enqueueWhisper(String username, String message, MessagePriority priority) {
        whisperQueue.add(new Whisper(username, message, priority, messageCounter++));
    }

    public void enqueueChat(String message, MessagePriority priority) {
        whisperQueue.add(new ChatMessage(message, priority, messageCounter++));
    }

    private boolean canSendMessage() {
        return bigBigSendTimer.elapsed() && bigSendTimer.elapsed() && fastSendTimer.elapsed();
    }

    private void sendChatUpdateTimers(BaseMessage message) {
        sendChatInstant(message.getChatInput(), message instanceof Whisper);
        fastSendTimer.reset();
        fastCount++;
        if (fastCount >= FAST_LIMIT) {
            bigSendTimer.reset();
            fastCount = 0;
            slowCount++;
            if (slowCount >= SLOW_LIMIT) {
                bigBigSendTimer.reset();
                slowCount = 0;
            }
        }
    }

    private void sendChatInstant(String message, boolean command) {
        if (!BotClientBridge.inGame()) {
            Debug.logError("Failed to send chat message as no client loaded.");
            return;
        }

        if (command) {
            BotClientBridge.sendChatCommand(message);
        } else {
            BotClientBridge.sendChatMessage(message);
        }
    }

    private static abstract class BaseMessage {
        public MessagePriority priority;
        public int index;

        public BaseMessage(MessagePriority priority, int index) {
            this.priority = priority;
            this.index = index;
        }

        public abstract String getChatInput();
    }

    private static class Whisper extends BaseMessage {
        public String username;
        public String message;

        public Whisper(String username, String message, MessagePriority priority, int index) {
            super(priority, index);
            this.username = username;
            this.message = message;
        }

        @Override
        public String getChatInput() {
            String tpl = resolveWhisperTemplate();
            String formatted = tpl.replace("{to}", username).replace("{msg}", message);
            return formatted.startsWith("/") ? formatted.substring(1) : formatted;
        }

        private static String resolveWhisperTemplate() {
            //#if MC >= 260000
            //$$ try {
            //$$     baritone.api.IBaritone primary = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            //$$     if (primary != null && primary.getSwarm() != null) {
            //$$         String tpl = primary.getSwarm().resolvedWhisperTemplate();
            //$$         if (tpl != null && tpl.contains("{to}") && tpl.contains("{msg}")) {
            //$$             return tpl;
            //$$         }
            //$$     }
            //$$ } catch (Throwable ignored) {
            //$$     // Fall back to default msg command when swarm hook is unavailable.
            //$$ }
            //#endif
            return "/msg {to} {msg}";
        }
    }

    private static class ChatMessage extends BaseMessage {

        public String message;

        public ChatMessage(String message, MessagePriority priority, int index) {
            super(priority, index);
            this.message = message;

        }

        @Override
        public String getChatInput() {
            return message;
        }
    }
}
