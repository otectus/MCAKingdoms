package dev.otectus.mcakingdoms.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcakingdoms.api.gating.KingdomPredicate;
import dev.otectus.mcakingdoms.api.gating.UnknownKingdomPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The configured unknown-kingdom answer fills only an omitted {@code when_unknown}, and never edits the author's JSON. */
class IntegrationConfigTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test
    void omittedFallbackUsesTheConfiguredPolicyAndDefaultsToDeny() {
        JsonObject authored = json("{\"include\":[\"mcakingdoms:serenum\"]}");
        JsonObject completed = IntegrationConfig.withUnknownPolicy(authored);
        assertFalse(authored.has("when_unknown"), "the authored predicate was modified");
        assertEquals("deny", completed.get("when_unknown").getAsString());
        assertEquals(UnknownKingdomPolicy.DENY, KingdomPredicate.fromJson(completed).whenUnknown());
    }

    @Test
    void anAuthoredFallbackIsKept() {
        JsonObject authored = json("{\"include\":[\"mcakingdoms:serenum\"],\"when_unknown\":\"allow\"}");
        assertSame(authored, IntegrationConfig.withUnknownPolicy(authored));
        assertEquals(UnknownKingdomPolicy.ALLOW, KingdomPredicate.fromJson(authored).whenUnknown());
    }
}
