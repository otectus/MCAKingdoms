package dev.otectus.mcakingdoms.persistence;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** H09: the opt-in progress carry-over moves old-namespace keys once, keeps a backup, and never runs twice. */
class LegacyProgressTest {
    @TempDir Path world;

    @Test
    void movesAdvancementsAndStatisticsOnceWithBackups() throws Exception {
        Files.createDirectories(world.resolve("advancements")); Files.createDirectories(world.resolve("stats")); Files.createDirectories(world.resolve("data"));
        Path advancements = world.resolve("advancements/0000.json");
        Files.writeString(advancements, "{\"ultima_kingdoms:welcome\":{\"criteria\":{\"joined\":\"2026-01-01\"},\"done\":true},"
                + "\"ultima_kingdoms:recipes/village_ledger\":{\"done\":true},\"mcakingdoms:recipes/village_ledger\":{\"done\":false},"
                + "\"minecraft:story/root\":{\"done\":true},\"DataVersion\":3465}");
        Path stats = world.resolve("stats/0000.json");
        Files.writeString(stats, "{\"stats\":{\"minecraft:used\":{\"ultima_kingdoms:book_of_kingdoms\":3,\"mcakingdoms:book_of_kingdoms\":2},"
                + "\"minecraft:crafted\":{\"ultima_kingdoms:village_ledger\":1}},\"DataVersion\":3465}");
        Path untouched = world.resolve("stats/0001.json");
        Files.writeString(untouched, "{\"stats\":{\"minecraft:used\":{\"minecraft:stone\":5}},\"DataVersion\":3465}");

        var result = LegacyProgress.migrate(world);
        assertTrue(result.ran()); assertEquals(2, result.files()); assertEquals(2, result.advancements()); assertEquals(2, result.statistics());
        assertTrue(result.failures().isEmpty());

        var a = JsonParser.parseString(Files.readString(advancements)).getAsJsonObject();
        assertTrue(a.getAsJsonObject("mcakingdoms:welcome").get("done").getAsBoolean(), "an advancement earned under the old name carries over");
        assertFalse(a.getAsJsonObject("mcakingdoms:recipes/village_ledger").get("done").getAsBoolean(), "an entry already under the new name wins");
        assertFalse(a.has("ultima_kingdoms:welcome"));
        assertTrue(a.has("minecraft:story/root"));
        var s = JsonParser.parseString(Files.readString(stats)).getAsJsonObject().getAsJsonObject("stats");
        assertEquals(5, s.getAsJsonObject("minecraft:used").get("mcakingdoms:book_of_kingdoms").getAsInt(), "counts under both names are added");
        assertEquals(1, s.getAsJsonObject("minecraft:crafted").get("mcakingdoms:village_ledger").getAsInt());
        assertTrue(Files.exists(world.resolve("advancements/0000.json.ultima-bak")));
        assertTrue(Files.readString(world.resolve("stats/0000.json.ultima-bak")).contains("ultima_kingdoms:book_of_kingdoms"), "the backup is the original");
        assertFalse(Files.exists(world.resolve("stats/0001.json.ultima-bak")), "files without old keys are not touched");

        String after = Files.readString(stats);
        var again = LegacyProgress.migrate(world);
        assertFalse(again.ran(), "the marker makes the pass run once");
        assertEquals(after, Files.readString(stats));
    }
}
