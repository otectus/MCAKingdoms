package dev.otectus.mcakingdoms.civic;

import com.google.gson.JsonParser;
import dev.otectus.mcakingdoms.data.ReloadReport;
import dev.otectus.mcakingdoms.testsupport.MemoryResources;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** A rejected civic reload keeps the committed rules and is recorded as a rejection, not reported as a success. */
class CivicDefinitionsReloadTest {
    private static final ResourceLocation LAMPLIGHTERS = new ResourceLocation("mcakingdoms", "lamplighters");
    private static final String SHIPPED = "mcakingdoms:mcakingdoms_factions/civic_network/lamplighters.json";

    @BeforeEach
    void fresh() {
        ReloadReport.reset();
        CivicDefinitions.INSTANCE.clear();
    }

    private static String describe(ReloadReport.Attempt attempt) {
        return ReloadReport.describe(Optional.of(attempt), null).stream().map(Component::getString).collect(Collectors.joining("\n"));
    }

    private static ReloadReport.Attempt reload(MemoryResources resources) {
        var attempt = ReloadReport.begin();
        var prepared = CivicDefinitions.INSTANCE.prepare(resources, null);
        CivicDefinitions.INSTANCE.apply(prepared, resources, null);
        CivicDefinitions.INSTANCE.commitPending();
        ReloadReport.committed();
        return attempt;
    }

    @Test
    void validRulesAreAcceptedAndInvalidOnesKeepThem() {
        var accepted = reload(MemoryResources.shipped());
        assertEquals(ReloadReport.State.ACCEPTED, accepted.state());
        assertTrue(CivicDefinitions.INSTANCE.get(LAMPLIGHTERS).isPresent());
        long revision = CivicDefinitions.INSTANCE.revision();

        var resources = MemoryResources.shipped();
        String future = resources.text(SHIPPED).replaceFirst("\"schema\"\\s*:\\s*1", "\"schema\": 2");
        var rejected = reload(resources.put(SHIPPED, future));
        assertEquals(ReloadReport.State.PARTIAL, rejected.state());
        assertTrue(CivicDefinitions.INSTANCE.get(LAMPLIGHTERS).isPresent(), "Rejected rules must not replace the committed set");
        assertEquals(revision, CivicDefinitions.INSTANCE.revision());
        String text = describe(rejected);
        assertTrue(text.contains("civic_network/lamplighters.json: unsupported schema 2"), text);
        assertTrue(text.contains("Civic network rules: the previous definitions remain active."), text);
    }

    @Test
    void malformedJsonIsARejectionNotASilentSkip() {
        reload(MemoryResources.shipped());
        var rejected = reload(MemoryResources.shipped().put("mcakingdoms:mcakingdoms_factions/civic_network/extra.json", "{\"schema\": 1,"));
        assertEquals(ReloadReport.State.PARTIAL, rejected.state());
        String text = describe(rejected);
        assertTrue(text.contains("civic_network/extra.json: malformed JSON"), text);
        assertTrue(CivicDefinitions.INSTANCE.get(LAMPLIGHTERS).isPresent());
    }

    @Test
    void fieldProblemsNameTheFieldAndWhatWasFound() {
        String rules = "{\"schema\":1,\"organization\":\"mcakingdoms:lamplighters\",\"sponsor_quests\":[],\"building_families\":[\"Work Shop\"],\"commissions\":[]}";
        var problem = assertThrows(IllegalArgumentException.class, () -> CivicDefinitions.parse(JsonParser.parseString(rules)));
        assertTrue(problem.getMessage().contains("building_families") && problem.getMessage().contains("\"Work Shop\""), problem.getMessage());
        String missing = "{\"schema\":1,\"sponsor_quests\":[],\"building_families\":[\"workshop\"],\"commissions\":[]}";
        var absent = assertThrows(IllegalArgumentException.class, () -> CivicDefinitions.parse(JsonParser.parseString(missing)));
        assertTrue(absent.getMessage().contains("missing required field organization"), absent.getMessage());
        assertTrue(Map.of().isEmpty());
    }

    @Test
    void firstLoadRejectionDoesNotClaimEarlierRules() {
        var resources = MemoryResources.shipped();
        String future = resources.text(SHIPPED).replaceFirst("\"schema\"\\s*:\\s*1", "\"schema\": 2");
        var rejected = reload(resources.put(SHIPPED, future));
        assertTrue(CivicDefinitions.INSTANCE.get(LAMPLIGHTERS).isEmpty());
        assertTrue(describe(rejected).contains("no earlier definitions were active"), describe(rejected));
    }
}
