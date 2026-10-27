package dev.otectus.mcakingdoms.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every {@code /kingdoms help} topic summary is translated in each bundled language, and English matches the code. */
class KingdomsHelpCommandsTest {
    @Test
    void everyTopicSummaryIsTranslated() throws IOException {
        Set<String> problems = new TreeSet<>();
        for (String locale : List.of("en_us", "es_es", "zh_cn")) {
            JsonObject lang = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/mcakingdoms/lang/" + locale + ".json")))
                    .getAsJsonObject();
            KingdomsHelpCommands.topicSummaries().forEach((name, summary) -> {
                String key = "command.mcakingdoms.help.topic." + name;
                if (!lang.has(key)) problems.add(locale + " lacks " + key);
                else if (locale.equals("en_us") && !lang.get(key).getAsString().equals(summary)) problems.add(key + " differs from the code");
            });
        }
        assertTrue(problems.isEmpty(), problems.toString());
    }
}
