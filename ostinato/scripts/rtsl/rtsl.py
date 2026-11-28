# Ostinato: RTS live check under Grim (server-ac-client). Usage: rtsl.py <tag> [mine,build,storage,armor,layers,editor] [W1,W2]
import os, re, sys, time, subprocess, hashlib, uuid as _uuid, glob, json
P = r"C:\Users\<user>\Documents\MinecraftDev\swarm-gate-logs\rtsl"
CG = r"C:\Users\<user>\Documents\MinecraftDev\headless-bot-logs\phase3\gate\client-gate.py"
TAG = sys.argv[1]
PARTS = sys.argv[2].split(",") if len(sys.argv) > 2 else ["mine", "build"]
W = (sys.argv[3] if len(sys.argv) > 3 else "Azalea,Spelunker").split(",")  # Ostinato: the two workers
W1, W2 = W[0], W[1]
BOTS = ["Copper"] + [w for w in W if w != "Copper"]
PROG = r"C:\Users\<user>\Documents\MinecraftDev\headless-bot-logs\phase3\rts-progress.txt"
def prog(m):
    with open(PROG, "a", encoding="utf-8") as f: f.write(time.strftime("%Y-%m-%d %H:%M MST ") + m + "\n")
# server half of client-gate (plugins = grim only, course, rcon helpers); stop before its own bot launch
src = open(CG, encoding="utf-8").read(); src = src[:src.index("BOUT = os.path.join")]
src = src.replace('OUT = os.path.join(P3, "gate", "client-audit", tag)', 'OUT = os.path.join(r"%s", "run", tag)' % P)
sys.argv = ["rtsl", "grim", TAG]
for n in BOTS:
    assert n not in open(r"C:\Users\<user>\Documents\MinecraftDev\headless-bot-logs\phase3\server-ac-client\ops.json").read(), n + " is op"
exec(compile(src, CG, "exec"))
def offline(n):
    h = bytearray(hashlib.md5(("OfflinePlayer:" + n).encode()).digest()); h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
    return str(_uuid.UUID(bytes=bytes(h)))
GATE = os.path.join(P, "gate.ps1")
def ps(*a): return subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", GATE, *a], capture_output=True, text=True, creationflags=NO_WIN).stdout
slog_off = size(SLOG)
outs = {}
try:
    # world: two ore patches with a decoy band between them, a shared chest, a blueprint yard
    for c in ["fill -40 -61 20 -37 -61 23 coal_ore", "fill -36 -61 20 -36 -61 23 coal_ore", "fill -35 -61 20 -32 -61 23 coal_ore",
              "fill -31 -61 20 -31 -61 23 coal_ore", "setblock -36 -60 26 chest", "fill -36 -60 27 -36 -59 27 air",
              "fill -30 -60 30 -24 -58 33 air", "kill @e[type=item]"]:
        R(c)
    # Ostinato: the course recarves a 14-wide void trench at x=-58..-45 every run. RTS bots were not
    # in the HBotAc-only playerdata wipe, so they rejoined at last position (the layers wall, which sat
    # in that trench) and fell before any teleport (r10: Copper void at -47,-58,41, Azalea high-fall).
    # client-gate now wipes every player and setworldspawn; this restates the spawn after our fills.
    R("gamerule respawn_radius 0")  # Ostinato: 26.3 name; spawnRadius is rejected
    R("setworldspawn -65 -60 0")
    for n in BOTS: outs[n] = 0
    for n in BOTS:
        print(ps("-Client", "-Name", n).strip()); time.sleep(4)
    t0 = time.time(); joined = set()
    while len(joined) < len(BOTS) and time.time() - t0 < 300:
        txt, _ = read_from(SLOG, slog_off)
        joined = {n for n in BOTS if re.search(n + r" (joined the game|\[.*\] logged in)", txt)}
        time.sleep(3)
    log("joined " + ",".join(sorted(joined)))
    if len(joined) < len(BOTS): raise SystemExit("not all joined")
    txt, _ = read_from(SLOG, slog_off)
    for n in BOTS:
        m = re.search(re.escape(n) + r"[^\n]*logged in with entity id \d+ at \(\[.*?\]([^)]+)\)", txt)
        log("login %s at %s" % (n, m.group(1).strip() if m else "?"))
    for n in BOTS:
        R("gamemode survival " + n)
        R("tp %s -38 -60 18" % n)
    time.sleep(8)
    for n in BOTS: R("clear " + n); R("effect clear " + n)
    R("tp Copper -65 -60 0"); R("tp %s -38 -60 18" % W1); R("tp %s -33 -60 18" % W2)
    for n in W: R("give %s copper_pickaxe" % n); R("give %s bread 8" % n)
    feeds = os.path.join(P, "center", "bots")
    t0 = time.time()
    while time.time() - t0 < 120:
        fresh = [n for n in BOTS if os.path.exists(os.path.join(feeds, n + ".json")) and time.time() - os.path.getmtime(os.path.join(feeds, n + ".json")) < 5]
        if len(fresh) == len(BOTS): break
        time.sleep(2)
    log("fresh feeds " + ",".join(fresh))
    def send(bot, cmd, wait=8):
        inst = os.path.join(P, "inst", bot); ob = os.path.join(inst, "command-outbox.txt"); b0 = size(ob)
        tmp = os.path.join(inst, "command-inbox.tmp")
        with open(tmp, "w", encoding="utf-8", newline="\n") as f: f.write(cmd + "\n")
        os.replace(tmp, os.path.join(inst, "command-inbox.txt"))
        t = time.time()
        while time.time() - t < wait:
            time.sleep(0.4)
            if size(ob) > b0: break
        r, _ = read_from(ob, b0); log("send %s< %s => %s" % (bot, cmd, r.strip()[:300])); return r
    def clog(bot):
        fs = sorted(glob.glob(os.path.join(P, "run", "client-%s-*.log" % bot)), key=os.path.getmtime)
        return read_from(fs[-1], 0)[0] if fs else ""
    def block(x, y, z, b): return "passed" in rcon("execute if block %d %d %d %s" % (x, y, z, b)).lower()
    send("Copper", "swarm lease status"); time.sleep(2)
    if "mine" in PARTS:
        A = "-40,-61,20:-37,-59,23"; B = "-35,-61,20:-32,-59,23"; OV = "-38,-61,20:-35,-59,23"
        ch = "chest=-36,-60,26"
        send("Copper", "swarm lease %s r:o:%s role miner ore=coal_ore %s batch=4 region=%s" % (W1, A, ch, A))
        time.sleep(3)
        send("Copper", "swarm lease %s r:o:%s role miner ore=coal_ore %s batch=4 region=%s" % (W2, OV, ch, OV))
        time.sleep(3)
        send("Copper", "swarm lease %s r:o:%s role miner ore=coal_ore %s batch=4 region=%s" % (W2, B, ch, B))
        t0 = time.time(); last = ""
        while time.time() - t0 < 300:
            coal = sum(1 for x in list(range(-40, -36)) + list(range(-35, -31)) for z in range(20, 24) if block(x, -61, z, "coal_ore"))
            st = rcon("data get block -36 -60 26 Items")
            s = "coal_ore left %d chest %s" % (coal, re.sub(r"\s+", " ", st)[-160:])
            if s != last: log(s); last = s
            if coal == 0 and time.time() - t0 > 30:
                time.sleep(25); break
            time.sleep(15)  # Ostinato: 33 rcon connections per poll; poll gently so the harness is not the load
        decoy = sum(1 for z in range(20, 24) if block(-31, -61, z, "coal_ore")); dia = sum(1 for z in range(20, 24) if block(-36, -61, z, "coal_ore"))
        log("RESULT mine: decoy band intact %d/4, gap band (x -36, between the regions) intact %d/4" % (decoy, dia))
        log("chest " + rcon("data get block -36 -60 26 Items")[:600])
        send("Copper", "swarm lease status", 4)
        for n in W: send(n, "role idle")
        time.sleep(4)
    if "build" in PARTS:
        bp = os.path.join(P, "center", "blueprints"); os.makedirs(bp, exist_ok=True)
        cells = [(x, y, z) for y in (-60, -59) for x in range(-30, -24) for z in (30, 31)]
        def write_bp(cs, ver):
            o = (-30, -60, 30)
            t = "ost1\n6 2 2\n# blueprint rtsl origin %d %d %d version %d\n" % (o + (ver,)) + "".join("%d %d %d cobblestone\n" % (x - o[0], y - o[1], z - o[2]) for x, y, z in cs)
            tmp = os.path.join(bp, "rtsl.ost.tmp"); open(tmp, "w", newline="\n").write(t); os.replace(tmp, os.path.join(bp, "rtsl.ost"))
        write_bp(cells, 1)
        R("tp %s -29 -60 28" % W1); R("tp %s -26 -60 28" % W2)
        for n in W: R("give %s cobblestone 32" % n)
        time.sleep(3)
        # the window's split for [Azalea, Spelunker] over bounds -30..-25 (x is the longer edge): -30..-28 and -27..-25
        SA = "-30,-60,30:-28,-59,31"; SB = "-27,-60,30:-25,-59,31"
        send("Copper", "swarm lease %s r:o:%s blueprint live rtsl part=%s" % (W1, SA, SA))
        send("Copper", "swarm lease %s r:o:%s blueprint live rtsl part=%s" % (W2, SB, SB))
        erased = None; t0 = time.time(); last = ""
        while time.time() - t0 < 300:
            sts = {}
            for f in glob.glob(os.path.join(bp, "rtsl@*.status")):
                try: sts[os.path.basename(f)] = open(f).read().splitlines()
                except OSError: pass
            placed = {tuple(map(int, l.split())) for v in sts.values() for l in v[1:] if len(l.split()) == 3}
            heads = " | ".join("%s %s" % (k, v[0] if v else "") for k, v in sorted(sts.items()))
            if heads != last: log("status " + heads); last = heads
            if erased is None and placed:
                left = [c for c in cells if c not in placed and c[1] == -59]
                if left:
                    erased = left[-1]; cells = [c for c in cells if c != erased]; write_bp(cells, 2); log("ERASE mid-build %s (placed so far %d)" % (erased, len(placed)))
            if erased and len(sts) == 2 and all(v and v[0].split()[0] == "2" and v[0].split()[1] == v[0].split()[2] for v in sts.values()):
                time.sleep(5); break
            time.sleep(3)
        got = [c for c in cells if block(*c, "cobblestone")]
        dup = []
        log("RESULT build: %d/%d cells cobblestone, erased cell %s is %s" % (len(got), len(cells), erased, "air" if erased and block(*erased, "air") else "NOT air"))
        for n in W:
            lines = [l for l in clog(n).splitlines() if "blueprint" in l.lower() or "lease" in l.lower()]
            log("%s blueprint/lease lines:\n  " % n + "\n  ".join(lines[-25:]))
        for n in W: send(n, "blueprint stop")
    if "storage" in PARTS:
        # three single chests 2 apart (never merged into a double), mixed: homes by category, biggest first
        CH = [(-20, -60, 26), (-17, -60, 26), (-14, -60, 26)]
        fill = [[("coal", 14), ("oak_log", 8), ("wheat", 5)], [("cobblestone", 32), ("coal", 4)], [("oak_log", 12), ("bone", 3)]]
        want = [{"coal": 18, "wheat": 5}, {"cobblestone": 32}, {"oak_log": 20, "bone": 3}]
        for (x, y, z), items in zip(CH, fill):
            R("setblock %d %d %d air" % (x, y, z))
            R("setblock %d %d %d chest{Items:[%s]}" % (x, y, z, ",".join('{Slot:%db,id:"minecraft:%s",count:%d}' % (i, n, c) for i, (n, c) in enumerate(items))))
        R("tp %s -17 -60 23" % W1); R("clear %s" % W1)
        def contents(c):
            # Ostinato: the server cuts a long "data get" reply ("{Slot:..."), so read the list one entry at a time
            m = {}
            for i in range(27):
                e = rcon("data get block %d %d %d Items[%d]" % (c + (i,)))
                a = re.search(r'id: "minecraft:(\w+)"', e); k = re.search(r"count: (\d+)", e)
                if not a: break
                m[a.group(1)] = m.get(a.group(1), 0) + (int(k.group(1)) if k else 1)
            return m
        send(W1, "role storage chests=" + ";".join("%d,%d,%d" % c for c in CH))
        t0 = time.time(); last = ""; ok = False
        while time.time() - t0 < 360:
            got = [contents(c) for c in CH]
            s = "storage " + " | ".join(",".join("%s.%d" % kv for kv in sorted(g.items())) for g in got)
            if s != last: log(s); last = s
            if got == want: ok = True; time.sleep(8); break
            time.sleep(10)
        got = [contents(c) for c in CH]
        log("RESULT storage: %s (want %s) %s" % (got, want, "SORTED" if got == want else "NOT SORTED"))
        lines = [l for l in clog(W1).splitlines() if "role storage" in l]
        log("%s storage lines:\n  " % W1 + "\n  ".join(lines[-30:]))
        send(W1, "role idle")
    if "armor" in PARTS:
        # a crafting table, a supply chest with exactly one copper set's ingots (5+8+7+4) and a deliver chest left empty
        TB = (-23, -60, 14); SUP = (-21, -60, 14); DLV = (-25, -60, 14)
        for c in (TB, SUP, DLV): R("setblock %d %d %d air" % c)
        R("setblock %d %d %d crafting_table" % TB)
        R('setblock %d %d %d chest{Items:[{Slot:0b,id:"minecraft:copper_ingot",count:24}]}' % SUP)
        R("setblock %d %d %d chest" % DLV)
        AB = W[-1]
        R("tp %s -23 -60 11" % AB); R("clear %s" % AB)
        def worn(n):  # one slot per query: long replies are cut by the server
            got = []
            for sl, piece in (("head", "helmet"), ("chest", "chestplate"), ("legs", "leggings"), ("feet", "boots")):
                if "copper_" + piece in rcon("data get entity %s equipment.%s.id" % (n, sl)): got.append("copper_" + piece)
            return got
        send(AB, "role armorer set=copper at=%d,%d,%d chests=%d,%d,%d" % (TB + SUP))
        t0 = time.time(); last = ""
        while time.time() - t0 < 300:
            w = worn(AB); sup = rcon("data get block %d %d %d Items" % SUP)
            st = "armor worn %s supply %s" % (",".join(w), re.sub(r"\s+", " ", sup)[-90:])
            if st != last: log(st); last = st
            if len(w) == 4: time.sleep(5); break
            time.sleep(8)
        w = worn(AB)
        log("RESULT armor: %s wears %d/4 copper pieces %s; supply now %s" % (AB, len(w), w, rcon("data get block %d %d %d Items" % SUP)[-120:]))
        lines = [l for l in clog(AB).splitlines() if "role armorer" in l or "role deposit" in l]
        log("%s armorer lines:\n  " % AB + "\n  ".join(lines[-30:]))
        send(AB, "role idle")
    if "layers" in PARTS:
        # Ostinato: (5) paint a 2-layer wall through the real command-center window (h=2), Y-step + erase one top cell, build live
        import subprocess as _sp
        # Ostinato: keep this yard east of the course void trench (x=-58..-45). r8-r10 built in the trench.
        for c in ["fill -42 -61 38 -30 -61 44 grass_block", "fill -42 -60 38 -30 -56 44 air"]:
            R(c)
        R("tp %s -40 -60 40" % W1); R("tp %s -32 -60 40" % W2)
        time.sleep(2)
        for n in W: R("give %s cobblestone 32" % n)
        jar = r"C:\Users\<user>\Documents\MinecraftDev\rts-wt\ostinato\center-app\build\libs\ostinato-center-app.jar"
        java = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe"
        wdir = os.path.join(P, "run", TAG); os.makedirs(wdir, exist_ok=True)
        wlog, auto_f = os.path.join(wdir, "window.log"), os.path.join(wdir, "window-auto.txt")
        open(auto_f, "w").close()
        win = _sp.Popen([java, "-Xmx256m", "-cp", jar, "baritone.swarm.center.app.CenterApp",
                         "--dir", os.path.join(P, "center"), "--layout", "map", "--pos", "2600,40",
                         "--log", wlog, "--auto-file", auto_f])
        log("window pid %d over %s" % (win.pid, os.path.join(P, "center"))); time.sleep(8)
        def wtext():
            return open(wlog, encoding="utf-8", errors="replace").read() if os.path.exists(wlog) else ""
        def auto(*lines, wait=None, timeout=20):
            with open(auto_f, "a", encoding="utf-8") as f: f.write("\n".join(lines) + "\n")
            log("window< " + " ; ".join(lines))
            if not wait:
                time.sleep(2); return None
            t0 = time.time()
            while time.time() - t0 < timeout:
                m = re.search(wait, wtext())
                if m:
                    log("window> " + m.group(0)); return m.group(0)
                time.sleep(1)
            log("window> no " + wait); return None
        auto("@map|rts:on", wait=r"rts=true")
        cells = [(x, y, z) for y in (-60, -59) for x in range(-40, -36) for z in (40, 41)]  # 4x2 columns x 2 layers = 16
        auto("@set|rts:bp:name|wall", "@set|rts:bp:y|-60", "@set|rts:bp:h|2", "@set|rts:bp:block|cobblestone", "@|rts:bp:clear")
        auto("@map|rts:paint:-40,40,-37,41", wait=r"blueprint wall 16 blocks")
        auto("@set|rts:bp:h|1", "@|rts:bp:up")
        erased = (-37, -59, 41)
        cells = [c for c in cells if c != erased]
        auto("@map|rts:erase:-37,41,-37,41", wait=r"blueprint wall 15 blocks")
        # Ostinato: r9 split left Azalea short 2 upper cells at the strip edge; one bot builds the whole 15 to prove the paint
        auto("@map|select:" + W1, "@|rts:bp:live", wait=r"blueprint live wall")
        t0 = time.time(); last = ""
        while time.time() - t0 < 180:
            got = sum(1 for c in cells if block(*c, "cobblestone"))
            s = "layers placed %d/15 (erased %s air=%s)" % (got, erased, block(*erased, "air"))
            if s != last: log(s); last = s
            if got == 15 and block(*erased, "air"):
                time.sleep(4); break
            time.sleep(4)
        got = [c for c in cells if block(*c, "cobblestone")]
        log("RESULT layers: %d/15 cobblestone from window paint h=2, erased cell %s is %s" % (len(got), erased, "air" if block(*erased, "air") else "NOT air"))
        log("window log tail:\n  " + "\n  ".join(wtext().splitlines()[-40:]))
        for n in W: send(n, "blueprint stop")
        try:
            win.terminate(); win.wait(8)
        except Exception:
            win.kill()
        log("window closed")
    if "editor" in PARTS:
        # Ostinato: 3D editor Live mode. Paint a 2-layer shape in --layout editor, Build live with both
        # workers, erase one cell mid-build, require the rest placed and the erased cell air.
        import subprocess as _sp
        for c in ["fill -42 -61 38 -30 -61 44 grass_block", "fill -42 -60 38 -30 -56 44 air"]:
            R(c)
        R("tp %s -41 -60 40" % W1); R("tp %s -31 -60 40" % W2)
        time.sleep(2)
        for n in W:
            R("give %s cobblestone 32" % n)
        jar = r"C:\Users\<user>\Documents\MinecraftDev\schematic-editor-wt\ostinato\center-app\build\libs\ostinato-center-app.jar"
        java = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe"
        wdir = os.path.join(P, "run", TAG); os.makedirs(wdir, exist_ok=True)
        wlog, auto_f = os.path.join(wdir, "window.log"), os.path.join(wdir, "window-auto.txt")
        open(auto_f, "w").close()
        win = _sp.Popen([java, "-Xmx256m", "-cp", jar, "baritone.swarm.center.app.CenterApp",
                         "--dir", os.path.join(P, "center"), "--layout", "editor", "--pos", "2600,40",
                         "--log", wlog, "--auto-file", auto_f])
        log("editor window pid %d over %s" % (win.pid, os.path.join(P, "center"))); time.sleep(8)
        def wtext():
            return open(wlog, encoding="utf-8", errors="replace").read() if os.path.exists(wlog) else ""
        def auto(*lines, wait=None, timeout=20):
            with open(auto_f, "a", encoding="utf-8") as f: f.write("\n".join(lines) + "\n")
            log("window< " + " ; ".join(lines))
            if not wait:
                time.sleep(2); return None
            t0 = time.time()
            while time.time() - t0 < timeout:
                m = re.search(wait, wtext())
                if m:
                    log("window> " + m.group(0)); return m.group(0)
                time.sleep(1)
            log("window> no " + wait); return None
        cells = [(x, y, z) for y in (-60, -59) for x in range(-40, -37) for z in (42, 43)]  # 3x2 columns x 2 layers = 12
        auto("@set|ed:name|wall", "@set|ed:block|cobblestone", "@set|ed:y|-60", "@set|ed:h|2", "@|ed:clear")
        auto("@map|ed:paint:-40,42,-38,43", wait=r"blueprint wall 12 blocks")
        auto("@|ed:save")
        # selection lives on the map card; switch there, pick both workers, come back to the editor for Build live
        auto("@|header.layout.map")
        auto("@map|select:" + "+".join(W), wait=r"selected=")
        auto("@|header.layout.editor")
        auto("@|ed:live", wait=r"blueprint live")
        erased = (-38, -59, 43)
        t0 = time.time(); last = ""; did_erase = False
        while time.time() - t0 < 240:
            placed = [c for c in cells if block(*c, "cobblestone")]
            if not did_erase and len(placed) >= 1:
                cand = erased if block(*erased, "air") else next((c for c in cells if c not in placed), None)
                if cand is not None:
                    erased = cand
                    cells = [c for c in cells if c != erased]
                    auto("@set|ed:y|%d" % erased[1], "@set|ed:h|1",
                         "@map|ed:erase:%d,%d" % (erased[0], erased[2]),
                         wait=r"blueprint wall %d blocks" % len(cells))
                    auto("@|ed:save")
                    did_erase = True
                    log("mid-build erase %s" % (erased,))
            want = len(cells)
            got_n = sum(1 for c in cells if block(*c, "cobblestone"))
            s = "editor placed %d/%d (erased %s air=%s did_erase=%s)" % (got_n, want, erased, block(*erased, "air"), did_erase)
            if s != last: log(s); last = s
            if did_erase and got_n == want and block(*erased, "air"):
                time.sleep(4); break
            time.sleep(4)
        got = [c for c in cells if block(*c, "cobblestone")]
        log("RESULT editor: %d/%d cobblestone from editor paint h=2 + 2-bot live, erased cell %s is %s, mid-erase %s" % (
            len(got), len(cells), erased, "air" if block(*erased, "air") else "NOT air", "yes" if did_erase else "NO"))
        log("window log tail:\n  " + "\n  ".join(wtext().splitlines()[-50:]))
        for n in W: send(n, "blueprint stop")
        try:
            win.terminate(); win.wait(8)
        except Exception:
            win.kill()
        log("editor window closed")
    for n in BOTS:
        lines = [l for l in clog(n).splitlines() if re.search(r"role|lease|scheduler|chest|blueprint", l, re.I)]
        log("%s role/lease lines (%d):\n  " % (n, len(lines)) + "\n  ".join(lines[-60:]))
finally:
    txt, _ = read_from(SLOG, slog_off)
    flags = [l for l in txt.splitlines() if re.search(r"(Copper|Azalea|Spelunker) failed|Grim.*(Copper|Azalea|Spelunker)", l)]
    deaths = [l for l in txt.splitlines() if re.search(r"(Copper|Azalea|Spelunker) (died|was|fell|drowned|suffocated)", l)]
    log("GRIM FLAGS %d" % len(flags)); [log("  " + l) for l in flags[:40]]
    log("DEATHS %d" % len(deaths)); [log("  " + l) for l in deaths[:10]]
    print(ps("-StopAll").strip()[-400:])
    try: R("stop")
    except Exception as e: log("rcon stop: %s" % e)
    try: srv.wait(60)
    except Exception: srv.kill()
    log("server relay exit %s" % srv.poll())
    prog("rtsl %s bots=%s parts=%s grim flags=%d deaths=%d" % (TAG, ",".join(BOTS), ",".join(PARTS), len(flags), len(deaths)))
