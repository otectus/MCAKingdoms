package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import java.lang.reflect.Method;
import java.util.UUID;
import static dev.otectus.mcakingdoms.test.TestAssertions.*;

@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class SettlementPersistenceTest {
    private SettlementRecord record(String name) {
        return record(name, name.equals("Greenford") ? 52 : 42);
    }
    private SettlementRecord record(String name, int villageId) {
        SettlementRecord r = new SettlementRecord();
        r.id = UUID.randomUUID(); r.dimension = Level.OVERWORLD; r.anchor = new BlockPos(3,64,8);
        r.radius=32; r.bounds=SettlementBounds.around(r.anchor,32);
        r.kingdomId=new ResourceLocation("mcakingdoms:serenum"); r.displayName=name;
        r.slug=new ResourceLocation("mcakingdoms:"+name.toLowerCase());
        r.biomeAtCreation=new ResourceLocation("minecraft:plains");
        r.assignmentSource=AssignmentSource.EXACT_BIOME; r.detectionSource=DetectionSource.STRUCTURE;
        r.sourceId=new ResourceLocation("test:village"); r.sourceKey=name; r.nameLocked=true;
        r.addExternalRef("mca", "minecraft:overworld#"+villageId);
        r.addExternalRef("mca", "minecraft:overworld#"+(villageId+1));
        r.addStrongStructureIdentity(r.sourceId,r.sourceKey);r.addStrongStructureIdentity(r.sourceId,"start-a");r.addStrongStructureIdentity(r.sourceId,"start-b");
        r.retiredSlugs.add(new ResourceLocation("mcakingdoms:retired"));r.aliases.add("Old " + name);
        return r;
    }
    private static net.minecraft.nbt.Tag legacy(net.minecraft.nbt.Tag tag) {
        if (tag instanceof net.minecraft.nbt.StringTag s) return net.minecraft.nbt.StringTag.valueOf(s.getAsString().replace("mcakingdoms:","ultima_kingdoms:"));
        if (tag instanceof CompoundTag c) { CompoundTag out=new CompoundTag(); for (String k:c.getAllKeys()) out.put(k.replace("mcakingdoms:","ultima_kingdoms:"),legacy(c.get(k))); return out; }
        if (tag instanceof net.minecraft.nbt.ListTag l) { var out=new net.minecraft.nbt.ListTag(); for (var e:l) out.add(legacy(e)); return out; }
        return tag.copy();
    }
    /** A settlements file written by Ultima Kingdoms (before 0.1.1) is carried over and loads intact. */
    @GameTest(template="empty") public void preRenameSettlementFileMigratesThroughTheRealLoader(GameTestHelper helper) throws Exception {
        helper.runAfterDelay(1, helper::succeed);
        var data=new SettlementSavedData(); var original=record("Aldwick"); data.add(original);
        CompoundTag root=new CompoundTag(); root.put("data",legacy(data.save(new CompoundTag())));
        net.minecraft.nbt.NbtUtils.addCurrentDataVersion(root);
        assertTrue(root.toString().contains("ultima_kingdoms:aldwick"));
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("mcakingdoms-legacy");
        net.minecraft.nbt.NbtIo.writeCompressed(root,directory.resolve("ultima_kingdoms_settlements.dat").toFile());
        assertEquals(java.util.List.of("mcakingdoms_settlements.dat"),
                dev.otectus.mcakingdoms.persistence.LegacyRename.migrateSavedData(directory));
        CompoundTag migrated=net.minecraft.nbt.NbtIo.readCompressed(directory.resolve("mcakingdoms_settlements.dat").toFile());
        Method load=SettlementSavedData.class.getDeclaredMethod("load",CompoundTag.class); load.setAccessible(true);
        var loaded=(SettlementSavedData)load.invoke(null,migrated.getCompound("data"));
        var record=loaded.get(original.id).orElseThrow();
        assertEquals(original.snapshot(),record.snapshot());
        assertEquals(new ResourceLocation("mcakingdoms:aldwick"),record.slug);
        assertEquals(original.retiredSlugs,record.retiredSlugs);
        assertEquals(original.externalRefValues,record.externalRefValues);
        assertTrue(java.nio.file.Files.exists(directory.resolve("ultima_kingdoms_settlements.dat")));
    }
    @GameTest(template="empty") public void nbtRoundTripPreservesIdentityAndReferences(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var original=record("Aldwick"); var loaded=SettlementRecord.load(original.save());
        assertEquals(original.snapshot(), loaded.snapshot());
        assertEquals(original.strongStructureIdentities,loaded.strongStructureIdentities);
        assertEquals(original.externalRefValues,loaded.externalRefValues);
        assertEquals(original.retiredSlugs,loaded.retiredSlugs);
    }
    @GameTest(template="empty") public void mergedIdentityRedirectSurvivesSerialization(GameTestHelper helper) throws Exception {
        helper.runAfterDelay(1, helper::succeed);
        var a=record("Aldwick"); var b=record("Greenford"); var data=new SettlementSavedData();
        data.add(a); data.add(b); data.validateMergeExternalRefs(a,b);
        a.externalRefValues.forEach((namespace,values)->values.forEach(value->b.addExternalRef(namespace,value)));
        b.aliases.add(a.displayName);b.aliases.addAll(a.aliases);
        data.redirect(a.id,b.id);data.changed(b);
        Method load=SettlementSavedData.class.getDeclaredMethod("load",CompoundTag.class); load.setAccessible(true);
        var loaded=(SettlementSavedData)load.invoke(null,data.save(new CompoundTag()));
        assertEquals(b.id,loaded.get(a.id).orElseThrow().id);
        assertEquals(b.id,loaded.getByExternalRef("mca","minecraft:overworld#42").orElseThrow().id);
        assertEquals(b.id,loaded.getByExternalRef("mca","minecraft:overworld#53").orElseThrow().id);
        assertTrue(loaded.get(b.id).orElseThrow().aliases.contains("Aldwick"));
        assertTrue(loaded.get(b.id).orElseThrow().aliases.contains("Old Aldwick"));
        assertEquals(1,loaded.records().size());
    }
    @GameTest(template="empty") public void duplicateExternalReferenceIsRejectedAtomically(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var a=record("Aldwick",42);var b=record("Greenford",42);var data=new SettlementSavedData();
        data.add(a);
        assertThrows(IllegalStateException.class,()->data.add(b));
        assertEquals(1,data.records().size());
        assertEquals(a.id,data.getByExternalRef("mca","minecraft:overworld#42").orElseThrow().id);
    }
    @GameTest(template="empty") public void futureRootSchemaRefusesInitializationAndStaysClean(GameTestHelper helper) throws Exception {
        helper.runAfterDelay(1, helper::succeed);
        CompoundTag future=new CompoundTag(); future.putInt("Schema",99);
        Method load=SettlementSavedData.class.getDeclaredMethod("load",CompoundTag.class); load.setAccessible(true);
        var loaded=(SettlementSavedData)load.invoke(null,future);
        Method validate=SettlementSavedData.class.getDeclaredMethod("validateLoaded"); validate.setAccessible(true);
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->validate.invoke(loaded));
        assertInstanceOf(IllegalStateException.class,failure.getCause());
        assertFalse(loaded.isDirty());
    }
    @GameTest(template="empty") public void futureRecordSchemaIsNotSilentlyDowngraded(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var tag=record("Aldwick").save();tag.putInt("Schema",99);
        assertThrows(IllegalArgumentException.class,()->SettlementRecord.load(tag));
    }
    @GameTest(template="empty") public void futureNestedRecordProtectsWholeSave(GameTestHelper helper) throws Exception {
        helper.runAfterDelay(1, helper::succeed);
        var original=record("Aldwick"); var data=new SettlementSavedData(); data.add(original);
        var tag=data.save(new CompoundTag());tag.getList("Settlements",10).getCompound(0).putInt("Schema",99);
        Method load=SettlementSavedData.class.getDeclaredMethod("load",CompoundTag.class);load.setAccessible(true);
        var loaded=(SettlementSavedData)load.invoke(null,tag);
        Method validate=SettlementSavedData.class.getDeclaredMethod("validateLoaded");validate.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->validate.invoke(loaded));
        assertFalse(loaded.isDirty());
    }

}
