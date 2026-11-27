package adris.altoclef.headless;

//#if MC >= 260000
//$$ import adris.altoclef.AltoClef;
//$$ import adris.altoclef.Debug;
//$$ import adris.altoclef.tasksystem.Task;
//$$ import baritone.api.context.BotContext;
//$$ import baritone.api.context.BotContextScope;
//$$ import baritone.api.headless.IHeadlessAgent;
//$$ import baritone.api.headless.IHeadlessAgentFactory;
//$$ import baritone.api.headless.IHeadlessHost;
//$$
//$$ import java.util.concurrent.ConcurrentLinkedQueue;
//$$
//$$ /**
//$$  * ServiceLoader entrypoint allowing Ostinato's {@code HeadlessMultiLauncher} to instantiate
//$$  * isolated TenorClef ({@link AltoClef}) agents per headless bot without importing TenorClef.
//$$  */
//$$ public final class TenorClefHeadlessFactory implements IHeadlessAgentFactory {
//$$
//$$     @Override
//$$     public IHeadlessAgent create(BotContext context, IHeadlessHost host) {
//$$         return new TenorClefAgent(context, host);
//$$     }
//$$
//$$     private static final class TenorClefAgent implements IHeadlessAgent {
//$$         private final BotContext context;
//$$         private final IHeadlessHost host;
//$$         private final ConcurrentLinkedQueue<String> pendingCommands = new ConcurrentLinkedQueue<>();
//$$         private AltoClef mod;
//$$
//$$         TenorClefAgent(BotContext context, IHeadlessHost host) {
//$$             this.context = context;
//$$             this.host = host;
//$$         }
//$$
//$$         @Override
//$$         public void onAttach(BotContext ctx, IHeadlessHost h) {
//$$             BotContextScope.runWith(ctx, () -> {
//$$                 TenorClefBotContext tcCtx = ctx.computeAttachmentIfAbsent(
//$$                         TenorClefBotContext.class, TenorClefBotContext::new);
//$$                 tcCtx.setHeadlessHost(h);
//$$                 this.mod = new AltoClef();
//$$                 tcCtx.setMod(this.mod);
//$$                 this.mod.initializeHeadless(ctx, h);
//$$             });
//$$         }
//$$
//$$         @Override
//$$         public void onTick() {
//$$             if (mod != null) {
//$$                 BotContextScope.runWith(context, () -> {
//$$                     String line;
//$$                     while ((line = pendingCommands.poll()) != null) {
//$$                         executeCommandLine(line);
//$$                     }
//$$                     mod.onHeadlessTick();
//$$                 });
//$$             }
//$$         }
//$$
//$$         @Override
//$$         public void onCommand(String line) {
//$$             if (line != null && !line.isBlank()) {
//$$                 pendingCommands.add(line.trim());
//$$             }
//$$         }
//$$
//$$         @Override
//$$         public String currentTask() {
//$$             if (mod == null) {
//$$                 return "uninitialized";
//$$             }
//$$             try {
//$$                 return BotContextScope.callWith(context, () -> {
//$$                     if (mod.getUserTaskChain() != null && mod.getUserTaskChain().isActive()) {
//$$                         Task t = mod.getUserTaskChain().getCurrentTask();
//$$                         if (t != null) {
//$$                             return t.toString();
//$$                         }
//$$                     }
//$$                     if (mod.getTaskRunner() != null && mod.getTaskRunner().isActive()) {
//$$                         return mod.getTaskRunner().statusReport;
//$$                     }
//$$                     return "idle";
//$$                 });
//$$             } catch (Exception e) {
//$$                 return "idle";
//$$             }
//$$         }
//$$
//$$         private void executeCommandLine(String trimmed) {
//$$             try {
//$$                 if (AltoClef.getCommandExecutor().isClientCommand(trimmed)) {
//$$                     AltoClef.getCommandExecutor().execute(trimmed);
//$$                 } else {
//$$                     AltoClef.getCommandExecutor().executeWithPrefix(trimmed);
//$$                 }
//$$             } catch (Throwable t) {
//$$                 Debug.logWarning("Headless command failed (" + trimmed + "): " + t.getMessage());
//$$             }
//$$         }
//$$
//$$         @Override
//$$         public void onShutdown() {
//$$             if (mod != null) {
//$$                 BotContextScope.runWith(context, mod::stopTasks);
//$$             }
//$$         }
//$$     }
//$$ }
//#else
public final class TenorClefHeadlessFactory {
}
//#endif
