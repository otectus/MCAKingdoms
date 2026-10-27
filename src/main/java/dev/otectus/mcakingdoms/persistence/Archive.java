package dev.otectus.mcakingdoms.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Archive files for capacity maintenance (F12): the exact records an operator moved out of a store, as readable JSON in
 * {@code <world>/mcakingdoms_archive/}, outside the data folder Minecraft loads. An archive is written completely and
 * moved into place before its records leave the store, so a failed compaction leaves both copies, never neither.
 */
public final class Archive {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Archive() {
    }

    public static Path directory(Path worldRoot) {
        return worldRoot.resolve("mcakingdoms_archive");
    }

    /** Writes {@code records} for {@code store} and returns the file; throws when it cannot be written durably. */
    public static Path write(Path worldRoot, String store, long gameTime, String reason, JsonElement records) throws IOException {
        Path directory = directory(worldRoot);
        Files.createDirectories(directory);
        Path target = directory.resolve(store + "-" + gameTime + "-" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".json");
        JsonObject root = new JsonObject();
        root.addProperty("store", store);
        root.addProperty("archivedAtGameTime", gameTime);
        root.addProperty("reason", reason);
        root.add("records", records);
        Path temporary = Files.createTempFile(directory, store, ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, JSON.toJson(root), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            return target;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }
}
