package baritone.swarm.center.channel;

import java.net.URI;
import java.util.Objects;

/**
 * Ostinato: loopback or host:port address for a {@code HeadlessControlServer}.
 * Pure JDK (no Minecraft/Gson) so {@code center-app} compiles it standalone.
 */
public record HeadlessEndpoint(String host, int port) {

    public HeadlessEndpoint {
        host = (host == null || host.isBlank()) ? "127.0.0.1" : host.trim();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    public static HeadlessEndpoint parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty endpoint");
        }
        String s = raw.trim();
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("ws://") || s.startsWith("wss://")) {
            URI uri = URI.create(s);
            String h = uri.getHost();
            int p = uri.getPort();
            if (p <= 0) {
                throw new IllegalArgumentException("endpoint URI must specify a port: " + raw);
            }
            return new HeadlessEndpoint(h == null ? "127.0.0.1" : h, p);
        }
        int colon = s.lastIndexOf(':');
        if (colon < 0) {
            return new HeadlessEndpoint("127.0.0.1", Integer.parseInt(s));
        }
        String h = s.substring(0, colon).trim();
        String p = s.substring(colon + 1).trim();
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return new HeadlessEndpoint(h.isEmpty() ? "127.0.0.1" : h, Integer.parseInt(p));
    }

    public URI httpUri(String path) {
        String p = (path == null || path.isEmpty()) ? "/" : (path.startsWith("/") ? path : "/" + path);
        return URI.create("http://" + host + ":" + port + p);
    }

    public URI wsUri() {
        return URI.create("ws://" + host + ":" + port + "/ws");
    }

    public String label() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HeadlessEndpoint other && port == other.port && Objects.equals(host, other.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }
}
