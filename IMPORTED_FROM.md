# Imported from

Both trees are snapshots without history. The full history stays in the two source repositories, which remain
the place to read it (`git log`, blame) and to build the older Minecraft lines.

| Directory    | Source repository                                   | Branch                   | Commit      |
|--------------|-----------------------------------------------------|--------------------------|-------------|
| `ostinato/`  | https://github.com/vexrypt-rgb/Ostinato             | `integrate/2026-11-10`   | `05c0ba160` |
| `tenorclef/` | https://github.com/vexrypt-rgb/TenorClef            | `integrate/2026-11-10`   | `1a9688b0`  |

Previous snapshot (2026-11-07): `ostinato/` @ `170d5c70`, `tenorclef/` @ `0f32e2c5`.

Changes made on import:
- Only tracked files are copied (`git archive`), so local run output (logs, `altoclef/` run folders) never comes in.
- Removed `tenorclef/libs/baritone-unoptimized-fabric-ostinato-26.3.jar` and its `.sha256`. Here 26.3 always builds
  against `ostinato/`.
- `.idea/` is not imported.
- The `.github/` folders inside each subdirectory are kept for reference only. GitHub runs only the root
  `.github/workflows/`.

From this import on, new 26.3 work lands here first. The source repositories keep the 1.21.11, 1.21.4 and 1.16.1
lines and the release workflow that builds them.