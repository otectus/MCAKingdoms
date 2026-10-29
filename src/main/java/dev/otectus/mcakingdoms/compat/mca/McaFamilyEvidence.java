package dev.otectus.mcakingdoms.compat.mca;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Read-only MCA family facts. Missing/unloaded people never become deceased by inference. */
public final class McaFamilyEvidence {
    public record Marriage(UUID first, UUID second) { }
    public static Optional<Marriage> marriage(MinecraftServer server, UUID person) {
        if (!server.isSameThread()) throw new IllegalStateException("Family evidence requires server thread");
        try {
            String root = McaAccess.root(); if (root == null || root.isBlank()) return Optional.empty();
            Class<?> treeType = Class.forName(root + ".server.world.data.FamilyTree");
            Object tree = treeType.getMethod("get", ServerLevel.class).invoke(null, server.overworld());
            Object first = ((Optional<?>) treeType.getMethod("getOrEmpty", UUID.class).invoke(tree, person)).orElse(null);
            if (first == null || deceased(first) || !married(first)) return Optional.empty();
            UUID spouse = (UUID) first.getClass().getMethod("partner").invoke(first);
            if (spouse == null || spouse.equals(person)) return Optional.empty();
            Object second = ((Optional<?>) treeType.getMethod("getOrEmpty", UUID.class).invoke(tree, spouse)).orElse(null);
            if (second == null || deceased(second) || !married(second) || !person.equals(second.getClass().getMethod("partner").invoke(second))) return Optional.empty();
            return Optional.of(new Marriage(person, spouse));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return Optional.empty(); }
    }
    /** The name MCA records for a person, when its family tree knows them; a read of MCA's own record, nothing inferred. */
    public static Optional<String> name(MinecraftServer server, UUID person) {
        if (!server.isSameThread() || person == null) return Optional.empty();
        try {
            String root = McaAccess.root(); if (root == null || root.isBlank()) return Optional.empty();
            Class<?> treeType = Class.forName(root + ".server.world.data.FamilyTree");
            Object tree = treeType.getMethod("get", ServerLevel.class).invoke(null, server.overworld());
            Object node = ((Optional<?>) treeType.getMethod("getOrEmpty", UUID.class).invoke(tree, person)).orElse(null);
            if (node == null) return Optional.empty();
            Object name = node.getClass().getMethod("getName").invoke(node);
            return name instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return Optional.empty(); }
    }
    /**
     * One person's family record as MCA keeps it (A1): their recorded parents, partner and children, whether MCA recorded
     * their death, and whether they are a player. A read of MCA's own record; an unknown parent (MCA's nil id) is left
     * out, and nothing is inferred from names or ids.
     */
    public record FamilyRecord(UUID id, Set<UUID> parents, UUID partner, Set<UUID> children, boolean deceased, boolean player, String name) { }
    private static final UUID NIL = new UUID(0, 0);
    /** {@code person}'s MCA family record, or empty when MCA is absent, its family tree is unreadable, or it does not know them. */
    public static Optional<FamilyRecord> record(MinecraftServer server, UUID person) {
        if (!server.isSameThread() || person == null) return Optional.empty();
        try {
            String root = McaAccess.root(); if (root == null || root.isBlank()) return Optional.empty();
            Class<?> treeType = Class.forName(root + ".server.world.data.FamilyTree");
            Object tree = treeType.getMethod("get", ServerLevel.class).invoke(null, server.overworld());
            Object node = ((Optional<?>) treeType.getMethod("getOrEmpty", UUID.class).invoke(tree, person)).orElse(null);
            if (node == null) return Optional.empty();
            Class<?> type = node.getClass();
            var parents = new LinkedHashSet<UUID>();
            for (String parent : List.of("father", "mother")) if (type.getMethod(parent).invoke(node) instanceof UUID id && !id.equals(NIL) && !id.equals(person)) parents.add(id);
            UUID partner = type.getMethod("partner").invoke(node) instanceof UUID id && !id.equals(NIL) && !id.equals(person) ? id : null;
            var children = new LinkedHashSet<UUID>();
            if (type.getMethod("children").invoke(node) instanceof Collection<?> recorded)
                for (Object child : recorded) if (child instanceof UUID id && !id.equals(NIL) && !id.equals(person) && children.size() < 64) children.add(id);
            Object name = type.getMethod("getName").invoke(node);
            return Optional.of(new FamilyRecord(person, parents, partner, children, deceased(node), (boolean) type.getMethod("isPlayer").invoke(node),
                    name instanceof String text ? text : ""));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return Optional.empty(); }
    }
    /** MCA's age state of a loaded villager ({@code BABY}, {@code TODDLER}, {@code CHILD}, {@code TEEN}, {@code ADULT}), or empty when it has none. */
    public static Optional<String> ageState(net.minecraft.world.entity.Entity entity) {
        try {
            Object state = entity.getClass().getMethod("getAgeState").invoke(entity);
            return state instanceof Enum<?> value ? Optional.of(value.name()) : Optional.empty();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return Optional.empty(); }
    }
    private static boolean deceased(Object node) throws ReflectiveOperationException { return (boolean) node.getClass().getMethod("isDeceased").invoke(node); }
    private static boolean married(Object node) throws ReflectiveOperationException {
        Object state = node.getClass().getMethod("getRelationshipState").invoke(node);
        return (boolean) state.getClass().getMethod("isMarried").invoke(state);
    }
    private McaFamilyEvidence() { }
}
