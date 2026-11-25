# Ostinato: rtsl harness = copy of p3w (3 headless bots, same roster/SIGIL home) pointed at server-ac-client (Grim, 25582).
$ErrorActionPreference = "Stop"
$S = "C:\Users\<user>\Documents\MinecraftDev\swarm-gate-logs\p3w"
$P = "C:\Users\<user>\Documents\MinecraftDev\swarm-gate-logs\rtsl"
# Ostinato: se-r2. Copy the fabric jar from this worktree (the script lives in ostinato/scripts/rtsl),
# not a hardcoded rts-wt path — Live editor runs need the builder fix in this branch.
$Jar = Join-Path (Split-Path $PSScriptRoot | Split-Path) "fabric\build\libs"
New-Item -ItemType Directory -Force $P | Out-Null
foreach ($d in "inst-template", "sigil-home") { if (Test-Path "$P\$d") { Remove-Item "$P\$d" -Recurse -Force }; Copy-Item "$S\$d" "$P\$d" -Recurse }
foreach ($d in "center", "spool", "run", "inst") { if (Test-Path "$P\$d") { Remove-Item "$P\$d" -Recurse -Force }; New-Item -ItemType Directory -Force "$P\$d" | Out-Null }
$j = Get-ChildItem $Jar -Filter *.jar | Where-Object { $_.Name -notmatch "sources|dev" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item $j.FullName "$P\ost-rtsl.jar" -Force
(Get-Content "$S\runClient.args" -Raw).Replace("$S\ost-p3w.jar", "$P\ost-rtsl.jar") | Set-Content "$P\runClient.args" -NoNewline
(Get-Content "$S\gate.ps1" -Raw).Replace("swarm-gate-logs\p3w", "swarm-gate-logs\rtsl").Replace('"p3w"', '"rtsl"').Replace('p3w\\inst', 'rtsl\\inst') | Set-Content "$P\gate.ps1" -NoNewline
(Get-Content "$S\send.ps1" -Raw).Replace("swarm-gate-logs\p3w", "swarm-gate-logs\rtsl") | Set-Content "$P\send.ps1" -NoNewline
foreach ($n in "Copper", "Azalea", "Spelunker") {
  $t = "$P\inst-template\$n"
  (Get-Content "$t\baritone\settings.txt" -Raw).Replace("swarm-gate-logs\p3w", "swarm-gate-logs\rtsl") | Set-Content "$t\baritone\settings.txt" -NoNewline
  (Get-Content "$t\launch.cfg" -Raw).Replace("127.0.0.1:25571", "127.0.0.1:25582") | Set-Content "$t\launch.cfg" -NoNewline
  $o = Get-Content "$t\options.txt" | ForEach-Object { $k = ($_ -split ":", 2)[0]; if ($k -like "soundCategory_*") { "${k}:0.0" } else { $_ } }
  Set-Content "$t\options.txt" $o
}
"jar $($j.Name) $($j.Length) $($j.LastWriteTime)"
Select-String -Path "$P\runClient.args" -Pattern "ost-rtsl" -SimpleMatch | Measure-Object | ForEach-Object { "args refs $($_.Count)" }
Select-String -Path "$P\gate.ps1","$P\send.ps1","$P\inst-template\*\baritone\settings.txt" -Pattern "p3w" | ForEach-Object { "LEFTOVER $_" }
Select-String -Path "$P\inst-template\*\options.txt" -Pattern "soundCategory_" | Where-Object { $_.Line -notmatch ":0.0$" } | ForEach-Object { "LOUD $_" }
Get-Content "$P\inst-template\Copper\launch.cfg" | Select-String "255"
