package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.AssignmentSource;
import dev.otectus.mcakingdoms.api.DetectionSource;
import dev.otectus.mcakingdoms.api.SettlementBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * F02: one bad record, duplicate identity or broken redirect refuses the whole settlement store instead of loading the
 * rest writable. A refused store keeps the original payload, never becomes dirty and refuses every mutation; a healthy
 * store and legitimate retired-id chains load unchanged. (GameTests, because record loading needs the game bootstrap.)
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class SettlementLoadRefusalGameTests {
    private static int nextReference = 9_100;

    private static SettlementRecord record(String name) {
        SettlementRecord r = new SettlementRecord();
        r.id = UUID.randomUUID();
        r.dimension = Level.OVERWORLD;
        r.anchor = new BlockPos(3, 64, 8);
        r.radius = 32;
        r.bounds = SettlementBounds.around(r.anchor, 32);
        r.kingdomId = new ResourceLocation("mcakingdoms:serenum");
        r.displayName = name;
        r.slug = new ResourceLocation("mcakingdoms:" + name.toLowerCase(java.util.Locale.ROOT));
        r.biomeAtCreation = new ResourceLocation("minecraft:plains");
        r.assignmentSource = AssignmentSource.EXACT_BIOME;
        r.detectionSource = DetectionSource.STRUCTURE;
        r.sourceId = new ResourceLocation("test:village");
        r.sourceKey = name;
        r.addStrongStructureIdentity(r.sourceId, r.sourceKey); // what loading normalizes a structure record to
        r.addExternalRef("mca", "minecraft:overworld#" + nextReference++);
        return r;
    }

    private static SettlementSavedData load(CompoundTag tag) throws Exception {
        Method load = SettlementSavedData.class.getDeclaredMethod("load", CompoundTag.class);
        load.setAccessible(true);
        return (SettlementSavedData) load.invoke(null, tag);
    }

    private static CompoundTag healthy(SettlementRecord... records) {
        SettlementSavedData data = new SettlementSavedData();
        for (SettlementRecord record : records) data.add(record);
        return data.save(new CompoundTag());
    }

    private static CompoundTag redirect(UUID source, UUID target) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Source", source);
        tag.putUUID("Target", target);
        return tag;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    /** The refusal contract: an explanation, no records, original bytes kept, not dirty, every write refused. */
    private static void assertRefused(CompoundTag original, String expected) throws Exception {
        CompoundTag before = original.copy();
        SettlementSavedData loaded = load(original);
        check(loaded.refused(), "should refuse: " + expected);
        Method validate = SettlementSavedData.class.getDeclaredMethod("validateLoaded");
        validate.setAccessible(true);
        String message = null;
        try {
            validate.invoke(loaded);
        } catch (InvocationTargetException failure) {
            check(failure.getCause() instanceof IllegalStateException, "wrong refusal type: " + failure.getCause());
            message = failure.getCause().getMessage();
        }
        check(message != null, "validateLoaded did not refuse: " + expected);
        check(message.contains(expected), "expected '" + expected + "' in: " + message);
        check(message.contains("refusing to initialize"), message);
        check(loaded.records().isEmpty(), "a refused store exposed records");
        check(!loaded.isDirty(), "a refused store is dirty");
        check(before.equals(loaded.save(new CompoundTag())), "a refused store must save the original payload unchanged");
        loaded.setDirty();
        check(!loaded.isDirty(), "an autosave could write a refused store");
        boolean refusedAdd = false;
        try { loaded.add(record("Late")); } catch (IllegalStateException expectedFailure) { refusedAdd = true; }
        check(refusedAdd, "a refused store accepted a new settlement");
        boolean refusedMark = false;
        try { loaded.markReconciled(); } catch (IllegalStateException expectedFailure) { refusedMark = true; }
        check(refusedMark, "a refused store accepted a reconciliation mark");
        check(before.equals(original), "loading altered the payload it read");
    }

    @GameTest(template = "empty")
    public static void healthyStoreLoadsIdenticalIdentities(GameTestHelper helper) throws Exception {
        var a = record("Aldwick");
        var b = record("Greenford");
        CompoundTag saved = healthy(a, b);
        SettlementSavedData loaded = load(saved);
        check(!loaded.refused(), "a healthy store was refused");
        check(a.snapshot().equals(loaded.get(a.id).orElseThrow().snapshot()), "Aldwick changed on load");
        check(b.snapshot().equals(loaded.get(b.id).orElseThrow().snapshot()), "Greenford changed on load");
        check(saved.equals(loaded.save(new CompoundTag())), "a healthy round trip changed the payload");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidOrMissingIdRefusesTheWholeStore(GameTestHelper helper) throws Exception {
        CompoundTag saved = healthy(record("Aldwick"), record("Greenford"));
        saved.getList("Settlements", 10).getCompound(1).put("Id", new IntArrayTag(new int[]{1, 2}));
        assertRefused(saved, "Settlement record 1 is malformed");
        CompoundTag missing = healthy(record("Aldwick"));
        missing.getList("Settlements", 10).getCompound(0).remove("Id");
        assertRefused(missing, "Settlement record 0 is malformed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void malformedBoundsOrRadiusRefuse(GameTestHelper helper) throws Exception {
        CompoundTag saved = healthy(record("Aldwick"), record("Greenford"));
        CompoundTag second = saved.getList("Settlements", 10).getCompound(1);
        second.putInt("MinX", 500);
        second.putInt("MaxX", 10);
        assertRefused(saved, "Settlement record 1 is malformed");
        CompoundTag radius = healthy(record("Aldwick"));
        radius.getList("Settlements", 10).getCompound(0).putInt("Radius", -4);
        assertRefused(radius, "negative radius");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void duplicateIdRefusesInsteadOfOverwriting(GameTestHelper helper) throws Exception {
        var a = record("Aldwick");
        CompoundTag saved = healthy(a, record("Greenford"));
        saved.getList("Settlements", 10).getCompound(1).putUUID("Id", a.id);
        assertRefused(saved, "share the id " + a.id);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void duplicateExternalReferenceRefuses(GameTestHelper helper) throws Exception {
        var a = record("Aldwick");
        var b = record("Greenford");
        b.addExternalRef("mca", a.externalRefValues.get("mca").iterator().next());
        CompoundTag saved = new CompoundTag();
        saved.putInt("Schema", 1);
        ListTag list = new ListTag();
        list.add(a.save());
        list.add(b.save());
        saved.put("Settlements", list);
        saved.put("Redirects", new ListTag());
        assertRefused(saved, "Conflicting MCA: Kingdoms settlement external references");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void malformedRedirectsRefuse(GameTestHelper helper) throws Exception {
        CompoundTag missingTarget = healthy(record("Aldwick"));
        CompoundTag broken = new CompoundTag();
        broken.putUUID("Source", UUID.randomUUID());
        missingTarget.getList("Redirects", 10).add(broken);
        assertRefused(missingTarget, "Settlement redirect 0 is malformed");

        CompoundTag wrongType = healthy(record("Aldwick"));
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("not a redirect"));
        wrongType.put("Redirects", strings);
        assertRefused(wrongType, "Redirects is not a list of records");

        var a = record("Aldwick");
        var b = record("Greenford");
        CompoundTag conflicting = healthy(a, b);
        UUID retired = UUID.randomUUID();
        conflicting.getList("Redirects", 10).add(redirect(retired, a.id));
        conflicting.getList("Redirects", 10).add(redirect(retired, b.id));
        assertRefused(conflicting, "is redirected to both");

        var c = record("Aldwick");
        var d = record("Greenford");
        CompoundTag live = healthy(c, d);
        live.getList("Redirects", 10).add(redirect(c.id, d.id));
        assertRefused(live, "still a live settlement");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void redirectCyclesAndDanglingTargetsRefuse(GameTestHelper helper) throws Exception {
        CompoundTag cycle = healthy(record("Aldwick"));
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        cycle.getList("Redirects", 10).add(redirect(first, second));
        cycle.getList("Redirects", 10).add(redirect(second, first));
        assertRefused(cycle, "Settlement redirect cycle");

        CompoundTag dangling = healthy(record("Aldwick"));
        dangling.getList("Redirects", 10).add(redirect(UUID.randomUUID(), UUID.randomUUID()));
        assertRefused(dangling, "which is no settlement");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void retiredIdChainsStillLoad(GameTestHelper helper) throws Exception {
        var live = record("Aldwick");
        CompoundTag saved = healthy(live);
        UUID oldest = UUID.randomUUID(), middle = UUID.randomUUID();
        saved.getList("Redirects", 10).add(redirect(oldest, middle));
        saved.getList("Redirects", 10).add(redirect(middle, live.id));
        SettlementSavedData loaded = load(saved);
        check(!loaded.refused(), "a legitimate retired-id chain was refused");
        check(live.id.equals(loaded.resolveId(oldest)), "the chain does not resolve to the live settlement");
        check(live.id.equals(loaded.get(middle).orElseThrow().id), "the middle id does not resolve");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void futureSchemasRefuse(GameTestHelper helper) throws Exception {
        CompoundTag future = healthy(record("Aldwick"));
        future.putInt("Schema", 99);
        assertRefused(future, "Unsupported MCA: Kingdoms save schema 99");
        CompoundTag nested = healthy(record("Aldwick"), record("Greenford"));
        nested.getList("Settlements", 10).getCompound(1).putInt("Schema", 7);
        assertRefused(nested, "settlement schema 7 at record 1");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anUnreadableFileIsRefusedNotStartedOver(GameTestHelper helper) {
        check(!SettlementSavedData.fresh(false).refused(), "a new world must start with a writable store");
        SettlementSavedData unreadable = SettlementSavedData.fresh(true);
        check(unreadable.refused(), "an existing but unreadable file started over");
        unreadable.setDirty();
        check(!unreadable.isDirty(), "an unreadable store could be saved over its file");
        boolean refused = false;
        try { unreadable.add(record("Late")); } catch (IllegalStateException expected) { refused = true; }
        check(refused, "an unreadable store accepted a settlement");
        helper.succeed();
    }
}
