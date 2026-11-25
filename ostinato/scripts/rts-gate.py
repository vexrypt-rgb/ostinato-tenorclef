"""Ostinato: RTS mode live gate on Paper 26.3 + one anticheat, driving the real Fabric client through its command inbox.

Reuses the client audit gate (server setup, anticheat jar swap, flag counting, cleanup) and swaps in RTS steps.
Usage (from this repo's ostinato/ folder): python scripts/rts-gate.py grim rts1 [steps]
Steps: farmer, miner, idle (more as RTS slices land).
"""
from pathlib import Path
import os, re

repo = Path(__file__).resolve().parents[1]
os.environ["GATE_REPO"] = str(repo)
gate = Path(r"C:\Users\redfa\Documents\MinecraftDev\headless-bot-logs\phase3\gate\client-gate.py")
source = gate.read_text(encoding="utf-8")
source = source.replace('OUT = os.path.join(P3, "gate", "client-audit", tag)', 'OUT = os.path.join(P3, "gate", "rts", tag)')
start = source.index('step("seg_ladder"')
end = source.index('if CLIENT: append("stop"); time.sleep(2)')
steps = r'''
def chest_count(x, y, z, item):
    r = rcon("data get block %d %d %d Items" % (x, y, z))
    return sum(int(c) for c in re.findall(r'id: "minecraft:%s"[^}]*?count: (\d+)' % item, r)) + \
        sum(int(c) for c in re.findall(r'count: (\d+)[^}]*?id: "minecraft:%s"' % item, r))
def chest_has(x, y, z, item, n):
    def poll():
        c = chest_count(x, y, z, item)
        return ("chest %d %d %d holds %d %s" % (x, y, z, c, item)) if c >= n else None
    return poll
DEP_FAIL = r"role deposit: (chest did not open|chest closed early|walk to chest timed out|[^\n]*unreachable)[^\n]*"
FARM = ["fill -71 -61 24 -61 -61 34 grass_block", "fill -70 -61 25 -62 -61 33 farmland[moisture=7]",
        "fill -70 -60 25 -62 -60 33 wheat[age=7]", "setblock -66 -60 29 air", "setblock -66 -61 29 water",
        "setblock -60 -60 29 chest[facing=west]", "data merge block -60 -60 29 {Items:[]}", "kill @e[type=item]",
        "clear HBotAc", "give HBotAc bread 8", "tp HBotAc -60.5 -60 31.5 90 0"]
step("farmer", FARM, "role farmer at=-66,-60,29 r=6 chest=-60,-60,29 batch=24", None, DEP_FAIL, 240,
     poll=chest_has(-60, -60, 29, "wheat", 24),
     checks=["data get block -60 -60 29 Items", "execute if block -70 -60 25 minecraft:wheat",
             "data get entity HBotAc Pos"])
append("role status"); append("role idle"); __import__("time").sleep(3)
MINE = ["fill -41 -63 29 -35 -61 35 stone", "fill -40 -61 30 -38 -61 32 coal_ore", "setblock -33 -60 32 chest[facing=west]",
        "data merge block -33 -60 32 {Items:[]}", "kill @e[type=item]", "clear HBotAc", "give HBotAc copper_pickaxe 1",
        "give HBotAc bread 8", "tp HBotAc -33.5 -60 30.5 90 0"]
step("miner", MINE, "role miner ore=coal_ore chest=-33,-60,32 batch=6", None, DEP_FAIL, 240,
     poll=chest_has(-33, -60, 32, "coal", 6), checks=["data get block -33 -60 32 Items", "data get entity HBotAc Pos"])
append("role status"); append("role idle"); __import__("time").sleep(3)
# ---- RTS window: the real command-center window over this client's feed, driven by synthetic Swing events ----
import subprocess as _sp, time as _t
WIN = {}
CENTER = os.path.join(CDIR, "ostinato", "center")
AUTO = os.path.join(OUT, "window-auto.txt"); WLOG = os.path.join(OUT, "window.log")
def window_start():
    if WIN: return
    client_cmd("set swarmCenterFeed true"); client_cmd("set swarmCenterHost true"); _t.sleep(5)
    jar = os.path.join(os.environ["GATE_REPO"], "center-app", "build", "libs", "ostinato-center-app.jar")
    java = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java.exe")
    WIN["p"] = _sp.Popen([java, "-Xmx256m", "-cp", jar, "baritone.swarm.center.app.CenterApp", "--dir", CENTER,
                         "--layout", "map", "--pos", "2600,40", "--log", WLOG, "--auto-file", AUTO])
    log("window pid %d over %s" % (WIN["p"].pid, CENTER)); _t.sleep(6)
def wtext(): return open(WLOG, encoding="utf-8", errors="replace").read() if os.path.exists(WLOG) else ""
def auto(*lines, wait=None, timeout=20):
    with open(AUTO, "a", encoding="utf-8") as f: f.write("\n".join(lines) + "\n")
    log("window< " + " ; ".join(lines))
    if wait:
        t0 = _t.time()
        while _t.time() - t0 < timeout:
            m = re.search(wait, wtext())
            if m: log("window> " + m.group(0)); return m.group(0)
            _t.sleep(1)
        log("window> no " + wait); return None
    _t.sleep(2)
def select_host():
    for _ in range(10):
        if auto("@map|select:HBotAc", wait=r"selected=\[HBotAc\][^\n]*", timeout=4): return True
        _t.sleep(2)
    return False
def act(*lines):
    return lambda: auto(*lines)
def blocks_at(cells, block):
    return sum(1 for (x, y, z) in cells if "passed" in R("execute if block %d %d %d minecraft:%s" % (x, y, z, block)))
def built(cells, block):
    def poll():
        n = blocks_at(cells, block)
        return ("%d/%d %s placed" % (n, len(cells), block)) if n == len(cells) else None
    return poll
if not only or "window" in only:
    window_start()
    log("window sees HBotAc: %s" % select_host())
    auto("@map|rts:on", wait=r"rts=true")
    # roles from the window: pick farmer, fill chest/radius/batch, press Assign to selected
    step("window-farmer", FARM, act("@set|rts:role|farmer", "@set|rts:chest|-60,-60,29", "@set|rts:radius|6",
         "@set|rts:batch|24", "@|rts:assign"), None, DEP_FAIL, 240, poll=chest_has(-60, -60, 29, "wheat", 24),
         checks=["data get block -60 -60 29 Items", "data get entity HBotAc Pos"])
    auto("@|rts:idle", wait=r"reply admitted[^\n]*role idle[^\n]*")
    # right-click move on open ground
    def at_col(x, z):
        def poll():
            m = re.findall(r"(-?[\d.]+)d", rcon("data get entity HBotAc Pos"))
            if len(m) == 3 and int(__import__("math").floor(float(m[0]))) == x and int(__import__("math").floor(float(m[2]))) == z:
                return "client at column %d,%d" % (x, z)
        return poll
    # this world is void past the course: lay a floor for the move and the build (y -61), air above
    YARD = ["fill -59 -61 28 -43 -61 42 grass_block", "fill -59 -60 28 -43 -56 42 air"]
    step("window-move", YARD, act("@map|rts:order:-50,31"), None, None, 90, poll=at_col(-50, 31),
         checks=["data get entity HBotAc Pos"])
    # live blueprint: paint 3x3 cobblestone at y -60, Build live, then paint one more row while it builds
    BP = [(x, -60, z) for x in range(-48, -45) for z in range(36, 39)]
    BP2 = BP + [(x, -60, 39) for x in range(-48, -45)]
    BPFIX = YARD + [ "kill @e[type=item]", "clear HBotAc", "give HBotAc cobblestone 64",
             "give HBotAc bread 8", "tp HBotAc -50.5 -60 37.5 -90 0"]
    def bp_go():
        auto("@set|rts:bp:name|base", "@set|rts:bp:y|-60", "@set|rts:bp:block|cobblestone", "@|rts:bp:clear")
        auto("@map|rts:paint:-48,36,-46,38", wait=r"blueprint base 9 blocks[^\n]*")
        auto("@map|select:HBotAc", "@|rts:bp:live", wait=r"reply admitted[^\n]*blueprint live base[^\n]*")
    step("window-blueprint", BPFIX, bp_go, None, r"blueprint: [^\n]*(failed|error)[^\n]*", 180, poll=built(BP, "cobblestone"),
         checks=["data get entity HBotAc Pos"])
    auto("@map|rts:paint:-48,39,-46,39", wait=r"blueprint base 12 blocks[^\n]*")
    step("window-blueprint-edit", [], None, None, r"blueprint: [^\n]*(failed|error)[^\n]*", 180, poll=built(BP2, "cobblestone"),
         checks=["data get entity HBotAc Pos"])
    st = os.path.join(CENTER, "blueprints", "base.status")
    log("status file: " + (open(st, encoding="utf-8").read().splitlines()[0] if os.path.exists(st) else "missing"))
    append("blueprint stop"); _t.sleep(2)
    WIN["p"].kill(); log("window closed")
'''
source = source[:start] + steps + source[end:]
# Ostinato: a step's command may be a callable (window gestures) instead of a bot line.
assert source.count("    if cmd: append(cmd)\n") == 1
source = source.replace("    if cmd: append(cmd)\n", "    if callable(cmd): cmd()\n    elif cmd: append(cmd)\n")
source = source.replace("if CLIENT and cmd and cmd.startswith(", "if CLIENT and isinstance(cmd, str) and cmd.startswith(")
# Ostinato: never launch an audible client. Force every sound category to 0 after the options fixer, and refuse to start otherwise.
anchor = '    for n in ("command-inbox.txt", "command-inbox.processing"):\n'
assert source.count(anchor) == 1
source = source.replace(anchor, """    _o = os.path.join(CDIR, "options.txt")
    _t = re.sub(r"(soundCategory_\\w+):[0-9.]+", r"\\1:0.0", open(_o, encoding="utf-8-sig").read())
    open(_o, "w", encoding="utf-8").write(_t)
    assert "soundCategory_master:0.0" in _t and not re.search(r"soundCategory_\\w+:(?!0\\.0\\b)", _t), "client not muted"
    log("options: all sound categories 0.0")
""" + anchor)
exec(compile(source, str(gate), "exec"), {"__name__": "__main__", "__file__": str(gate)})
