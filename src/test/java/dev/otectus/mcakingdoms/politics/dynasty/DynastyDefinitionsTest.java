package dev.otectus.mcakingdoms.politics.dynasty;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A5: data-pack laws and court ranks are validated field by field; the shipped ranks parse and style every kingdom. */
class DynastyDefinitionsTest {
    private static SuccessionLaw law(String json) {
        return SuccessionLaw.parse(new ResourceLocation("test", "law"), JsonParser.parseString(json).getAsJsonObject());
    }

    @Test void aValidCustomLawParses() {
        var law = law("{\"schema\":1,\"base\":\"designated_then_lineage\",\"title\":\"test.law\",\"confirmation\":\"council\",\"relations\":[\"child\",\"recognized\"],\"minor_regency\":false}");
        assertEquals(SuccessionLaw.Mode.CUSTOM_DATAPACK, law.mode());
        assertTrue(law.designatedFirst());
        assertEquals(SuccessionLaw.Confirmation.COUNCIL, law.confirmation());
        assertEquals(EnumSet.of(Lineage.Relation.CHILD, Lineage.Relation.RECOGNIZED), law.relations());
        assertFalse(law.minorRegency());
    }

    @Test void invalidLawsAreRefusedWithTheirReason() {
        assertThrows(IllegalArgumentException.class, () -> law("{\"schema\":2,\"base\":\"equal_primogeniture\",\"title\":\"t\",\"relations\":[\"child\"]}"));
        assertThrows(IllegalArgumentException.class, () -> law("{\"schema\":1,\"base\":\"random\",\"title\":\"t\",\"relations\":[\"child\"]}"));
        assertThrows(IllegalArgumentException.class, () -> law("{\"schema\":1,\"base\":\"equal_primogeniture\",\"title\":\"t\",\"relations\":[\"designated\"]}"));
        assertThrows(IllegalArgumentException.class, () -> law("{\"schema\":1,\"base\":\"equal_primogeniture\",\"title\":\"t\",\"relations\":[\"recognized\"]}"));
        assertThrows(IllegalArgumentException.class, () -> law("{\"schema\":1,\"base\":\"equal_primogeniture\",\"title\":\"Not A Key\",\"relations\":[\"child\"]}"));
        var unknown = assertThrows(IllegalArgumentException.class, () -> law("{\"schema\":1,\"base\":\"equal_primogeniture\",\"title\":\"t\",\"relations\":[\"child\"],\"command\":\"op @a\"}"));
        assertTrue(unknown.getMessage().contains("unknown field command"));
    }

    @Test void builtInLawsCannotBeReplacedAndManualIsTheDefault() {
        assertTrue(SuccessionLaw.builtIn(SuccessionLaw.MANUAL));
        assertEquals(SuccessionLaw.Mode.MANUAL_ONLY, SuccessionLaw.get(SuccessionLaw.MANUAL).orElseThrow().mode());
        assertEquals(5, SuccessionLaw.all().values().stream().filter(l -> l.mode() != SuccessionLaw.Mode.CUSTOM_DATAPACK).count());
    }

    @Test void shippedRanksParseAndStyleEveryBuiltInKingdom() throws Exception {
        Path dir = Path.of("src/main/resources/data/mcakingdoms/mcakingdoms/noble_ranks");
        var kingdoms = new TreeSet<String>();
        try (var files = Files.list(Path.of("src/main/resources/data/mcakingdoms/mcakingdoms/kingdoms"))) {
            files.forEach(f -> kingdoms.add("mcakingdoms:" + f.getFileName().toString().replace(".json", "")));
        }
        var ranks = new ArrayList<NobleRank>();
        try (var files = Files.list(dir)) {
            for (Path file : files.sorted().toList())
                ranks.add(NobleRank.parse(new ResourceLocation("mcakingdoms", file.getFileName().toString().replace(".json", "")), JsonParser.parseString(Files.readString(file)).getAsJsonObject()));
        }
        assertEquals(4, ranks.size());
        for (var rank : ranks) assertEquals(kingdoms, new TreeSet<>(rank.styles().keySet()), rank.id() + " styles every built-in kingdom");
        var first = ranks.stream().filter(r -> r.id().getPath().equals("first_rank")).findFirst().orElseThrow();
        assertTrue(first.landed());
        assertEquals("dynasty.mcakingdoms.rank.lunari.first_rank", first.titleKey("mcakingdoms:lunari"));
        assertEquals("dynasty.mcakingdoms.rank.first_rank", first.titleKey("other:kingdom"));
    }

    @Test void invalidRanksAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> NobleRank.parse(new ResourceLocation("t", "r"), JsonParser.parseString("{\"schema\":1,\"tier\":0,\"title\":\"a.b\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> NobleRank.parse(new ResourceLocation("t", "r"), JsonParser.parseString("{\"schema\":1,\"tier\":1,\"title\":\"a.b\",\"grants\":[\"op\"]}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> NobleRank.parse(new ResourceLocation("t", "r"), JsonParser.parseString("{\"schema\":1,\"tier\":1,\"title\":\"a.b\",\"styles\":{\"Bad Id\":\"a.b\"}}").getAsJsonObject()));
    }

    @Test void legitimacyIsAReadingOfBoundedFacts() {
        var weights = DynastyConfig.Weights.DEFAULT;
        var strong = Legitimacy.assess(new Legitimacy.Facts(true, true, true, false, 1, 3, 0), weights);
        assertEquals(Legitimacy.Level.STRONG, strong.level());
        assertEquals(2 + 1 + 1 + 1 + 1, strong.score());
        var contested = Legitimacy.assess(new Legitimacy.Facts(false, false, false, false, 0, 0, 5), weights);
        assertEquals(Legitimacy.Level.CONTESTED, contested.level());
        assertEquals(1 - 4, contested.score(), "at most two open contests count");
        var elected = Legitimacy.assess(new Legitimacy.Facts(false, false, false, true, 0, 0, 0), weights);
        assertEquals(Legitimacy.Level.ESTABLISHED, elected.level());
    }
}
