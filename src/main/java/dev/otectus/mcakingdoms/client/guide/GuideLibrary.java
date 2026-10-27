package dev.otectus.mcakingdoms.client.guide;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.guide.KingdomGuide;
import dev.otectus.mcakingdoms.guide.KingdomGuide.Entry;
import net.minecraft.client.Minecraft;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * The Book's topics for the current language: English first, then the selected language's resources replacing topics
 * whole by id (never mixing two versions of one topic). Read once and kept until resources reload or the language
 * changes. Each resource is bounded; a broken one is named in the log and every other one still loads.
 */
public final class GuideLibrary {
    public record Library(String language, List<Entry> entries, Map<String, Entry> byId, List<String> problems) {
        public Optional<Entry> topic(String id) { return Optional.ofNullable(byId.get(id)); }
    }

    private static Library cached;

    private GuideLibrary() {
    }

    public static synchronized Library get() {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (cached == null || !cached.language().equals(language)) cached = load(language);
        return cached;
    }

    /** Resource packs changed: read the topics again next time. */
    public static synchronized void invalidate() {
        cached = null;
    }

    /** The topic that explains {@code task}: one leading to it directly, else one linking to it; empty when none does. */
    public static Optional<String> topicForTask(String task) {
        var library = get();
        for (Entry entry : library.entries()) if (entry.task().equals(task)) return Optional.of(entry.id());
        for (Entry entry : library.entries())
            for (var link : entry.links()) if (link.kind() == KingdomGuide.Link.Kind.TASK && link.target().equals(task)) return Optional.of(entry.id());
        return Optional.empty();
    }

    private static Library load(String language) {
        var map = new LinkedHashMap<String, Entry>();
        var problems = new ArrayList<String>();
        read("en_us", map, problems);
        if (!language.equals("en_us")) read(language, map, problems);
        var entries = map.values().stream().sorted(Comparator.comparingInt(e -> KingdomGuide.CATEGORIES.indexOf(e.category()))).toList();
        var byId = new LinkedHashMap<String, Entry>();
        entries.forEach(e -> byId.put(e.id(), e));
        return new Library(language, entries, Collections.unmodifiableMap(byId), List.copyOf(problems));
    }

    private static void read(String language, Map<String, Entry> map, List<String> problems) {
        var manager = Minecraft.getInstance().getResourceManager();
        var resources = manager.listResources("guide/" + language, id -> id.getNamespace().equals("mcakingdoms") && id.getPath().endsWith(".json"));
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(64).forEach(resource -> {
            try (var stream = resource.getValue().open();
                 var reader = new java.io.InputStreamReader(new BoundedInputStream(stream), java.nio.charset.StandardCharsets.UTF_8)) {
                var result = KingdomGuide.parse(reader);
                if (map.size() + result.entries().stream().filter(e -> !map.containsKey(e.id())).count() > 512) throw new IOException("Guide entry budget exceeded");
                for (Entry entry : result.entries()) {
                    Entry base = map.get(entry.id());
                    // A translation replaces the topic whole; it never mixes paragraphs or blocks from two versions.
                    if (base != null && base.schema() != entry.schema())
                        LogUtils.getLogger().info("Guide topic {} from {} (schema {}) replaces the schema {} version", entry.id(), resource.getKey(), entry.schema(), base.schema());
                    map.put(entry.id(), entry);
                }
                if (!result.problems().isEmpty()) {
                    problems.add(resource.getKey().toString());
                    result.problems().forEach(problem -> LogUtils.getLogger().warn("Guide resource {}: {}", resource.getKey(), problem));
                }
            } catch (Exception failure) {
                problems.add(resource.getKey().toString());
                LogUtils.getLogger().warn("Could not read guide resource {}", resource.getKey(), failure);
            }
        });
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private int remaining = 1_000_000;
        BoundedInputStream(InputStream in) { super(in); }
        @Override public int read() throws IOException { if (remaining-- <= 0) throw new IOException("Guide resource too large"); return super.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("Guide resource too large");
            int n = in.read(b, off, Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }
}
