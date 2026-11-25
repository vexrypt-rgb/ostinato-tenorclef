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
'''
source = source[:start] + steps + source[end:]
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
