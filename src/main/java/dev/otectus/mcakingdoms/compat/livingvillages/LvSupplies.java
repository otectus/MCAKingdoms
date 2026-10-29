package dev.otectus.mcakingdoms.compat.livingvillages;

import net.minecraft.world.item.Item;

import java.util.List;
import java.util.UUID;

/** Read helpers the Kingdom Tasks forms use to offer only what the bridge would accept. */
public final class LvSupplies {
    private LvSupplies() {
    }

    /** What the village bound to {@code settlement} can take now ({@code currency}, or a material id); empty when not a village. */
    public static List<String> deliverables(LvRuntime rt, UUID settlement) {
        return rt.view().bindingForSettlement(settlement).flatMap(b -> rt.access.village(rt.server, b.village()))
                .map(village -> LvEconomy.deliverables(rt, village)).orElse(List.of());
    }

    public static Item item(LvRuntime rt, String kind) {
        return LvEconomy.itemFor(rt, kind);
    }

    public static String partyName(LvRuntime rt, String party) {
        return dev.otectus.mcakingdoms.api.politics.PoliticalPartyRef.parse(party).map(p -> LvDiplomacy.name(rt, rt.view(), p)).orElse(party);
    }
}
