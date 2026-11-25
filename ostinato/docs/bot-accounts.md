# Bot accounts

The command center adds and removes the accounts `#swarm spawn` uses. `launcher_accounts.json` is only an import source. Ostinato does not store passwords, and it does not put access tokens, refresh tokens, or device codes on a child process command line.

## Azure application id

Microsoft sign-in uses the device-code flow against the personal (consumers) authority, with scope `XboxLive.signin` plus `offline_access`. The default application id is a public client Ostinato ships (`AzureClientConfig.DEFAULT_CLIENT_ID`). Override it with one line in `azure-client-id.txt` under the Ostinato config directory (`<game>/baritone`), or with `-Dostinato.azureClientId`.

Mojang has to approve that application id before Minecraft services will accept it. Until then, sign-in reaches Microsoft and Xbox, then stops with "app ID awaiting Mojang approval" instead of a generic failure. Submit the id at https://aka.ms/mce-reviewappid.

If a caller passes a blank application id, the sign-in button explains the registration and does not start the flow.

On Java 25 the Windows DPAPI calls use the foreign-function interface. If the client refuses native access, sign-in stops with "Windows DPAPI is not available" and does not write a token. A flag that allows it, when you need one, is `--enable-native-access=ALL-UNNAMED`.

## What is stored

Tokens are protected before they are written. On Windows that protection is DPAPI (`CryptProtectData`), current user, no prompt. The index file next to the blobs has names and kinds only.

A spawned bot gets `auth.bin` inside its own game directory, with an access list limited to the current user when the file system allows it. The child command line receives `-Dostinato.swarm.authFile` pointing at that file. `Main` splices the token into its in-memory arguments. `launch.cfg` does not receive the token.

Offline names use the same offline-player id Minecraft uses, and they have no token. One chosen account cannot sign in a counted spawn of more than one bot. The child registry name stays the name you passed to `#swarm spawn`. `--username` is the account's Minecraft name when that account has one.

## Sign-in stops

The window shows the user code and the verification URL. It polls once per interval. It does not wait in a loop on the client thread. Closing the command center does not despawn bots and does not cancel a child that is already running.
