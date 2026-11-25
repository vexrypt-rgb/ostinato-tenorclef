TenorClef v0.29.0 for Minecraft 26.3 (Fabric, Java 25), built against Ostinato v1.28.0 from the same commit. Put
`tenorclef-mc26.3-v0.29.0.jar` and
[Ostinato v1.28.0](https://github.com/vexrypt-rgb/ostinato-tenorclef/releases/tag/ostinato-v1.28.0)'s
`ostinato-mc26.3-unoptimized-fabric-1.28.0.jar` in `mods/`.

- **Swarm commands** (with Ostinato's `swarmCommandProtocol` on): `@swarm send <bot> <command>`, `@swarm outcome <id>`
  and `@swarm cancel <id>`.
- **`@` chat through the command center registry**, using Ostinato's public `ISwarm.localAt` hook. TenorClef no longer
  reflects into Ostinato internals.
- Works with Ostinato's controls-only input: everything TenorClef asks Ostinato to do goes through vanilla key presses
  and mouse look.

Full notes are in [`tenorclef/CHANGELOG.md`](https://github.com/vexrypt-rgb/ostinato-tenorclef/blob/tenorclef-v0.29.0/tenorclef/CHANGELOG.md).
The 1.21.11, 1.21.4 and 1.16.1 jars are unchanged since 0.28.0 and stay in
[vexrypt-rgb/TenorClef](https://github.com/vexrypt-rgb/TenorClef/releases). TenorClef is MIT; Ostinato is LGPL-3.0.