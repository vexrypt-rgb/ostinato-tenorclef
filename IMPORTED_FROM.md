# Imported from

Both trees are snapshots without history. The full history stays in the two source repositories, which remain
the place to read it (`git log`, blame) and to build the older Minecraft lines.

| Directory    | Source repository                                   | Ref                                   | Commit      |
|--------------|-----------------------------------------------------|---------------------------------------|-------------|
| `ostinato/`  | https://github.com/vexrypt-rgb/Ostinato             | `main` (PR #146), tag `v1.27.0`       | `ffdcad248` |
| `tenorclef/` | https://github.com/vexrypt-rgb/TenorClef            | `main` (PR #44), tag `v0.28.0`        | `58d49f64`  |

Earlier snapshots: 2026-11-10 integration branches (`ostinato/` @ `05c0ba160`, `tenorclef/` @ `1a9688b0`);
2026-11-07 (`ostinato/` @ `170d5c70`, `tenorclef/` @ `0f32e2c5`).

Changes made on import:
- Only tracked files are copied (`git archive`), so local run output (logs, `altoclef/` run folders) never comes in.
- Removed `tenorclef/libs/baritone-unoptimized-fabric-ostinato-26.3.jar` and its `.sha256`. Here 26.3 always builds
  against `ostinato/`.
- `.idea/` is not imported.
- Local Windows user paths in docs, raw logs and scripts read `C:\Users\<user>\...` (text-only replacement).
- `tenorclef/versions/mapping-26.3-1.21.11.txt` (derived from Mojang's names) is not committed. On the first build
  `tenorclef/root.gradle.kts` fetches it from a pinned TenorClef commit and checks its SHA-256; see
  `tenorclef/docs/DEVELOPMENT.md`.
- The `.github/` folders inside each subdirectory are kept for reference only. GitHub runs only the root
  `.github/workflows/`.

From this import on, new 26.3 work lands here first. The source repositories keep the 1.21.11, 1.21.4 and 1.16.1
lines and the release workflow that builds them.
