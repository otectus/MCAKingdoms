package dev.otectus.mcakingdoms.civic;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CivicSavedDataTest {
    @Test void futureAndMalformedPayloadsRemainReadOnlyAndByteEquivalent() {
        for(int schema:new int[]{1,99}) {
            var raw=new CompoundTag();raw.putInt("Schema",schema);raw.putString("Payload","broken civic state");
            var loaded=CivicSavedData.load(raw);
            assertFalse(loaded.writable());assertEquals(raw,loaded.save(new CompoundTag()));
        }
    }
    @Test void additiveDismissalSetDefaultsForEarlierSchemaOneSaves() {
        var raw=new CompoundTag();raw.putInt("Schema",1);
        raw.putString("Payload","{\"revision\":0,\"chapters\":{},\"contacts\":{},\"seeds\":{},\"introductions\":{}}");
        var loaded=CivicSavedData.load(raw);assertTrue(loaded.writable());assertTrue(loaded.state().dismissed.isEmpty());
        var next=loaded.copy();next.dismissed.add(UUID.randomUUID());next.validate();
        assertTrue(loaded.state().dismissed.isEmpty());
    }
    @Test void pendingCommunityRejectsMalformedDimensionAndAllowsUnmappedHome() {
        var state=new CivicSavedData.State();var npc=UUID.randomUUID();
        state.seeds.put(npc,new CivicSavedData.Seed(npc,"test:guild",null,"not a dimension",1,UUID.randomUUID(),0));
        assertThrows(IllegalArgumentException.class,state::validate);
        state.seeds.put(npc,new CivicSavedData.Seed(npc,"test:guild",null,"minecraft:overworld",1,UUID.randomUUID(),0));
        assertDoesNotThrow(state::validate);
    }
    @Test void corruptAppointmentCannotReferenceMissingChapter() {
        var state=new CivicSavedData.State();var npc=UUID.randomUUID();
        state.contacts.put(npc,new CivicSavedData.Contact(npc,UUID.randomUUID(),npc,0));
        assertThrows(IllegalArgumentException.class,state::validate);
    }
    @Test void mergeMovesSitesSeedsAndIntroductionsToTheSurvivor() {
        var state=new CivicSavedData.State();state.revision=5;UUID retired=UUID.randomUUID(),survivor=UUID.randomUUID(),npc=UUID.randomUUID(),player=UUID.randomUUID();
        var chapter=new CivicSavedData.Chapter(UUID.randomUUID(),"test:guild",null,new CivicSavedData.Site(retired,"minecraft:overworld",4,7,"workshop","workshop_l1",1,64,2),npc,3);
        state.chapters.put(chapter.id(),chapter);
        state.seeds.put(npc,new CivicSavedData.Seed(npc,"test:guild",retired,null,null,UUID.randomUUID(),4));
        state.introductions.put(player+"|test:guild",new CivicSavedData.Introduction(player,"test:guild",UUID.randomUUID(),chapter.id(),retired,9));
        state.validate();
        assertTrue(state.redirectSettlement(retired,survivor));
        state.validate();
        assertEquals(survivor,state.chapters.get(chapter.id()).site().settlement());
        assertEquals(chapter.site().buildingId(),state.chapters.get(chapter.id()).site().buildingId());
        assertEquals(survivor,state.seeds.get(npc).settlement());
        assertEquals(survivor,state.introductions.get(player+"|test:guild").settlement());
        assertFalse(state.redirectSettlement(retired,survivor));
    }
}
