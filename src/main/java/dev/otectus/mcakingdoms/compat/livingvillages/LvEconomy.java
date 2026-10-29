package dev.otectus.mcakingdoms.compat.livingvillages;

import dev.otectus.mcakingdoms.compat.livingvillages.LvState.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Living Villages' economy stays canonical: its treasury, stores and costs are read and shown, never accrued again here.
 * The one write is a supply delivery, a bounded transaction: the player's items are taken through the inventory, the
 * provider's own store credit takes what fits, any surplus goes back to the player, and the result is journaled and
 * acknowledged at the next save before any standing is granted.
 */
public final class LvEconomy {
    private LvEconomy() {
    }

    /** The configured currency (Living Villages calls it emeralds whatever it is), with its display name. */
    static Item currency(LvRuntime rt) {
        return rt.access.currency();
    }

    public static Component currencyName(LvRuntime rt) {
        return currency(rt).getDescription();
    }

    /** What can be delivered to this village now: its currency while the treasury has room, and the materials it stocks. */
    static List<String> deliverables(LvRuntime rt, Object village) {
        var out = new ArrayList<String>();
        if (rt.access.treasury(village) < rt.access.maxTreasury()) out.add("currency");
        for (String material : new TreeSet<>(rt.access.materials(village)))
            if (rt.access.material(village, material) < rt.access.maxMaterial(material)) out.add(material);
        return out;
    }

    static Item itemFor(LvRuntime rt, String kind) {
        if (kind.equals("currency")) return currency(rt);
        var id = ResourceLocation.tryParse(kind);
        return id == null ? null : ForgeRegistries.ITEMS.getValue(id);
    }

    private static int owned(ServerPlayer player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item) && !stack.hasTag()) count += stack.getCount();
        return count;
    }

    private static int take(ServerPlayer player, Item item, int amount) {
        int left = amount;
        for (ItemStack stack : player.getInventory().items) {
            if (left <= 0) break;
            if (!stack.is(item) || stack.hasTag()) continue;
            int used = Math.min(left, stack.getCount());
            stack.shrink(used);
            left -= used;
        }
        player.getInventory().setChanged();
        return amount - left;
    }

    /**
     * Delivers up to {@code requested} of {@code kind} from the player standing in the settlement. Partial capacity is honoured
     * and reported; a replayed request id returns its first result without moving anything again.
     */
    public static String supply(LvRuntime rt, ServerPlayer player, UUID settlement, String kind, int requested, UUID request) {
        var prior = rt.view().journal.get(request);
        if (prior != null) {
            if (prior.kind() != OperationKind.RELIEF || !player.getUUID().equals(prior.actor()))
                throw LvText.refuse("request_reused", "That request belongs to another action.");
            return LvText.text("supply_replayed", "Already delivered: %s.", prior.after());
        }
        var binding = rt.view().bindingForSettlement(settlement).orElseThrow(() -> LvText.refuse("not_bound", "That settlement is not a Living Villages village."));
        if (rt.kingdoms().getSettlementAt(player.serverLevel(), player.blockPosition()).filter(s -> s.id().equals(settlement)).isEmpty())
            throw LvText.refuse("supply_presence", "Stand in the village to hand over supplies.");
        var village = rt.access.village(rt.server, binding.village()).orElseThrow(() -> LvText.refuse("provider_missing", "Living Villages no longer lists that village."));
        if (requested < 1 || requested > 2304) throw LvText.refuse("supply_amount", "Deliver from 1 to 2304 items at a time.");
        if (!deliverables(rt, village).contains(kind)) throw LvText.refuse("supply_not_needed", "The village cannot store any more of that.");
        Item item = itemFor(rt, kind);
        if (item == null) throw LvText.refuse("supply_unknown", "That item is not available.");
        int room = kind.equals("currency") ? rt.access.maxTreasury() - rt.access.treasury(village)
                : rt.access.maxMaterial(kind) - rt.access.material(village, kind);
        int have = owned(player, item);
        if (have <= 0) throw LvText.refuse("supply_none", "You carry no %s.", item.getDescription());
        int amount = Math.min(Math.min(requested, have), Math.max(0, room));
        if (amount <= 0) throw LvText.refuse("supply_not_needed", "The village cannot store any more of that.");
        int before = kind.equals("currency") ? rt.access.treasury(village) : rt.access.material(village, kind);
        var next = rt.data.snapshot();
        next.journal.put(request, new Operation(request, OperationKind.RELIEF, settlement, null, OperationState.INTENT,
                kind + ":" + before, kind + ":" + (before + amount), rt.now(), 0, 0, false, "supply", ActorKind.PLAYER, player.getUUID()));
        if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        int taken = take(player, item, amount);
        int credited = kind.equals("currency") ? rt.access.credit(village, taken) : rt.access.creditMaterial(village, kind, taken);
        if (credited < taken) {
            // The provider's store filled meanwhile: what it did not take goes straight back to the player.
            var back = new ItemStack(item, taken - credited);
            if (!player.getInventory().add(back)) player.drop(back, false);
        }
        rt.access.dirty(rt.server);
        int after = kind.equals("currency") ? rt.access.treasury(village) : rt.access.material(village, kind);
        next = rt.data.snapshot();
        next.journal.put(request, next.journal.get(request).state(OperationState.APPLIED, kind + ":" + after + " (+" + credited + ")", rt.now()));
        next.record(EventKind.RELIEF, rt.now(), settlement, binding.realm(), null, null, player.getName().getString() + ": " + credited + " " + item.getDescription().getString(), false);
        rt.commit(next);
        // The standing for it is granted once the world save makes the delivery durable (acknowledged, below).
        rt.awaitingSave.add(request);
        return credited < requested
                ? LvText.text("supply_partial", "Delivered %s of %s %s; the rest stays with you because the village had room or you carried no more.", credited, requested, item.getDescription())
                : LvText.text("supply_done", "Delivered %s %s to %s.", credited, item.getDescription(), binding.providerName());
    }

    private static final java.util.regex.Pattern CREDITED = java.util.regex.Pattern.compile("\\(\\+(\\d+)\\)$");

    /** What a journaled supply delivery credited to the provider, read from its applied state ({@code kind:after (+n)}). */
    static int credited(Operation op) {
        var match = CREDITED.matcher(op.after());
        return op.kind() == OperationKind.RELIEF && match.find() ? Integer.parseInt(match.group(1)) : 0;
    }

    /**
     * A supply delivery the world save has made durable: the delivering player gains a small standing with the village's
     * kingdom, once per request (the request id is the standing's correlation).
     */
    static void acknowledged(LvRuntime rt, Operation op) {
        int credited = credited(op);
        if (credited <= 0 || !LvConfig.companionContext()) return;
        String kingdom = rt.kingdoms().getSettlement(op.subject()).map(s -> s.kingdomId().toString()).orElse("");
        String place = rt.view().bindingForSettlement(op.subject()).map(Binding::providerName).orElse("");
        dev.otectus.mcakingdoms.factions.Consequences.standing(rt.server, op.actor(), kingdom, Math.min(20, 2 + credited / 16), op.id(),
                "Supplied " + place);
    }

    // ---- growth feedback -------------------------------------------------------------------------------------------------------

    /** Why the village is or is not growing, in the provider's own terms, with the action that addresses it where there is one. */
    public static List<Component> growth(LvRuntime rt, UUID settlement) {
        var binding = rt.view().bindingForSettlement(settlement).orElseThrow(() -> LvText.refuse("not_bound", "That settlement is not a Living Villages village."));
        var village = rt.access.village(rt.server, binding.village()).orElseThrow(() -> LvText.refuse("provider_missing", "Living Villages no longer lists that village."));
        var out = new ArrayList<Component>();
        int people = rt.access.population(village), beds = rt.access.beds(village);
        out.add(LvText.tr("growth.population", "Population: %s of %s beds (%s soldiers, %s employed, %s idle).", people, beds,
                rt.access.recruits(village), rt.access.workers(village), rt.access.jobless(village)));
        Component currency = currencyName(rt);
        Object pending = null;
        for (Object slot : rt.access.structures(village)) if (!rt.access.slotBuilt(slot)) { pending = slot; break; }
        boolean adoptedPaused = binding.adopted() && !LvConfig.adoptedConstruction();
        if (adoptedPaused) out.add(LvText.tr("growth.adopted", "Construction is paused here: MCA: Kingdoms keeps the buildings of a village placed under management as they are."));
        else if (!rt.access.flag("EXPANSION_ENABLED")) out.add(LvText.tr("growth.expansion_off", "Living Villages' expansion is turned off on this server."));
        if (pending != null) {
            String type = rt.access.slotType(pending);
            int cost = rt.access.structureCost(type);
            if (!rt.access.slotPaid(pending)) {
                int land = rt.access.number("LAND_COST_ESTIMATE", 0);
                int need = cost + land, has = rt.access.treasury(village);
                out.add(has >= need ? LvText.tr("growth.building_affordable", "Next building: %s; its %s %s are affordable and will be paid at the next step.", type, need, currency)
                        : LvText.tr("growth.building_short", "Next building: %s — waiting for %s %s (the treasury has %s). Supplying %s helps.", type, need, currency, has, currency));
            } else {
                out.add(LvText.tr("growth.building_progress", "Building %s: %s of %s.", type, rt.access.slotProgress(pending), rt.access.number("STRUCTURE_THRESHOLD", 0)));
            }
        } else if (people >= beds) {
            out.add(LvText.tr("growth.beds", "Every bed is taken; the village grows when it builds or upgrades a house."));
        }
        if (rt.access.houseLimit(village) >= 0)
            out.add(LvText.tr("growth.capped", "The ground here would not take more than %s houses; this village will not grow past its beds.", rt.access.houseLimit(village)));
        if (rt.access.besieged(binding.village())) out.add(LvText.tr("growth.siege", "Under siege: all work has stopped until it is over."));
        out.add(rt.access.materialized(binding.village()) ? LvText.tr("growth.live", "Its people are in the world now.")
                : LvText.tr("growth.simulated", "Simulated while nobody is near (this is normal); it still grows."));
        out.add(LvText.tr("growth.treasury", "Treasury: %s of %s %s.", rt.access.treasury(village), rt.access.maxTreasury(), currency));
        return out;
    }
}
