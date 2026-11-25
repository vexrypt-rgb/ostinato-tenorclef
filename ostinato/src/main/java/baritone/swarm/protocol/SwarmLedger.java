/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.swarm.protocol;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ostinato: Phase 2c part 2. One append-only file per bot for the commands it sent and the non-idempotent commands
 * it started. Each line is forced to disk before the method returns, so a crash after a place, break or transfer
 * still leaves the START line. Past {@link CommandProtocol#LEDGER_CAP} * 2 lines it rewrites, dropping settled rows
 * older than the ledger TTL, via a temp file and an atomic move.
 *
 * <pre>
 * ostinato-swarm-ledger v1
 * 1|ms|START|id|name|hash|argsB64|noteB64
 * 1|ms|END|id|EXECUTION|OBSERVATION|reasonB64
 * 1|ms|SENT|id|target|deadline|leased|bodyB64
 * 1|ms|STATE|id|acked|accepted|gaveUp|fenced|sends|EXECUTION|OBSERVATION|reasonB64
 * 1|ms|FENCED|id
 * </pre>
 */
public final class SwarmLedger {

    public static final String HEADER = "ostinato-swarm-ledger v1";

    private final Path file;
    private final List<String> lines = new ArrayList<>();
    private FileChannel channel;
    private int dataLines;
    private int compactAt = CommandProtocol.LEDGER_CAP * 2;
    private long retainMs = CommandProtocol.DEFAULT_LEDGER_TTL_MS;

    private SwarmLedger(Path file) {
        this.file = file;
    }

    public static SwarmLedger open(Path file) throws IOException {
        SwarmLedger ledger = new SwarmLedger(file);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(file) || Files.size(file) == 0) {
            Files.writeString(file, HEADER + "\n");
        }
        String text = Files.readString(file);
        for (String line : text.split("\\R", -1)) {
            if (!line.isEmpty()) {
                ledger.lines.add(line);
            }
        }
        if (ledger.lines.isEmpty() || !HEADER.equals(ledger.lines.get(0))) {
            throw new IOException("not a swarm ledger: " + file);
        }
        ledger.dataLines = ledger.lines.size() - 1;
        ledger.reopen();
        return ledger;
    }

    public Path file() {
        return file;
    }

    /** Test hook. Production stays at twice the in-memory cap. */
    void setCompactAt(int n) {
        compactAt = Math.max(1, n);
    }

    void setRetainMs(long ms) {
        retainMs = Math.max(0L, ms);
    }

    public Replay replay() {
        return parse(lines);
    }

    public synchronized void start(long atMs, UUID id, String name, String hash, String args, String note)
            throws IOException {
        append(line(atMs, "START", id, name, hash, b64(args), b64(note)), atMs);
    }

    public synchronized void end(long atMs, UUID id, ExecutionState exec, ObservationState obs, String reason)
            throws IOException {
        append(line(atMs, "END", id, exec == null ? "-" : exec.name(), obs == null ? "-" : obs.name(), b64(reason)), atMs);
    }

    public synchronized void sent(long atMs, UUID id, String target, long deadline, boolean leased, String body)
            throws IOException {
        append(line(atMs, "SENT", id, target, Long.toString(deadline), leased ? "1" : "0", b64(body)), atMs);
    }

    public synchronized void state(long atMs, UUID id, boolean acked, boolean accepted, boolean gaveUp, boolean fenced,
                                   int sends, ExecutionState exec, ObservationState obs, String reason) throws IOException {
        append(line(atMs, "STATE", id, bit(acked), bit(accepted), bit(gaveUp), bit(fenced), Integer.toString(sends),
                exec == null ? "-" : exec.name(), obs == null ? "-" : obs.name(), b64(reason)), atMs);
    }

    public synchronized void fenced(long atMs, UUID id) throws IOException {
        append(line(atMs, "FENCED", id), atMs);
    }

    public synchronized void close() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    private void append(String row, long now) throws IOException {
        writeLine(row);
        lines.add(row);
        dataLines++;
        if (dataLines > compactAt) {
            compact(now);
        }
    }

    private void writeLine(String row) throws IOException {
        if (channel == null) {
            reopen();
        }
        ByteBuffer buf = ByteBuffer.wrap((row + "\n").getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        channel.force(true);
    }

    private void compact(long now) throws IOException {
        List<String> kept = render(parse(lines), now, retainMs);
        if (kept.size() >= dataLines) {
            compactAt = dataLines + CommandProtocol.LEDGER_CAP;
            return;
        }
        if (channel != null) {
            channel.close();
            channel = null;
        }
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(text(kept).getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.deleteIfExists(tmp);
                throw new IOException("atomic move unsupported for " + file, e);
            }
            lines.clear();
            lines.add(HEADER);
            lines.addAll(kept);
            dataLines = kept.size();
        } finally {
            reopen();
        }
    }

    private void reopen() throws IOException {
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private static String text(List<String> kept) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        for (String row : kept) {
            sb.append(row).append('\n');
        }
        return sb.toString();
    }

    static Replay parse(List<String> rows) {
        Replay out = new Replay();
        Map<UUID, OpenStart> starts = new LinkedHashMap<>();
        Map<UUID, Closed> ends = new LinkedHashMap<>();
        for (String row : rows) {
            if (row == null || row.isEmpty() || HEADER.equals(row)) {
                continue;
            }
            String[] f = row.split("\\|", -1);
            if (f.length < 4 || !"1".equals(f[0])) {
                continue;
            }
            Long ms = longOrNull(f[1]);
            UUID id = uuidOrNull(f[3]);
            if (ms == null || id == null) {
                continue;
            }
            switch (f[2]) {
                case "START" -> {
                    if (f.length < 8) {
                        break;
                    }
                    starts.put(id, new OpenStart(id, f[4], f[5], unb64(f[6]), unb64(f[7]), ms));
                }
                case "END" -> {
                    if (f.length < 7) {
                        break;
                    }
                    ExecutionState exec = enumOrNull(ExecutionState.class, f[4]);
                    ObservationState obs = enumOrNull(ObservationState.class, f[5]);
                    if (exec == null || obs == null) {
                        break;
                    }
                    ends.put(id, new Closed(id, "", "", ms, exec, obs, unb64(f[6])));
                }
                case "SENT" -> {
                    if (f.length < 8) {
                        break;
                    }
                    Long deadline = longOrNull(f[5]);
                    if (deadline == null) {
                        break;
                    }
                    SentImage image = out.sent.computeIfAbsent(id, k -> new SentImage(id));
                    image.target = f[4];
                    image.deadline = deadline;
                    image.leased = "1".equals(f[6]);
                    image.body = unb64(f[7]);
                    image.atMs = ms;
                    if (image.sends < 1) {
                        image.sends = 1;
                    }
                }
                case "STATE" -> {
                    if (f.length < 12) {
                        break;
                    }
                    SentImage image = out.sent.get(id);
                    if (image == null) {
                        break;
                    }
                    image.acked = "1".equals(f[4]);
                    image.accepted = "1".equals(f[5]);
                    image.gaveUp = "1".equals(f[6]);
                    image.fenced = "1".equals(f[7]) || image.fenced;
                    Integer sends = intOrNull(f[8]);
                    if (sends != null) {
                        image.sends = sends;
                    }
                    image.exec = enumOrNull(ExecutionState.class, f[9]);
                    image.obs = enumOrNull(ObservationState.class, f[10]);
                    image.reason = unb64(f[11]);
                    image.atMs = ms;
                }
                case "FENCED" -> {
                    SentImage image = out.sent.get(id);
                    if (image != null) {
                        image.fenced = true;
                    }
                }
                default -> {
                }
            }
        }
        for (OpenStart start : starts.values()) {
            Closed end = ends.get(start.id);
            if (end != null && end.atMs >= start.atMs) {
                out.closed.put(start.id, new Closed(start.id, start.name, start.hash, end.atMs, end.exec, end.obs, end.reason));
            } else {
                out.open.put(start.id, start);
            }
        }
        return out;
    }

    static List<String> render(Replay replay, long now, long retainMs) {
        List<String> kept = new ArrayList<>();
        for (OpenStart start : replay.open.values()) {
            kept.add(line(start.atMs, "START", start.id, start.name, start.hash, b64(start.args), b64(start.note)));
        }
        for (Closed closed : replay.closed.values()) {
            if (now - closed.atMs >= retainMs) {
                continue;
            }
            kept.add(line(closed.atMs, "END", closed.id, closed.exec.name(), closed.obs.name(), b64(closed.reason)));
            // The hash has to survive with the outcome, or a relaunch cannot tell this id from a different body.
            kept.add(line(closed.atMs, "START", closed.id, closed.name, closed.hash, b64(""), b64("")));
        }
        for (SentImage sent : replay.sent.values()) {
            boolean live = !sent.acked && !sent.gaveUp;
            if (!live && now - sent.atMs >= retainMs) {
                continue;
            }
            kept.add(line(sent.atMs, "SENT", sent.id, sent.target, Long.toString(sent.deadline), sent.leased ? "1" : "0",
                    b64(sent.body)));
            if (sent.acked || sent.gaveUp || sent.fenced || sent.exec != null || sent.obs != null) {
                kept.add(line(sent.atMs, "STATE", sent.id, bit(sent.acked), bit(sent.accepted), bit(sent.gaveUp),
                        bit(sent.fenced), Integer.toString(sent.sends),
                        sent.exec == null ? "-" : sent.exec.name(), sent.obs == null ? "-" : sent.obs.name(),
                        b64(sent.reason)));
            }
            if (sent.fenced) {
                kept.add(line(sent.atMs, "FENCED", sent.id));
            }
        }
        return kept;
    }

    private static String line(long atMs, String kind, UUID id, String... rest) {
        StringBuilder sb = new StringBuilder();
        sb.append("1|").append(atMs).append('|').append(kind).append('|').append(id);
        for (String part : rest) {
            sb.append('|').append(part == null ? "" : part);
        }
        return sb.toString();
    }

    private static String b64(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String bit(boolean v) {
        return v ? "1" : "0";
    }

    private static Long longOrNull(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID uuidOrNull(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String text) {
        if (text == null || text.isEmpty() || "-".equals(text)) {
            return null;
        }
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static final class Replay {
        public final Map<UUID, OpenStart> open = new LinkedHashMap<>();
        public final Map<UUID, Closed> closed = new LinkedHashMap<>();
        public final Map<UUID, SentImage> sent = new LinkedHashMap<>();
    }

    public static final class OpenStart {
        public final UUID id;
        public final String name;
        public final String hash;
        public final String args;
        public final String note;
        public final long atMs;

        OpenStart(UUID id, String name, String hash, String args, String note, long atMs) {
            this.id = id;
            this.name = name;
            this.hash = hash;
            this.args = args == null ? "" : args;
            this.note = note == null ? "" : note;
            this.atMs = atMs;
        }
    }

    public static final class Closed {
        public final UUID id;
        public final String name;
        public final String hash;
        public final long atMs;
        public final ExecutionState exec;
        public final ObservationState obs;
        public final String reason;

        Closed(UUID id, String name, String hash, long atMs, ExecutionState exec, ObservationState obs, String reason) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.hash = hash == null ? "" : hash;
            this.atMs = atMs;
            this.exec = exec;
            this.obs = obs;
            this.reason = reason == null ? "" : reason;
        }
    }

    public static final class SentImage {
        public final UUID id;
        public String target = "";
        public String body = "";
        public long deadline;
        public boolean leased;
        public boolean acked;
        public boolean accepted;
        public boolean gaveUp;
        public boolean fenced;
        public int sends = 1;
        public ExecutionState exec;
        public ObservationState obs;
        public String reason = "";
        public long atMs;

        SentImage(UUID id) {
            this.id = id;
        }
    }
}
