package adris.altoclef.headless;

//#if MC >= 260000
//$$ import adris.altoclef.eventbus.EventBus;
//$$ import adris.altoclef.util.helpers.ConfigHelper;
//$$ import baritone.api.Settings;
//$$ import baritone.api.context.BotContext;
//$$ import baritone.api.context.BotContextScope;
//$$ import baritone.api.policy.IAgentPolicy;
//$$ import org.junit.jupiter.api.BeforeAll;
//$$ import org.junit.jupiter.api.Test;
//$$ import org.junit.jupiter.api.io.TempDir;
//$$
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import java.util.concurrent.atomic.AtomicInteger;
//$$
//$$ import static org.junit.jupiter.api.Assertions.*;
//$$
//$$ public class TenorClefContextIsolationTest {
//$$
//$$     @BeforeAll
//$$     static void bootstrapRegistries() {
//$$         net.minecraft.SharedConstants.tryDetectVersion();
//$$         net.minecraft.server.Bootstrap.bootStrap();
//$$     }
//$$
//$$     @Test
//$$     void eventBusAndConfigHelperAreIsolatedPerBotContext(@TempDir Path tempDir) throws Exception {
//$$         Path cfgA = tempDir.resolve("botA");
//$$         Path cfgB = tempDir.resolve("botB");
//$$         BotContext ctxA = new BotContext("botA", cfgA, true, new Settings(), (IAgentPolicy) null);
//$$         BotContext ctxB = new BotContext("botB", cfgB, true, new Settings(), (IAgentPolicy) null);
//$$
//$$         AtomicInteger countA = new AtomicInteger();
//$$         AtomicInteger countB = new AtomicInteger();
//$$
//$$         BotContextScope.runWith(ctxA, () -> {
//$$             EventBus.subscribe(String.class, s -> countA.incrementAndGet());
//$$             ConfigHelper.ensureCommentedListFileExists("test_list.txt", "botA list");
//$$         });
//$$
//$$         BotContextScope.runWith(ctxB, () -> {
//$$             EventBus.subscribe(String.class, s -> countB.incrementAndGet());
//$$             ConfigHelper.ensureCommentedListFileExists("test_list.txt", "botB list");
//$$         });
//$$
//$$         BotContextScope.runWith(ctxA, () -> EventBus.publish("onlyA"));
//$$         assertEquals(1, countA.get());
//$$         assertEquals(0, countB.get());
//$$
//$$         BotContextScope.runWith(ctxB, () -> EventBus.publish("onlyB"));
//$$         assertEquals(1, countA.get());
//$$         assertEquals(1, countB.get());
//$$
//$$         assertTrue(Files.exists(cfgA.resolve("test_list.txt")));
//$$         assertTrue(Files.exists(cfgB.resolve("test_list.txt")));
//$$         assertTrue(Files.readString(cfgA.resolve("test_list.txt")).contains("botA list"));
//$$         assertTrue(Files.readString(cfgB.resolve("test_list.txt")).contains("botB list"));
//$$
//$$         assertNotSame(
//$$                 BotContextScope.callWith(ctxA, TenorClefBotContext::currentOrNull),
//$$                 BotContextScope.callWith(ctxB, TenorClefBotContext::currentOrNull));
//$$     }
//$$ }
//#else
public class TenorClefContextIsolationTest {
}
//#endif
