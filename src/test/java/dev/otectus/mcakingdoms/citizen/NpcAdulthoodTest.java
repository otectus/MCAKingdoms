package dev.otectus.mcakingdoms.citizen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** One adulthood rule (spec 8.3): the canonical stage a Root's stage presents as, whatever the Root calls it. */
class NpcAdulthoodTest {
    @Test
    void customStageNamesFollowTheCanonicalStageTheyPresentAs() {
        assertEquals(NpcAdulthood.Verdict.ADULT, NpcAdulthood.classify("adult", "elder_wood").verdict());
        assertEquals(NpcAdulthood.Verdict.ADULT, NpcAdulthood.classify("senior", "greybeard").verdict());
        assertEquals(NpcAdulthood.Verdict.MINOR, NpcAdulthood.classify("teen", "fledgling").verdict());
        assertEquals(NpcAdulthood.Verdict.MINOR, NpcAdulthood.classify("BABY", "sprout").verdict());
        assertEquals("fledgling", NpcAdulthood.classify("teen", "fledgling").stage());
    }

    @Test
    void anUnrecognizedPresentationIsUnknownNotAdult() {
        assertEquals(NpcAdulthood.Verdict.UNKNOWN, NpcAdulthood.classify("ancient", "thrall").verdict());
        assertEquals(NpcAdulthood.Verdict.UNKNOWN, NpcAdulthood.classify("", "x").verdict());
        assertEquals(NpcAdulthood.Reason.STAGE_UNRECOGNIZED, NpcAdulthood.classify(null, "x").reason());
    }
}
