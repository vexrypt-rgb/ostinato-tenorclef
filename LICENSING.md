# Licensing

This repository holds two projects under two licences. Each directory keeps its own licence file, and nothing here
relicenses either project.

| Directory    | Project                          | Licence      | File                                   |
|--------------|----------------------------------|--------------|----------------------------------------|
| `ostinato/`  | Ostinato, a fork of Baritone     | LGPL-3.0 (with Baritone's notices and exception) | `ostinato/LICENSE`, `ostinato/LICENSE-Part-2.jpg` |
| `tenorclef/` | TenorClef, a fork of AltoClef    | MIT          | `tenorclef/LICENSE`, `tenorclef/LICENSING.md` |
| root files (`README.md`, `scripts/`, `.github/`) | build glue | MIT | this file |

TenorClef compiles against the Ostinato jar built from `ostinato/` and only uses `baritone.api.*`. It does not shade
or statically combine Ostinato. The pinned jars in `tenorclef/libs/` (older Minecraft lines) are unmodified Ostinato
builds; their source is the matching branch of https://github.com/vexrypt-rgb/Ostinato, and the LGPL-3.0 terms in
`ostinato/LICENSE` apply to them.

Minecraft is a trademark of Mojang AB. This project is not affiliated with Mojang or Microsoft, and ships no
Minecraft code or assets.