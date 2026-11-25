package baritone.swarm.accounts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ostinato: Azure application id for device-code sign-in. Personal Microsoft accounts,
 * public client, no secret. {@link #DEFAULT_CLIENT_ID} is the default and is overridable
 * with -Dostinato.azureClientId or azure-client-id.txt in the Ostinato config directory.
 */
public final class AzureClientConfig {

    public static final String DEFAULT_CLIENT_ID = "f941c94f-04b8-41d2-ba08-5a0318811df1";

    public static final String MISSING = "Sign-in needs an Azure app registration (a public client with the device-code "
            + "flow enabled). Put the application id in azure-client-id.txt in the Ostinato config directory, "
            + "or pass -Dostinato.azureClientId.";

    /** Ostinato: Minecraft services reject this app until Mojang approves it. Never include the response body. */
    public static final String AWAITING_MOJANG_APPROVAL = "app ID awaiting Mojang approval";

    private AzureClientConfig() {}

    public static String clientId(Path configDir) {
        String property = System.getProperty("ostinato.azureClientId");
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        if (configDir != null) {
            Path file = configDir.resolve("azure-client-id.txt");
            try {
                if (Files.isRegularFile(file)) {
                    for (String raw : Files.readAllLines(file)) {
                        String line = raw.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        return line;
                    }
                }
            } catch (IOException ignored) {
                // Fall through to the default id.
            }
        }
        return DEFAULT_CLIENT_ID;
    }
}
