"""Ostinato: adapt only command spelling and completion observation for the real client.

The supplied gate owns server setup, anticheat configuration, logging and cleanup.
Its source is read without modifying it or any other worktree. Usage: grim cc2 [steps].
"""
from pathlib import Path
import os

# Ostinato: never inherit another worker's baseline/headless worktree override.
repo = Path(__file__).resolve().parents[1]
assert str(repo).lower() == r"C:\Users\<user>\Documents\MinecraftDev\Ostinato-controls".lower()
os.environ["GATE_REPO"] = str(repo)

gate = Path(r"C:\Users\<user>\Documents\MinecraftDev\headless-bot-logs\phase3\gate\client-gate.py")
source = gate.read_text(encoding="utf-8")
assert source.count('"kill zombie"') == 1
source = source.replace('"kill zombie"', '"pve zombie"')
anchor = '    if only and name not in only: return\n'
assert source.count(anchor) == 1
source = source.replace(anchor, anchor + '''    # Ostinato: server-confirmed fixture state instead of a headless-only stdout regex.
    if CLIENT and name == "mine":
        def poll():
            # Ostinato: same bar as the headless step (one server-confirmed mined block); MineProcess then roams for more stone.
            for x in (-28, -27):
                for y in (-60, -59):
                    result = rcon(f"execute if block {x} {y} 3 minecraft:air")  # Ostinato: rcon replies "Test passed"; a say goes to chat, not the reply
                    if "passed" in result.lower(): return "server confirmed fixture block %d %d 3 is air" % (x, y)
            return None
''')
anchor = '    results.append(res); log("RESULT " + json.dumps(res))\n'
assert source.count(anchor) == 1
source = source.replace(anchor, anchor + '''    if CLIENT and name == "mine": append("stop")
    if CLIENT and name == "kill3": append("pve clear")
''')
# Ostinato: control steps - Baritone idle, plain vanilla KeyMapping holds only (vkeys). A flag here is vanilla-shaped input.
anchor = 'if CLIENT: append("stop"); time.sleep(2)'
assert source.count(anchor) == 1
source = source.replace(anchor, '''step("v_ladder", ["clear HBotAc", "tp HBotAc -90.7 -60 0.5 -90 0"], "vkeys 100 forward", None, None, 8, checks=["data get entity HBotAc Pos"])
step("v_jump", ["tp HBotAc -70.5 -60 10.5 -90 0"], "vkeys 60 jump", None, None, 6, checks=["data get entity HBotAc Pos"])
step("v_jumpwalk", ["tp HBotAc -70.5 -60 14.5 -90 0"], "vkeys 40 forward,jump", None, None, 5, checks=["data get entity HBotAc Pos"])
step("v_place", ["clear HBotAc", "item replace entity HBotAc weapon.mainhand with dirt 16", "tp HBotAc -66.5 -60 20.5 -90 60"], "vkeys 40 use", None, None, 5,
     checks=["execute if block -65 -60 20 minecraft:dirt run say CHECK placed", "clear HBotAc dirt 0"])
step("v_pillar", ["clear HBotAc", "item replace entity HBotAc weapon.mainhand with dirt 16", "tp HBotAc -30.5 -60 -10.5 -90 90"], "vkeys 80 jump,use", None, None, 6,
     checks=["data get entity HBotAc Pos", "clear HBotAc dirt 0"])
''' + anchor)
# Ostinato: the shared course teleports seg_stairs to y=-55 at x=-89.5, i.e. inside bedrock block (-90,-55) (the wall top is
# y=-54, the seg_ladder goal). Vanilla then pushes the player out at 0.1/tick and NCP sets it back until a kick. Stand on top.
assert source.count('"tp HBotAc -89.5 -55 0.5 -90 0"') == 1
source = source.replace('"tp HBotAc -89.5 -55 0.5 -90 0"', '"tp HBotAc -89.5 -54 0.5 -90 0"')
exec(compile(source, str(gate), "exec"), {"__name__": "__main__", "__file__": str(gate)})
