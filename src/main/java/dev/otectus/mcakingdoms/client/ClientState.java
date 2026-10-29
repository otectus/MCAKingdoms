package dev.otectus.mcakingdoms.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Local reading state kept on this computer only: the Book's last-read topic, bookmarks and recent topics, and recent and
 * favourite Kingdom Tasks. Only public guide topic ids and task ids are stored, never record contents, so nothing private
 * follows the player from one server to another. Bounded and versioned; an unreadable file is replaced by defaults.
 */
public final class ClientState {
    public static final String FILE_NAME = "mcakingdoms-client-state.json";
    private static final int VERSION = 1, MAX_BOOKMARKS = 32, MAX_RECENT = 8, MAX_FAVORITES = 16, MAX_BYTES = 65_536;
    private static final Pattern TOPIC = Pattern.compile("[a-z0-9_]{1,80}"), TASK = Pattern.compile("[a-z0-9_.]{1,64}");
    private static ClientState instance;

    private final Path file;
    private String lastRead = "start_here";
    private final LinkedHashSet<String> bookmarks = new LinkedHashSet<>();
    private final LinkedList<String> recentTopics = new LinkedList<>();
    private final LinkedList<String> recentTasks = new LinkedList<>();
    private final LinkedHashSet<String> favoriteTasks = new LinkedHashSet<>();
    /** The one pinned task (H10), shown on the HUD; a task id only, never a record. */
    private String pinnedTask;

    ClientState(Path file) {
        this.file = file;
    }

    public static synchronized ClientState get() {
        if (instance == null) {
            instance = new ClientState(FMLPaths.CONFIGDIR.get().resolve(FILE_NAME));
            instance.load();
        }
        return instance;
    }

    void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            if (Files.size(file) > MAX_BYTES) throw new IOException("larger than " + MAX_BYTES + " bytes");
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("version") && json.get("version").getAsInt() > VERSION) throw new IOException("written by a newer version");
            if (json.has("last_read") && TOPIC.matcher(json.get("last_read").getAsString()).matches()) lastRead = json.get("last_read").getAsString();
            read(json, "bookmarks", TOPIC, MAX_BOOKMARKS).forEach(bookmarks::add);
            recentTopics.addAll(read(json, "recent_topics", TOPIC, MAX_RECENT));
            recentTasks.addAll(read(json, "recent_tasks", TASK, MAX_RECENT));
            read(json, "favorite_tasks", TASK, MAX_FAVORITES).forEach(favoriteTasks::add);
            if (json.has("pinned_task") && TASK.matcher(json.get("pinned_task").getAsString()).matches()) pinnedTask = json.get("pinned_task").getAsString();
        } catch (IOException | RuntimeException failure) {
            LogUtils.getLogger().warn("[MCA: Kingdoms] Ignoring unreadable local state {}: {}", file, failure.getMessage());
            lastRead = "start_here";
            bookmarks.clear();
            recentTopics.clear();
            recentTasks.clear();
            favoriteTasks.clear();
            pinnedTask = null;
        }
    }

    private static List<String> read(JsonObject json, String key, Pattern pattern, int limit) {
        if (!(json.get(key) instanceof JsonArray array)) return List.of();
        List<String> out = new ArrayList<>();
        for (var element : array) {
            if (out.size() >= limit) break;
            if (element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (pattern.matcher(value).matches() && !out.contains(value)) out.add(value);
            }
        }
        return out;
    }

    void save() {
        JsonObject json = new JsonObject();
        json.addProperty("version", VERSION);
        json.addProperty("last_read", lastRead);
        json.add("bookmarks", array(bookmarks));
        json.add("recent_topics", array(recentTopics));
        json.add("recent_tasks", array(recentTasks));
        json.add("favorite_tasks", array(favoriteTasks));
        if (pinnedTask != null) json.addProperty("pinned_task", pinnedTask);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            LogUtils.getLogger().warn("[MCA: Kingdoms] Could not save local state {}: {}", file, failure.getMessage());
        }
    }

    private static JsonArray array(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    public String lastRead() {
        return lastRead;
    }

    /** Records a topic as read: it becomes the last-read topic and the newest recent one. */
    public void readTopic(String topic) {
        if (!TOPIC.matcher(topic).matches()) return;
        lastRead = topic;
        recentTopics.remove(topic);
        recentTopics.addFirst(topic);
        while (recentTopics.size() > MAX_RECENT) recentTopics.removeLast();
        save();
    }

    public boolean bookmarked(String topic) {
        return bookmarks.contains(topic);
    }

    /** Adds or removes a bookmark and says which it did. */
    public boolean toggleBookmark(String topic) {
        boolean added;
        if (bookmarks.remove(topic)) added = false;
        else {
            if (bookmarks.size() >= MAX_BOOKMARKS) bookmarks.remove(bookmarks.iterator().next());
            bookmarks.add(topic);
            added = true;
        }
        save();
        return added;
    }

    public List<String> bookmarks() {
        return List.copyOf(bookmarks);
    }

    public List<String> recentTopics() {
        return List.copyOf(recentTopics);
    }

    /**
     * Forgets topics a resource-pack or language change removed, so a stale bookmark does not open nothing. Returns
     * true when anything was removed.
     */
    public boolean pruneTopics(Predicate<String> exists) {
        boolean changed = bookmarks.removeIf(exists.negate()) | recentTopics.removeIf(exists.negate());
        if (!exists.test(lastRead)) { lastRead = "start_here"; changed = true; }
        if (changed) save();
        return changed;
    }

    public void taskOpened(String task) {
        if (!TASK.matcher(task).matches()) return;
        recentTasks.remove(task);
        recentTasks.addFirst(task);
        while (recentTasks.size() > MAX_RECENT) recentTasks.removeLast();
        save();
    }

    public List<String> recentTasks() {
        return List.copyOf(recentTasks);
    }

    public boolean favorite(String task) {
        return favoriteTasks.contains(task);
    }

    public boolean toggleFavorite(String task) {
        boolean added;
        if (favoriteTasks.remove(task)) added = false;
        else {
            if (!TASK.matcher(task).matches()) return false;
            if (favoriteTasks.size() >= MAX_FAVORITES) favoriteTasks.remove(favoriteTasks.iterator().next());
            favoriteTasks.add(task);
            added = true;
        }
        save();
        return added;
    }

    public List<String> favoriteTasks() {
        return List.copyOf(favoriteTasks);
    }

    /** Settings › "Clear bookmarks and history": forgets bookmarks, recent topics and recent and favourite tasks. */
    public Optional<String> pinnedTask() {
        return Optional.ofNullable(pinnedTask);
    }

    /** Pins {@code task}, replacing any other, or unpins it when it is the pinned one; true when it is now pinned. */
    public boolean togglePin(String task) {
        if (task == null || !TASK.matcher(task).matches()) return false;
        pinnedTask = task.equals(pinnedTask) ? null : task;
        save();
        return pinnedTask != null;
    }

    public void unpin() {
        if (pinnedTask == null) return;
        pinnedTask = null;
        save();
    }

    public void clearHistory() {
        bookmarks.clear();
        recentTopics.clear();
        recentTasks.clear();
        favoriteTasks.clear();
        lastRead = "start_here";
        save();
    }
}
