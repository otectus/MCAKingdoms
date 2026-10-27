package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.testsupport.MemoryResources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Political definition failures name the file and the field instead of a generic limit failure. */
class PoliticalDefinitionsDiagnosticsTest {
    private static final String CHARTER = "mcakingdoms:mcakingdoms/governments/serenum_charter.json";

    private static String rejection(MemoryResources resources) {
        return assertThrows(IllegalArgumentException.class, () -> PoliticalDefinitions.parse(resources)).getMessage();
    }

    @Test
    void shippedDefinitionsLoad() {
        var loaded = PoliticalDefinitions.parse(MemoryResources.shipped());
        assertTrue(loaded.containsKey("mcakingdoms:serenum_charter") && loaded.containsKey("mcakingdoms:leader"));
    }

    @Test
    void schemaKindTypeAndReferenceProblemsAreActionable() {
        var resources = MemoryResources.shipped();
        String charter = resources.text(CHARTER);
        String future = rejection(MemoryResources.shipped().put(CHARTER, charter.replaceFirst("\"schema\"\\s*:\\s*1", "\"schema\": 2")));
        assertTrue(future.contains(CHARTER) && future.contains("unsupported schema 2"), future);
        String kind = rejection(MemoryResources.shipped().put(CHARTER, charter.replaceFirst("\"kind\"\\s*:\\s*\"government\"", "\"kind\": \"office\"")));
        assertTrue(kind.contains(CHARTER) && kind.contains("kind must be \"government\""), kind);
        String type = rejection(MemoryResources.shipped().put(CHARTER, charter.replaceFirst("\"maxOffices\"\\s*:\\s*\\d+", "\"maxOffices\": \"many\"")));
        assertTrue(type.contains(CHARTER), type);
        String reference = rejection(MemoryResources.shipped().remove("mcakingdoms:mcakingdoms/offices/herald.json"));
        assertTrue(reference.contains("offices references mcakingdoms:herald"), reference);
        String malformed = rejection(MemoryResources.shipped().put(CHARTER, "{"));
        assertTrue(malformed.contains(CHARTER) && malformed.contains("malformed JSON"), malformed);
    }
}
