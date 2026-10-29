package dev.otectus.mcakingdoms.politics.dynasty;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** P08: the example pack's succession law and court rank load with the real parsers. */
class ExamplePackDynastyTest {
    private static final Path DATA = Path.of("examples/datapack/mcakingdoms-example/data/example/mcakingdoms");

    @Test void theExampleLawAndRankParse() throws Exception {
        var law = SuccessionLaw.parse(new ResourceLocation("example", "harbor_council"),
                JsonParser.parseString(Files.readString(DATA.resolve("succession_laws/harbor_council.json"))).getAsJsonObject());
        assertEquals(SuccessionLaw.Confirmation.COUNCIL, law.confirmation());
        assertTrue(law.designatedFirst());
        assertTrue(law.relations().contains(Lineage.Relation.RECOGNIZED));
        var rank = NobleRank.parse(new ResourceLocation("example", "harbor_warden"),
                JsonParser.parseString(Files.readString(DATA.resolve("noble_ranks/harbor_warden.json"))).getAsJsonObject());
        assertTrue(rank.landed());
        assertEquals("dynasty.example.rank.harbor_warden.league", rank.titleKey("example:harbor_league"));
        assertEquals("dynasty.example.rank.harbor_warden", rank.titleKey("mcakingdoms:serenum"));
    }
}
