package adris.altoclef.compose;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Atomic file store for saved {@link DemoRoutine}s under {@code altoclef/demos/}.
 * Refuses to save empty routines or routines containing unresolved steps.
 */
public final class DemoRoutineStore {

    public static final String EXTENSION = ".demo.properties";

    private final Path dir;

    public DemoRoutineStore(Path dir) {
        this.dir = dir;
    }

    public static DemoRoutineStore standard() {
        return new DemoRoutineStore(Paths.get("altoclef", "demos"));
    }

    public Path dir() {
        return dir;
    }

    public void save(DemoRoutine routine) throws IOException {
        if (routine == null || routine.isEmpty()) {
            throw new IllegalArgumentException("Cannot save an empty routine");
        }
        if (routine.hasUnresolvedSteps()) {
            throw new IllegalStateException("Cannot save routine '" + routine.name()
                    + "' while steps " + routine.unresolvedIndices() + " remain UNRESOLVED");
        }
        Files.createDirectories(dir);
        Properties props = routine.toProperties();
        StringWriter sw = new StringWriter();
        props.store(sw, "TenorClef teach-by-demonstration semantic routine");
        Path target = fileFor(routine.name());
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, sw.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public DemoRoutine load(String rawName) throws IOException {
        String name = DemoRoutine.sanitizeRoutineName(rawName);
        Path target = fileFor(name);
        if (!Files.isRegularFile(target)) {
            throw new IOException("No saved routine named '" + name + "'");
        }
        Properties props = new Properties();
        props.load(new StringReader(Files.readString(target, StandardCharsets.UTF_8)));
        return DemoRoutine.fromProperties(name, props);
    }

    public boolean exists(String rawName) {
        return Files.isRegularFile(fileFor(DemoRoutine.sanitizeRoutineName(rawName)));
    }

    public List<String> list() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(EXTENSION))
                    .map(f -> f.substring(0, f.length() - EXTENSION.length()))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException ignored) {
        }
        return names;
    }

    public boolean delete(String rawName) throws IOException {
        return Files.deleteIfExists(fileFor(DemoRoutine.sanitizeRoutineName(rawName)));
    }

    private Path fileFor(String sanitizedName) {
        return dir.resolve(sanitizedName + EXTENSION);
    }
}
