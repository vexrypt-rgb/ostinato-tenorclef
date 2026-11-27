/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.headless;

import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Localhost-only ({@code 127.0.0.1}) HTTP JSON REST and RFC 6455 WebSocket
 * control endpoint for {@link HeadlessFleetController}.
 */
public final class HeadlessControlServer implements AutoCloseable {

    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final HeadlessFleetController fleet;
    private final ServerSocket serverSocket;
    private final ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;

    public HeadlessControlServer(HeadlessFleetController fleet, int port) throws IOException {
        this.fleet = Objects.requireNonNull(fleet, "fleet");
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        this.serverSocket = new ServerSocket(port, 50, loopback);
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "headless-control-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        acceptThread = new Thread(this::acceptLoop, "headless-control-accept-" + port());
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(30_000);
                workers.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[control] accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket) {
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            HttpRequestHeader req = readHttpHeader(in);
            if (req == null) {
                return;
            }
            String upgrade = req.headers.get("upgrade");
            if (upgrade != null && "websocket".equalsIgnoreCase(upgrade) && "/ws".equals(req.path)) {
                handleWebSocket(s, in, out, req);
                return;
            }
            byte[] body = readHttpBody(in, req.headers);
            HttpResponse resp = routeHttp(req.method, req.path, body);
            writeHttpResponse(out, resp.status, resp.statusText, resp.body);
        } catch (IOException ignored) {
        } catch (RuntimeException e) {
            System.err.println("[control] client handler error: " + e.getMessage());
        }
    }

    private HttpResponse routeHttp(String method, String path, byte[] bodyBytes) {
        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8).trim();
        try {
            if ("GET".equals(method) && "/health".equals(path)) {
                return jsonResponse(200, "OK", fleet.healthJson());
            }
            if ("GET".equals(method) && "/bots".equals(path)) {
                return jsonResponse(200, "OK", fleet.listBotsJson());
            }
            if ("GET".equals(method) && "/center/snapshot".equals(path)) {
                return jsonResponse(200, "OK", fleet.centerSnapshotJson(port()));
            }
            if ("POST".equals(method) && "/center/request".equals(path)) {
                CenterRequest centerReq = parseCenterRequest(bodyStr);
                CenterReply centerReply = fleet.handleCenterRequest(centerReq);
                return jsonResponse(200, "OK", toJson(centerReply));
            }
            if ("GET".equals(method) && "/swarm/status".equals(path)) {
                return jsonResponse(200, "OK", fleet.swarmStatusJson());
            }
            if ("POST".equals(method) && "/swarm/command".equals(path)) {
                JsonObject payload = bodyStr.isEmpty() ? new JsonObject() : JsonParser.parseString(bodyStr).getAsJsonObject();
                return jsonResponse(200, "OK", fleet.dispatchSwarmAction(payload));
            }
            if ("POST".equals(method) && "/bots/spawn".equals(path)) {
                JsonObject payload = JsonParser.parseString(bodyStr).getAsJsonObject();
                HeadlessFleetController.ManagedBot spawned = spawnFromJson(payload);
                return jsonResponse(201, "Created", fleet.botSummaryJson(spawned));
            }
            if ("POST".equals(method) && "/shutdown".equals(path)) {
                fleet.requestShutdown();
                JsonObject res = new JsonObject();
                res.addProperty("shutdownRequested", true);
                return jsonResponse(200, "OK", res);
            }
            if (path.startsWith("/bots/")) {
                String rest = path.substring("/bots/".length());
                if (rest.endsWith("/command") && "POST".equals(method)) {
                    String botId = decodeSegment(rest.substring(0, rest.length() - "/command".length()));
                    String command = extractCommand(bodyStr);
                    return jsonResponse(200, "OK", fleet.dispatchCommand(botId, command));
                }
                if (rest.endsWith("/stop") && "POST".equals(method)) {
                    String botId = decodeSegment(rest.substring(0, rest.length() - "/stop".length()));
                    return stopTargetResponse(botId);
                }
                if (!rest.isEmpty() && rest.indexOf('/') < 0) {
                    String botId = decodeSegment(rest);
                    if ("GET".equals(method)) {
                        return jsonResponse(200, "OK", fleet.botDetailJson(botId));
                    }
                    if ("DELETE".equals(method)) {
                        return stopTargetResponse(botId);
                    }
                }
            }
            return errorResponse(404, "Not Found", "unknown endpoint: " + method + " " + path);
        } catch (NoSuchElementException e) {
            return errorResponse(404, "Not Found", e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return errorResponse(400, "Bad Request", e.getMessage());
        }
    }

    private HttpResponse stopTargetResponse(String botId) {
        JsonObject res = new JsonObject();
        res.addProperty("target", botId);
        if ("all".equalsIgnoreCase(botId)) {
            int stopped = fleet.stopAllBots();
            res.addProperty("stoppedCount", stopped);
            return jsonResponse(200, "OK", res);
        }
        if (!fleet.stopBot(botId)) {
            return errorResponse(404, "Not Found", "unknown bot: " + botId);
        }
        res.addProperty("stopped", true);
        return jsonResponse(200, "OK", res);
    }

    private HeadlessFleetController.ManagedBot spawnFromJson(JsonObject obj) {
        String username = obj.has("username") && !obj.get("username").isJsonNull()
                ? obj.get("username").getAsString()
                : (obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : null);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        UUID uuid = obj.has("uuid") && !obj.get("uuid").isJsonNull()
                ? HeadlessAccount.parseUuid(obj.get("uuid").getAsString())
                : null;
        String token = obj.has("accessToken") && !obj.get("accessToken").isJsonNull()
                ? obj.get("accessToken").getAsString()
                : "";
        String host = obj.has("host") && !obj.get("host").isJsonNull()
                ? obj.get("host").getAsString()
                : null;
        Integer port = obj.has("port") && !obj.get("port").isJsonNull()
                ? obj.get("port").getAsInt()
                : null;
        Path configDir = obj.has("configDir") && !obj.get("configDir").isJsonNull()
                ? Path.of(obj.get("configDir").getAsString())
                : null;
        return fleet.spawnBot(new HeadlessAccount(username, uuid, token), host, port, configDir);
    }

    private static String extractCommand(String bodyStr) {
        if (bodyStr.isEmpty()) {
            throw new IllegalArgumentException("command body must not be empty");
        }
        if (bodyStr.startsWith("{")) {
            JsonObject obj = JsonParser.parseString(bodyStr).getAsJsonObject();
            if (!obj.has("command") || obj.get("command").isJsonNull()) {
                throw new IllegalArgumentException("missing 'command' field");
            }
            return obj.get("command").getAsString();
        }
        return bodyStr;
    }

    private void handleWebSocket(Socket socket, BufferedInputStream in, OutputStream out, HttpRequestHeader req)
            throws IOException {
        String wsKey = req.headers.get("sec-websocket-key");
        if (wsKey == null || wsKey.isBlank()) {
            writeHttpResponse(out, 400, "Bad Request",
                    errorJson("missing Sec-WebSocket-Key").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String accept = computeWsAccept(wsKey.trim());
        String handshake = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(handshake.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        socket.setSoTimeout(0);
        final Object writeLock = new Object();
        ScheduledExecutorService pushExec = null;
        ScheduledFuture<?> pushTask = null;

        try {
            while (running.get() && !socket.isClosed()) {
                WsFrame frame = readWsFrame(in);
                if (frame == null || frame.opcode == 0x8) {
                    synchronized (writeLock) {
                        writeWsFrame(out, 0x8, new byte[0]);
                    }
                    return;
                }
                if (frame.opcode == 0x9) {
                    synchronized (writeLock) {
                        writeWsFrame(out, 0xA, frame.payload);
                    }
                    continue;
                }
                if (frame.opcode != 0x1 && frame.opcode != 0x0) {
                    continue;
                }
                String text = new String(frame.payload, StandardCharsets.UTF_8).trim();
                if (text.startsWith("{")) {
                    JsonObject wsReq = JsonParser.parseString(text).getAsJsonObject();
                    String action = wsReq.has("action") ? wsReq.get("action").getAsString()
                            : (wsReq.has("type") ? wsReq.get("type").getAsString() : "");
                    if ("subscribe".equalsIgnoreCase(action)) {
                        long interval = wsReq.has("intervalMs") ? wsReq.get("intervalMs").getAsLong() : 500L;
                        interval = Math.max(250L, Math.min(5000L, interval));
                        if (pushTask != null) {
                            pushTask.cancel(false);
                        }
                        if (pushExec == null) {
                            pushExec = Executors.newSingleThreadScheduledExecutor(r -> {
                                Thread t = new Thread(r, "headless-ws-push-" + port());
                                t.setDaemon(true);
                                return t;
                            });
                        }
                        JsonObject ack = new JsonObject();
                        ack.addProperty("ok", true);
                        ack.addProperty("action", "subscribe");
                        ack.addProperty("intervalMs", interval);
                        synchronized (writeLock) {
                            writeWsFrame(out, 0x1, ack.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        pushTask = pushExec.scheduleWithFixedDelay(() -> {
                            if (!running.get() || socket.isClosed()) {
                                return;
                            }
                            try {
                                JsonObject msg = new JsonObject();
                                msg.addProperty("ok", true);
                                msg.addProperty("event", "snapshot");
                                msg.add("data", fleet.centerSnapshotJson(port()));
                                synchronized (writeLock) {
                                    writeWsFrame(out, 0x1, msg.toString().getBytes(StandardCharsets.UTF_8));
                                }
                            } catch (IOException | RuntimeException ignored) {
                                try {
                                    socket.close();
                                } catch (IOException ignoredClose) {
                                }
                            }
                        }, 0L, interval, TimeUnit.MILLISECONDS);
                        continue;
                    }
                    if ("unsubscribe".equalsIgnoreCase(action)) {
                        if (pushTask != null) {
                            pushTask.cancel(false);
                            pushTask = null;
                        }
                        JsonObject ack = new JsonObject();
                        ack.addProperty("ok", true);
                        ack.addProperty("action", "unsubscribe");
                        synchronized (writeLock) {
                            writeWsFrame(out, 0x1, ack.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        continue;
                    }
                }
                JsonObject reply = dispatchWsMessage(text);
                synchronized (writeLock) {
                    writeWsFrame(out, 0x1, reply.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        } finally {
            if (pushTask != null) {
                pushTask.cancel(false);
            }
            if (pushExec != null) {
                pushExec.shutdownNow();
            }
        }
    }

    private JsonObject dispatchWsMessage(String text) {
        JsonObject reply = new JsonObject();
        try {
            if (!text.startsWith("{")) {
                reply.addProperty("ok", true);
                reply.addProperty("output", fleet.executeConsoleLine(text));
                return reply;
            }
            JsonObject req = JsonParser.parseString(text).getAsJsonObject();
            String action = req.has("action") ? req.get("action").getAsString()
                    : (req.has("type") ? req.get("type").getAsString() : "");
            reply.addProperty("ok", true);
            reply.addProperty("action", action);
            switch (action.toLowerCase(Locale.ROOT)) {
                case "health" -> reply.add("data", fleet.healthJson());
                case "list" -> reply.add("data", fleet.listBotsJson());
                case "snapshot" -> reply.add("data", fleet.centerSnapshotJson(port()));
                case "center_request" -> {
                    JsonObject payload = req.has("request") && req.get("request").isJsonObject()
                            ? req.getAsJsonObject("request") : req;
                    reply.add("data", toJson(fleet.handleCenterRequest(parseCenterRequest(payload))));
                }
                case "swarm_status" -> reply.add("data", fleet.swarmStatusJson());
                case "swarm_command" -> reply.add("data", fleet.dispatchSwarmAction(req));
                case "status" -> reply.add("data", fleet.botDetailJson(req.get("bot").getAsString()));
                case "command" -> {
                    String bot = req.has("bot") ? req.get("bot").getAsString() : "all";
                    String command = req.get("command").getAsString();
                    reply.add("data", fleet.dispatchCommand(bot, command));
                }
                case "spawn" -> reply.add("data", fleet.botSummaryJson(spawnFromJson(req)));
                case "stop" -> {
                    String bot = req.get("bot").getAsString();
                    if ("all".equalsIgnoreCase(bot)) {
                        JsonObject d = new JsonObject();
                        d.addProperty("stoppedCount", fleet.stopAllBots());
                        reply.add("data", d);
                    } else if (!fleet.stopBot(bot)) {
                        throw new NoSuchElementException("unknown bot: " + bot);
                    } else {
                        JsonObject d = new JsonObject();
                        d.addProperty("stopped", true);
                        d.addProperty("bot", bot);
                        reply.add("data", d);
                    }
                }
                case "shutdown" -> {
                    fleet.requestShutdown();
                    JsonObject d = new JsonObject();
                    d.addProperty("shutdownRequested", true);
                    reply.add("data", d);
                }
                default -> throw new IllegalArgumentException("unsupported action: " + action);
            }
            return reply;
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }

    private static CenterRequest parseCenterRequest(String bodyStr) {
        if (bodyStr == null || bodyStr.isBlank()) {
            throw new IllegalArgumentException("request body must not be empty");
        }
        return parseCenterRequest(JsonParser.parseString(bodyStr).getAsJsonObject());
    }

    private static CenterRequest parseCenterRequest(JsonObject obj) {
        String id = obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsString() : UUID.randomUUID().toString();
        long atMs = obj.has("atMs") && !obj.get("atMs").isJsonNull() ? obj.get("atMs").getAsLong() : System.currentTimeMillis();
        String opRaw = obj.has("op") && !obj.get("op").isJsonNull() ? obj.get("op").getAsString() : "ping";
        CenterRequest.Op op = CenterRequest.Op.valueOf(opRaw.trim().toUpperCase(Locale.ROOT));
        String command = obj.has("command") && !obj.get("command").isJsonNull() ? obj.get("command").getAsString() : "";
        String formId = obj.has("formId") && !obj.get("formId").isJsonNull() ? obj.get("formId").getAsString() : "";
        Map<String, String> values = new HashMap<>();
        if (obj.has("values") && obj.get("values").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("values").entrySet()) {
                if (e.getValue() != null && !e.getValue().isJsonNull()) {
                    values.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        String bot = obj.has("bot") && !obj.get("bot").isJsonNull() ? obj.get("bot").getAsString() : "";
        String line = obj.has("line") && !obj.get("line").isJsonNull() ? obj.get("line").getAsString() : "";
        boolean dryRun = obj.has("dryRun") && !obj.get("dryRun").isJsonNull() && obj.get("dryRun").getAsBoolean();
        return new CenterRequest(id, atMs, op, command, formId, values, bot, line, dryRun);
    }

    private static JsonObject toJson(CenterReply reply) {
        JsonObject obj = new JsonObject();
        obj.addProperty("v", baritone.swarm.center.feed.CenterFeed.VERSION);
        obj.addProperty("id", reply.id());
        obj.addProperty("atMs", reply.atMs());
        obj.addProperty("admitted", reply.admitted());
        obj.addProperty("executed", reply.executed());
        obj.addProperty("reason", reply.reason());
        obj.addProperty("line", reply.line());
        obj.addProperty("host", reply.host());
        return obj;
    }

    private static String computeWsAccept(String wsKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((wsKey + WS_MAGIC).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static WsFrame readWsFrame(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 < 0) {
            return null;
        }
        int b2 = in.read();
        if (b2 < 0) {
            return null;
        }
        int opcode = b1 & 0x0F;
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        if (len == 126) {
            byte[] ext = readExact(in, 2);
            len = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
        } else if (len == 127) {
            byte[] ext = readExact(in, 8);
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | (ext[i] & 0xFFL);
            }
        }
        if (len > MAX_BODY_BYTES) {
            throw new IOException("websocket frame too large: " + len);
        }
        byte[] mask = masked ? readExact(in, 4) : null;
        byte[] payload = readExact(in, (int) len);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        return new WsFrame(opcode, payload);
    }

    private static void writeWsFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | (opcode & 0x0F));
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length <= 65535) {
            out.write(126);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(127);
            long len = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((len >>> shift) & 0xFF));
            }
        }
        out.write(payload);
        out.flush();
    }

    private static byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) {
                throw new EOFException("unexpected end of stream");
            }
            off += r;
        }
        return buf;
    }

    private static HttpRequestHeader readHttpHeader(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (buf.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buf.write(b);
            if (b == '\r') {
                state = (state == 0 || state == 2) ? state + 1 : 1;
            } else if (b == '\n') {
                state = (state == 1 || state == 3) ? state + 1 : 0;
                if (state == 4) {
                    break;
                }
            } else {
                state = 0;
            }
        }
        String raw = buf.toString(StandardCharsets.ISO_8859_1);
        String[] lines = raw.split("\r\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return null;
        }
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            return null;
        }
        String method = requestLine[0].trim().toUpperCase(Locale.ROOT);
        String target = requestLine[1].trim();
        int q = target.indexOf('?');
        String path = q >= 0 ? target.substring(0, q) : target;
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                String k = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String v = lines[i].substring(colon + 1).trim();
                headers.put(k, v);
            }
        }
        return new HttpRequestHeader(method, path, headers);
    }

    private static byte[] readHttpBody(BufferedInputStream in, Map<String, String> headers) throws IOException {
        String cl = headers.get("content-length");
        if (cl == null || cl.isBlank()) {
            return new byte[0];
        }
        int len = Integer.parseInt(cl.trim());
        if (len <= 0) {
            return new byte[0];
        }
        if (len > MAX_BODY_BYTES) {
            throw new IOException("HTTP body too large: " + len);
        }
        return readExact(in, len);
    }

    private static void writeHttpResponse(OutputStream out, int status, String statusText, byte[] body)
            throws IOException {
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static HttpResponse jsonResponse(int status, String text, JsonObject json) {
        return new HttpResponse(status, text, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse errorResponse(int status, String text, String msg) {
        return new HttpResponse(status, text, errorJson(msg).getBytes(StandardCharsets.UTF_8));
    }

    private static String errorJson(String msg) {
        JsonObject err = new JsonObject();
        err.addProperty("error", msg == null ? "unknown error" : msg);
        return err.toString();
    }

    private static String decodeSegment(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        serverSocket.close();
        workers.shutdownNow();
    }

    private record HttpRequestHeader(String method, String path, Map<String, String> headers) {
    }

    private record HttpResponse(int status, String statusText, byte[] body) {
    }

    private record WsFrame(int opcode, byte[] payload) {
    }
}
