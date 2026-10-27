package dev.otectus.mcakingdoms.data;

import dev.otectus.mcakingdoms.testsupport.MemoryResources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Core definition failures name the resource, the field, what was found and what is expected. */
class DefinitionRegistryDiagnosticsTest {
    private static final String SERENUM = "mcakingdoms:mcakingdoms/kingdoms/serenum.json";

    private static String rejection(MemoryResources resources) {
        return assertThrows(IllegalArgumentException.class, () -> DefinitionRegistry.parse(resources)).getMessage();
    }

    @Test
    void shippedDefinitionsLoad() {
        DefinitionSnapshot snapshot = DefinitionRegistry.parse(MemoryResources.shipped());
        assertEquals(6, snapshot.kingdomCount());
        assertEquals(6, snapshot.namePoolCount());
        assertTrue(snapshot.biomeRuleCount() > 0 && snapshot.styleRuleCount() > 0);
    }

    @Test
    void malformedJsonNamesTheFileAndPosition() {
        String message = rejection(MemoryResources.shipped().put(SERENUM, "{\"schema\": 1, \"translation_key\": "));
        assertTrue(message.contains(SERENUM) && message.contains("malformed JSON"), message);
        assertTrue(message.contains("line"), "The parser position should be reported: " + message);
    }

    @Test
    void wrongTypeShowsTheFoundValueAndTheExpectedForm() {
        var resources = MemoryResources.shipped();
        String broken = resources.text(SERENUM).replaceFirst("\"color\"\\s*:\\s*[^,}]+", "\"color\": \"scarlet\"");
        String message = rejection(resources.put(SERENUM, broken));
        assertTrue(message.contains(SERENUM) && message.contains("heraldry.color") && message.contains("\"scarlet\"")
                && message.contains("#RRGGBB"), message);
    }

    @Test
    void missingFieldAndMissingReferenceNameTheFileToFix() {
        var resources = MemoryResources.shipped();
        String noKey = resources.text(SERENUM).replaceFirst("\"translation_key\"\\s*:\\s*\"[^\"]*\"\\s*,", "");
        String missing = rejection(resources.put(SERENUM, noKey));
        assertTrue(missing.contains(SERENUM) && missing.contains("missing required field translation_key"), missing);

        var references = MemoryResources.shipped().remove("mcakingdoms:mcakingdoms/name_pools/serenum.json");
        String reference = rejection(references);
        assertTrue(reference.contains(SERENUM) && reference.contains("missing name pool mcakingdoms:serenum")
                && reference.contains("name_pools/serenum.json"), reference);
    }

    @Test
    void unsupportedSchemaIsNamed() {
        var resources = MemoryResources.shipped();
        String future = resources.text(SERENUM).replaceFirst("\"schema\"\\s*:\\s*1", "\"schema\": 2");
        String message = rejection(resources.put(SERENUM, future));
        assertTrue(message.contains(SERENUM) && message.contains("unsupported schema 2") && message.contains("schema 1"), message);
    }
}
