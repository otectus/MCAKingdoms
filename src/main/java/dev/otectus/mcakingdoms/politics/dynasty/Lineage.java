package dev.otectus.mcakingdoms.politics.dynasty;

import java.util.*;

/**
 * A family as the succession rules read it (A1): references to people and the parent, child and partner edges MCA
 * records between them, never a copy of MCA's genealogy. Pure, so laws can be tested on any graph, including broken
 * ones (cycles, missing people). Nothing here infers gender, age or death from an id: a node says what its source
 * verified, and {@link Stage#UNKNOWN} or a missing node stays unknown.
 */
public final class Lineage {
    /** Life stage as verified: an adult, a minor, or not known (unloaded, no provider). */
    public enum Stage { ADULT, MINOR, UNKNOWN }

    /** One person: their recorded parents, children and partner, whether a source recorded their death, and their stage. */
    public record Node(UUID id, Set<UUID> parents, Set<UUID> children, UUID partner, boolean deceased, boolean player, Stage stage, String name) {
        public Node {
            Objects.requireNonNull(id); parents = Set.copyOf(parents); children = Set.copyOf(children);
            stage = stage == null ? Stage.UNKNOWN : stage; name = name == null ? "" : name;
        }
    }

    /** Where nodes come from: MCA's family tree in game, a fixed map in tests. Empty means the person is unknown. */
    @FunctionalInterface
    public interface Source { Optional<Node> node(UUID id); }

    /**
     * Why a candidate is considered, in the order succession considers them: a successor the government elected, a
     * claimant whose contest the crown upheld, the named successor, then the ruler's children, grandchildren and
     * siblings as MCA records them, then people the crown recognized as members of the ruling house.
     */
    public enum Relation { ELECTED, CLAIMANT, DESIGNATED, CHILD, GRANDCHILD, SIBLING, RECOGNIZED;
        /** True for the relations read from MCA's family records rather than from political decisions. */
        public boolean lineage() { return this == CHILD || this == GRANDCHILD || this == SIBLING; }
    }

    private Lineage() {
    }

    /**
     * The ruler's relatives that succession may consider, in the deterministic order the law then filters: children,
     * then grandchildren, then siblings; within each, by stable id. Visits at most {@code limit} people and never loops,
     * however the recorded edges are broken.
     */
    public static List<Map.Entry<UUID, Relation>> relatives(Source source, UUID ruler, int limit) {
        var out = new LinkedHashMap<UUID, Relation>();
        var rulerNode = source.node(ruler);
        if (rulerNode.isEmpty()) return List.of();
        var children = sorted(rulerNode.get().children());
        for (UUID child : children) if (!child.equals(ruler) && out.size() < limit) out.putIfAbsent(child, Relation.CHILD);
        for (UUID child : children) {
            var childNode = source.node(child);
            if (childNode.isEmpty()) continue;
            for (UUID grandchild : sorted(childNode.get().children()))
                if (!grandchild.equals(ruler) && out.size() < limit) out.putIfAbsent(grandchild, Relation.GRANDCHILD);
        }
        var siblings = new TreeSet<UUID>();
        for (UUID parent : rulerNode.get().parents()) source.node(parent).ifPresent(p -> siblings.addAll(p.children()));
        for (UUID sibling : siblings) if (!sibling.equals(ruler) && out.size() < limit) out.putIfAbsent(sibling, Relation.SIBLING);
        return List.copyOf(out.entrySet());
    }

    private static List<UUID> sorted(Set<UUID> ids) {
        return ids.stream().sorted().toList();
    }
}
