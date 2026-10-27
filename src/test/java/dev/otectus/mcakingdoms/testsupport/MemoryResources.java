package dev.otectus.mcakingdoms.testsupport;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** An in-memory data pack: the shipped {@code src/main/resources/data} tree plus test overrides. */
public final class MemoryResources implements ResourceManager {
    private final Map<ResourceLocation, String> files = new TreeMap<>();

    /** Every shipped data file, keyed like a server resource manager keys them ({@code namespace:path}). */
    public static MemoryResources shipped() {
        MemoryResources resources = new MemoryResources();
        Path data = Path.of("src/main/resources/data");
        try (Stream<Path> walk = Files.walk(data)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path relative = data.relativize(file);
                String namespace = relative.getName(0).toString();
                String path = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
                resources.files.put(new ResourceLocation(namespace, path), Files.readString(file));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return resources;
    }

    /** Adds every file of a data pack folder ({@code <pack>/data/<namespace>/...}), replacing files with the same id. */
    public MemoryResources overlay(Path pack) {
        Path data = pack.resolve("data");
        try (Stream<Path> walk = Files.walk(data)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path relative = data.relativize(file);
                String namespace = relative.getName(0).toString();
                String path = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
                files.put(new ResourceLocation(namespace, path), Files.readString(file));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return this;
    }

    /** The shipped definitions plus the documented example pack under {@code examples/datapack}. */
    public static MemoryResources withExamplePack() {
        return shipped().overlay(ExamplePack.PACK);
    }

    public MemoryResources put(String id, String json) {
        files.put(new ResourceLocation(id), json);
        return this;
    }

    public MemoryResources remove(String id) {
        files.remove(new ResourceLocation(id));
        return this;
    }

    public String text(String id) {
        return files.get(new ResourceLocation(id));
    }

    private static Resource resource(String text) {
        return new Resource((PackResources) null, () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Override public Optional<Resource> getResource(ResourceLocation id) {
        return Optional.ofNullable(files.get(id)).map(MemoryResources::resource);
    }

    @Override public Set<String> getNamespaces() {
        Set<String> namespaces = new TreeSet<>();
        files.keySet().forEach(id -> namespaces.add(id.getNamespace()));
        return namespaces;
    }

    @Override public List<Resource> getResourceStack(ResourceLocation id) {
        return getResource(id).map(List::of).orElse(List.of());
    }

    @Override public Map<ResourceLocation, Resource> listResources(String directory, Predicate<ResourceLocation> filter) {
        Map<ResourceLocation, Resource> found = new TreeMap<>();
        files.forEach((id, text) -> {
            if (id.getPath().startsWith(directory + "/") && filter.test(id)) found.put(id, resource(text));
        });
        return found;
    }

    @Override public Map<ResourceLocation, List<Resource>> listResourceStacks(String directory, Predicate<ResourceLocation> filter) {
        Map<ResourceLocation, List<Resource>> found = new TreeMap<>();
        listResources(directory, filter).forEach((id, resource) -> found.put(id, List.of(resource)));
        return found;
    }

    @Override public Stream<PackResources> listPacks() {
        return Stream.empty();
    }
}
