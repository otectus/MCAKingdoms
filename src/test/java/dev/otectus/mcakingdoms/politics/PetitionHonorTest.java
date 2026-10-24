package dev.otectus.mcakingdoms.politics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An approved honor petition grants the honor its kingdom's definition names, so datapack kingdoms get their own. */
class PetitionHonorTest {
    private static final Path DATA = Path.of("src/main/resources/data/mcakingdoms/mcakingdoms");

    @Test
    void theKingdomDefinitionNamesTheHonor() {
        assertEquals("mypack:harbor_light", GovernmentService.petitionHonor(Map.of("petition_honor", "mypack:harbor_light")));
        assertEquals("mcakingdoms:open_bell", GovernmentService.petitionHonor(Map.of()),
                "a kingdom that names none grants the open bell");
        assertEquals("mcakingdoms:open_bell", GovernmentService.petitionHonor(Map.of("petition_honor", "Not An Id")));
    }

    @Test
    void everyShippedKingdomNamesAShippedHonor() throws IOException {
        try (Stream<Path> kingdoms = Files.list(DATA.resolve("kingdoms"))) {
            for (Path file : kingdoms.filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonObject kingdom = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                assertTrue(kingdom.getAsJsonObject("metadata").has("petition_honor"), file + " names no petition honor");
                String honor = kingdom.getAsJsonObject("metadata").get("petition_honor").getAsString();
                assertTrue(honor.startsWith("mcakingdoms:"), honor);
                Path definition = DATA.resolve("honors").resolve(honor.substring("mcakingdoms:".length()) + ".json");
                assertTrue(Files.exists(definition), file.getFileName() + " grants missing honor " + honor);
                assertEquals("honor", JsonParser.parseString(Files.readString(definition)).getAsJsonObject().get("kind").getAsString());
            }
        }
    }

    @Test
    void noPetitionIsShippedThatCannotBeSubmitted() {
        assertFalse(Files.exists(DATA.resolve("petitions/commission_petition.json")),
                "the commission petition had no fulfilment and was refused on every submission");
    }
}
