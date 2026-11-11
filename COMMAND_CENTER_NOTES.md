# Command center notes

The frozen design is `SWARM_DESIGN_DOC_v3.tsx`. These are the conservative readings used for v1.

## One execute

`baritone.swarm.center.UnifiedRegistry.execute` is the entry for local `#` chat, local `@` chat, the command-center window, and AI tool calls.

Local `#` chat (`RegistryCall.Kind.LOCAL_CHAT`) does not read `CommandPermission`. `ICommand` already says chat does not. The call still goes through `execute`, then the existing `CommandManager`. Argument schema, leases, and the in-flight slot are not applied to local chat, because those checks would change today's chat lines. The commands still validate their own arguments inside `execute`.

`@` chat calls the same method by reflection (`UnifiedChat.localAt`). If that class is not on the classpath, TenorClef runs the command as before.

## Permission

| Caller | OWNER | SWARM | AI |
| --- | --- | --- | --- |
| Local chat | runs (tag not read) | runs | runs |
| Window, and the actor is the owner | runs | runs | runs |
| Swarm member | refused | runs | refused |
| AI tool, owner allowed and name on the tool list | refused | runs | runs |
| Public, or not authenticated | refused | refused | refused |

Owner-only commands are refused from swarm, AI, and public callers. The dispatcher is not called. Spawn and despawn live on `#swarm`, which is OWNER, so only the local owner (chat or the window) can launch or stop a child.

An AI caller may run a SWARM command only when `AgentPolicy` already allows that tool. That keeps `#goto` and `#mine` on the agent whitelist. It does not let the agent run `#set` or `#swarm spawn`. `#agent` settings lines that become `set ...` are refused at this gate.

`cancel` / `forcecancel` stay SWARM in the registry, matching `RegistryExecutor`, so a member can cancel a command it sent.

A blank owner name means the window actor is the owner. A set owner name must match the actor.

## Phase 1

Lease-needing commands are refused. A dry run returns `preview` and does not run. A world-mutating command aimed at a group or at all bots is refused the same way (`phase1`). One in-flight mutating command per target. `cancel`, `stop`, and `swarm despawn` are allowed while a target is busy.

## Layout

`#center` and `#center layout wall|map` are registry commands. The file is `command-center-layout-<user>.txt` in the Ostinato config directory (`<game>/baritone`, or the path passed to the store). The default is `wall`. The war-room map is a placeholder in v1.

## Accounts

Launcher `launcher_accounts.json` is an import source. Passwords are not copied. Tokens are stored only through the injected protector (Windows DPAPI in the client). They are not written in the clear, not logged, and not placed on a child process command line.

## Not in v1

Schematic editor, live view, radial menu, Ctrl+K, theme editor, full war-room map, bot POV, Phase 2 leases and coordinator, and any network listener.
