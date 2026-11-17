package dev.otectus.mcakingdoms.interaction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AUD-10: review fingerprints name their parts, so a relevant change is explained and an unrelated one is not a change. */
class ReviewTest {
    @Test void identicalPartsAreNoChange() {
        assertEquals(Review.of("work", "a@3", "mode", "PROJECTS"), Review.of("work", "a@3", "mode", "PROJECTS"));
        assertEquals(List.of(), Review.changed(Review.of("work", "a@3"), Review.of("work", "a@3")));
    }

    @Test void theChangedPartsAreNamed() {
        assertEquals(List.of("state", "inventory"), Review.changed(Review.of("work", "a@3", "state", "APPROVED", "inventory", "{0=2}"),
                Review.of("work", "a@3", "state", "FUNDED", "inventory", "{}")));
        assertEquals(List.of("authority"), Review.changed(Review.of("work", "a"), Review.of("work", "a", "authority", "true")));
    }

    @Test void anUnnamedVersionGivesNoExplanation() {
        assertEquals(List.of(), Review.changed("12:PROJECTS", "13:PROJECTS"), "a legacy version string cannot be explained part by part");
        assertTrue(Review.parts("12:PROJECTS").isEmpty());
    }

    @Test void valuesWithSeparatorsOfTheirOwnStayIntact() {
        var version = Review.of("place", "1|2:3", "hand", "");
        assertEquals("1|2:3", Review.parts(version).get("place"));
        assertEquals("", Review.parts(version).get("hand"));
        assertThrows(IllegalArgumentException.class, () -> Review.of("odd"));
    }
}
