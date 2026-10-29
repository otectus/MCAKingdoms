package dev.otectus.mcakingdoms.politics.dynasty;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A5: pure lineage and law tests over hand-built family graphs, including broken ones. */
class SuccessionResolverTest {
    private static final SuccessionLaw DESIGNATED = SuccessionLaw.get(new ResourceLocation("mcakingdoms", "designated_then_lineage")).orElseThrow();
    private static final SuccessionLaw EQUAL = SuccessionLaw.get(new ResourceLocation("mcakingdoms", "equal_primogeniture")).orElseThrow();
    private static final SuccessionLaw COUNCIL = SuccessionLaw.get(new ResourceLocation("mcakingdoms", "council_confirmation")).orElseThrow();
    private static final SuccessionLaw ELECTIVE = SuccessionLaw.get(new ResourceLocation("mcakingdoms", "elective_monarchy")).orElseThrow();
    private static final SuccessionLaw MANUAL = SuccessionLaw.get(SuccessionLaw.MANUAL).orElseThrow();

    private static UUID id(int n) { return new UUID(0, n); }

    /** A family graph: people by id, with parents and children kept consistent. */
    private static final class Family implements Lineage.Source {
        final Map<UUID, Lineage.Node> nodes = new HashMap<>();
        Family person(int n, Lineage.Stage stage, boolean deceased, boolean player) {
            var old = nodes.get(id(n));
            nodes.put(id(n), new Lineage.Node(id(n), old == null ? Set.of() : old.parents(), old == null ? Set.of() : old.children(), null, deceased, player, stage, "P" + n));
            return this;
        }
        Family adult(int n) { return person(n, Lineage.Stage.ADULT, false, false); }
        Family child(int parent, int child) {
            var p = nodes.get(id(parent)); var c = nodes.get(id(child));
            var kids = new HashSet<>(p.children()); kids.add(id(child));
            nodes.put(id(parent), new Lineage.Node(p.id(), p.parents(), kids, p.partner(), p.deceased(), p.player(), p.stage(), p.name()));
            var parents = new HashSet<>(c.parents()); parents.add(id(parent));
            nodes.put(id(child), new Lineage.Node(c.id(), parents, c.children(), c.partner(), c.deceased(), c.player(), c.stage(), c.name()));
            return this;
        }
        @Override public Optional<Lineage.Node> node(UUID id) { return Optional.ofNullable(nodes.get(id)); }
    }

    private static SuccessionResolver.Resolution resolve(SuccessionLaw law, Family family, int ruler, List<Map.Entry<UUID, Lineage.Relation>> named) {
        return SuccessionResolver.resolve(law, family, id(ruler), named, Set.of(), Set.of(), person -> true);
    }

    @Test void manualAndElectiveLawsNeverReadLineage() {
        var family = new Family().adult(1).adult(2).child(1, 2);
        assertEquals(SuccessionResolver.Outcome.MANUAL, resolve(MANUAL, family, 1, List.of()).outcome());
        assertEquals(SuccessionResolver.Outcome.ELECTION, resolve(ELECTIVE, family, 1, List.of()).outcome());
    }

    @Test void childrenInStableIdOrderBreakTwinTies() {
        var family = new Family().adult(1).adult(7).adult(3).child(1, 7).child(1, 3);
        var result = resolve(EQUAL, family, 1, List.of());
        assertEquals(SuccessionResolver.Outcome.HEIR, result.outcome());
        assertEquals(id(3), result.heir().orElseThrow().id());
        assertEquals(result, resolve(EQUAL, family, 1, List.of()), "the same evidence always gives the same answer");
    }

    @Test void namedSuccessorComesFirstOnlyUnderTheDesignatedLaw() {
        var family = new Family().adult(1).adult(2).adult(9).child(1, 2);
        var named = List.of(Map.entry(id(9), Lineage.Relation.DESIGNATED));
        assertEquals(id(9), resolve(DESIGNATED, family, 1, named).heir().orElseThrow().id());
        assertEquals(id(2), resolve(EQUAL, family, 1, List.of()).heir().orElseThrow().id());
    }

    @Test void deceasedAndRenouncedHeirsAreSkippedWithTheirReasons() {
        var family = new Family().adult(1).person(2, Lineage.Stage.ADULT, true, false).adult(3).adult(4).child(1, 2).child(1, 3).child(1, 4);
        var result = SuccessionResolver.resolve(EQUAL, family, id(1), List.of(), Set.of(), Set.of(id(3)), person -> true);
        assertEquals(id(4), result.heir().orElseThrow().id());
        assertEquals("dynasty.mcakingdoms.candidate.deceased", result.candidates().get(0).reasonKey());
        assertEquals("dynasty.mcakingdoms.candidate.renounced", result.candidates().get(1).reasonKey());
    }

    @Test void anUnverifiedHeirBlocksInsteadOfBeingSkipped() {
        var family = new Family().adult(1).person(2, Lineage.Stage.UNKNOWN, false, false).adult(3).child(1, 2).child(1, 3);
        var result = resolve(EQUAL, family, 1, List.of());
        assertEquals(SuccessionResolver.Outcome.BLOCKED, result.outcome());
        assertEquals("dynasty.mcakingdoms.outcome.unverified", result.reasonKey());
        var missing = new Family().adult(1).adult(3).child(1, 3);
        var node = missing.nodes.get(id(1));
        var kids = new HashSet<>(node.children()); kids.add(id(2)); // a child MCA names but no longer knows
        missing.nodes.put(id(1), new Lineage.Node(node.id(), node.parents(), kids, null, false, false, Lineage.Stage.ADULT, "P1"));
        assertEquals(SuccessionResolver.Outcome.BLOCKED, resolve(EQUAL, missing, 1, List.of()).outcome());
    }

    @Test void aMinorHeirWaitsUnderRegencyOnlyWhenTheLawAllowsIt() {
        var family = new Family().adult(1).person(2, Lineage.Stage.MINOR, false, false).child(1, 2);
        var result = resolve(EQUAL, family, 1, List.of());
        assertEquals(SuccessionResolver.Outcome.MINOR_HEIR, result.outcome());
        var noRegency = new SuccessionLaw(new ResourceLocation("test", "no_regency"), SuccessionLaw.Mode.CUSTOM_DATAPACK, "test.law", false, true,
                SuccessionLaw.Confirmation.NONE, false, true, true, EnumSet.of(Lineage.Relation.CHILD));
        assertEquals(SuccessionResolver.Outcome.BLOCKED, resolve(noRegency, family, 1, List.of()).outcome());
    }

    @Test void brokenGraphsTerminate() {
        var family = new Family().adult(1).adult(2).child(1, 2).child(2, 1); // a cycle: each is the other's parent
        var result = resolve(EQUAL, family, 1, List.of());
        assertEquals(id(2), result.heir().orElseThrow().id());
        assertTrue(result.candidates().size() <= SuccessionResolver.LIMIT);
    }

    @Test void aRulerWithoutAFamilyRecordBlocksUnlessSomeoneIsNamed() {
        var family = new Family().adult(9);
        assertEquals("dynasty.mcakingdoms.outcome.no_family_record", resolve(EQUAL, family, 1, List.of()).reasonKey());
        assertEquals(id(9), resolve(DESIGNATED, family, 1, List.of(Map.entry(id(9), Lineage.Relation.DESIGNATED))).heir().orElseThrow().id());
    }

    @Test void childlessRulersPassToSiblingsThenGrandchildrenComeBeforeSiblings() {
        var siblings = new Family().adult(10).adult(1).adult(5).child(10, 1).child(10, 5);
        assertEquals(id(5), resolve(EQUAL, siblings, 1, List.of()).heir().orElseThrow().id());
        var grand = new Family().adult(10).adult(1).adult(5).person(2, Lineage.Stage.ADULT, true, false).adult(3)
                .child(10, 1).child(10, 5).child(1, 2).child(2, 3);
        var result = resolve(EQUAL, grand, 1, List.of());
        assertEquals(id(3), result.heir().orElseThrow().id());
        assertEquals(Lineage.Relation.GRANDCHILD, result.heir().orElseThrow().relation());
    }

    @Test void twoParentsOfAnyGenderShareChildren() {
        var family = new Family().adult(1).adult(2).adult(3).child(1, 3).child(2, 3); // no gender is read or inferred
        assertEquals(id(3), resolve(EQUAL, family, 1, List.of()).heir().orElseThrow().id());
        assertEquals(id(3), resolve(EQUAL, family, 2, List.of()).heir().orElseThrow().id());
    }

    @Test void anElectedSuccessorIsNeverReplacedByLineageNorConfirmedAgain() {
        var family = new Family().adult(1).adult(2).child(1, 2).person(8, Lineage.Stage.ADULT, false, true);
        var named = List.of(Map.entry(id(8), Lineage.Relation.ELECTED));
        var result = resolve(COUNCIL, family, 1, named);
        assertEquals(SuccessionResolver.Outcome.HEIR, result.outcome());
        assertEquals(id(8), result.heir().orElseThrow().id());
        assertEquals(SuccessionResolver.Outcome.CONFIRMATION, resolve(COUNCIL, family, 1, List.of()).outcome());
    }

    @Test void upheldClaimantsPrecedeTheNamedSuccessorAndRecognizedMembersFollowSiblings() {
        var family = new Family().adult(1).adult(4).adult(6);
        var named = List.of(Map.entry(id(4), Lineage.Relation.CLAIMANT), Map.entry(id(6), Lineage.Relation.DESIGNATED));
        assertEquals(id(4), resolve(DESIGNATED, family, 1, named).heir().orElseThrow().id());
        var result = SuccessionResolver.resolve(EQUAL, family, id(1), List.of(), Set.of(id(6)), Set.of(), person -> true);
        assertEquals(id(6), result.heir().orElseThrow().id());
        assertEquals(Lineage.Relation.RECOGNIZED, result.heir().orElseThrow().relation());
    }

    @Test void kindAndResidenceGatesExplainThemselves() {
        var family = new Family().adult(1).person(2, Lineage.Stage.ADULT, false, true).adult(3).child(1, 2).child(1, 3);
        var noPlayers = new SuccessionLaw(new ResourceLocation("test", "npcs"), SuccessionLaw.Mode.CUSTOM_DATAPACK, "test.law", false, true,
                SuccessionLaw.Confirmation.NONE, true, false, true, EnumSet.of(Lineage.Relation.CHILD));
        var result = resolve(noPlayers, family, 1, List.of());
        assertEquals(id(3), result.heir().orElseThrow().id());
        assertEquals("dynasty.mcakingdoms.candidate.kind_excluded", result.candidates().get(0).reasonKey());
        var notResident = SuccessionResolver.resolve(EQUAL, family, id(1), List.of(), Set.of(), Set.of(), person -> !person.equals(id(2)));
        assertEquals("dynasty.mcakingdoms.candidate.not_eligible", notResident.candidates().get(0).reasonKey());
    }

    @Test void nobodyEligibleLeavesItToTheOfficials() {
        var family = new Family().adult(1).person(2, Lineage.Stage.ADULT, true, false).child(1, 2);
        assertEquals(SuccessionResolver.Outcome.NO_HEIR, resolve(EQUAL, family, 1, List.of()).outcome());
    }
}
