package dev.otectus.mcakingdoms.politics;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.McaKingdoms;
import dev.otectus.mcakingdoms.document.DocumentLedger;
import dev.otectus.mcakingdoms.document.DocumentService;
import dev.otectus.mcakingdoms.heraldry.HeraldicBlockEntity;
import dev.otectus.mcakingdoms.heraldry.HeraldicBlocks;
import dev.otectus.mcakingdoms.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/**
 * B4 GameTests on the server's own records: a founding charter sealed at a press is official, a copy with an altered
 * hash or kingdom is unverified, a moved capital makes it historical, a revocation reads on every copy, a reused
 * request seals nothing twice, the ledger survives a reload, and a placed banner binds to the settlement it stands in.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class DocumentGameTests {
    private static final String YEW = "mcakingdoms:yew";

    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    /** A settlement of Yew: {@code lane} puts it in its own lane far from other tests; lane -1 puts it around the test structure. */
    private static SettlementView settlement(GameTestHelper helper, String key, int lane) {
        var service = McaKingdomsApi.get(helper.getLevel().getServer());
        BlockPos position = lane < 0 ? helper.absolutePos(new BlockPos(1, 2, 1)) : helper.absolutePos(new BlockPos(0, 2, 2)).offset(0, 0, 63_000 + lane * 48);
        var result = service.registerCandidate(helper.getLevel(), SettlementCandidate.structure(helper.getLevel().dimension(), position, 4,
                new ResourceLocation("mcakingdoms:political_test"), key + position, null));
        return service.setKingdom(result.id(), new ResourceLocation(YEW));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void chartersAreOfficialOnlyWhileTheirRecordHolds(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        if (!(KingdomsPoliticsApi.get(server) instanceof GovernmentService politics)) throw new GameTestAssertException("No political service");
        var leader = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "CharterLeader")) { @Override public boolean hasPermissions(int level) { return level <= 2; } };
        var first = settlement(helper, "charter-a", 0);
        var second = settlement(helper, "charter-b", 1);
        // The server's own records, as a founding would leave them, with this test's player as leader: the charter
        // service reads the live government, and the GameTest server has no profile cache to vouch for a test player.
        var data = PoliticalSavedData.get(server);
        var next = data.transaction(); next.revision++;
        var leaderOffice = new Office("mcakingdoms:leader", McaKingdoms.POLITICS.get("mcakingdoms:leader", "office"), new Person(leader.getUUID(), Kind.PLAYER),
                null, leader.getUUID(), helper.getLevel().getGameTime());
        next.governments.put(YEW, new Government(YEW, first.id(), YEW + "_charter", McaKingdoms.POLITICS.get(YEW + "_charter", "government"), State.ACTIVE,
                Map.of("mcakingdoms:leader", leaderOffice), Map.of(), null, next.revision));
        data.commit(next);
        var press = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.setBlock(new BlockPos(1, 2, 1), HeraldicBlocks.SEAL_PRESS.get().defaultBlockState());
        leader.moveTo(press.getX() + 1.5, press.getY(), press.getZ() + 0.5);
        leader.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.KINGDOM_CHARTER.get()));
        var ledger = DocumentLedger.get(server);
        UUID request = UUID.randomUUID();
        DocumentService.seal(leader, request, DocumentLedger.Kind.FOUNDING, YEW, "", "");
        ItemStack charter = leader.getItemInHand(InteractionHand.MAIN_HAND).copy();
        check(!DocumentService.blank(charter), "The charter stayed blank");
        int sealed = ledger.documents().size();
        DocumentService.seal(leader, request, DocumentLedger.Kind.FOUNDING, YEW, "", "");
        check(ledger.documents().size() == sealed, "A reused request sealed a second document");
        check(DocumentService.inspect(leader, charter).verdict() == DocumentService.Verdict.OFFICIAL, "A fresh founding charter was not official");
        var forged = charter.copy(); forged.getTag().getCompound("Charter").putString("Hash", "0".repeat(32));
        check(DocumentService.inspect(leader, forged).verdict() == DocumentService.Verdict.UNVERIFIED, "An altered hash was believed");
        var moved = charter.copy(); moved.getTag().getCompound("Charter").putString("Kingdom", "mcakingdoms:lunari");
        check(DocumentService.inspect(leader, moved).verdict() == DocumentService.Verdict.UNVERIFIED, "An altered kingdom was believed");
        var seat = politics.execute(leader, new Request(UUID.randomUUID(), politics.revision(), Action.SEAT, YEW, "", second.id().toString(), null, "", "", "", 0, 0, 0));
        check(seat.success(), "Moving the capital was refused: " + seat.message());
        check(DocumentService.inspect(leader, charter).verdict() == DocumentService.Verdict.OFFICIAL_HISTORICAL, "A founding charter of a moved capital still read as current");
        UUID document = charter.getTag().getCompound("Charter").getUUID("Document");
        DocumentService.revoke(leader, document, "Superseded");
        check(DocumentService.inspect(leader, charter).verdict() == DocumentService.Verdict.REVOKED, "A revoked document still read as official");
        var reloaded = DocumentLedger.load(ledger.save(new CompoundTag()));
        check(reloaded.document(document).equals(ledger.document(document)), "The ledger did not survive a reload");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aPlacedBannerStandsForTheSettlementItIsIn(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var place = settlement(helper, "banner", -1);
        var relative = new BlockPos(1, 2, 2);
        var state = HeraldicBlocks.KINGDOM_BANNER.get().defaultBlockState();
        helper.setBlock(relative, state);
        var absolute = helper.absolutePos(relative);
        var placer = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "BannerPlacer"));
        state.getBlock().setPlacedBy(helper.getLevel(), absolute, state, placer, new ItemStack(ModItems.KINGDOM_BANNER.get()));
        if (!(helper.getLevel().getBlockEntity(absolute) instanceof HeraldicBlockEntity banner)) throw new GameTestAssertException("No heraldic block entity");
        check(banner.kingdom().equals(YEW), "The banner did not bind to the settlement's kingdom: " + banner.kingdom() + " in " + place.kingdomId());
        check(banner.primary() != 0 && !banner.historical(), "The banner has no colours of its kingdom");
        var saved = banner.saveWithoutMetadata();
        check(saved.getCompound("Heraldry").getString("Kingdom").equals(YEW), "The binding was not saved");
        helper.succeed();
    }
}
