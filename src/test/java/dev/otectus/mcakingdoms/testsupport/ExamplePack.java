package dev.otectus.mcakingdoms.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The example data pack and its deliberately invalid variants. Each variant folder holds the files that replace the
 * example's, and {@code expected.txt}: the loader domain on the first line, then text the diagnostic must contain.
 */
public final class ExamplePack {
    public static final Path PACK = Path.of("examples/datapack/mcakingdoms-example");
    public static final Path INVALID = Path.of("examples/datapack/invalid");

    public record Case(String name, Path folder, List<String> expected) {
        /** The example pack with this case's files replacing the valid ones. */
        public MemoryResources resources() { return MemoryResources.withExamplePack().overlay(folder); }
    }

    /** The invalid cases for one loader domain: core, politics or world_context. */
    public static List<Case> invalid(String domain) {
        try (Stream<Path> folders = Files.list(INVALID)) {
            return folders.filter(Files::isDirectory).sorted().map(folder -> {
                try {
                    List<String> lines = Files.readAllLines(folder.resolve("expected.txt")).stream().filter(line -> !line.isBlank()).toList();
                    return new Case(folder.getFileName().toString(), folder, lines.subList(1, lines.size()));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }).filter(found -> domainOf(found.folder()).equals(domain)).toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String domainOf(Path folder) {
        try {
            return Files.readAllLines(folder.resolve("expected.txt")).get(0).strip();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private ExamplePack() {
    }
}
