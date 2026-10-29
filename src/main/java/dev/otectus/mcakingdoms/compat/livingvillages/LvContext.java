package dev.otectus.mcakingdoms.compat.livingvillages;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import java.util.*;

/**
 * Verified village, realm and war context for MCA companions (Crime's jurisdiction, Conversations' and Quests' context
 * values). Only facts the bridge has confirmed; nothing about a war is offered before the bridge has recorded it.
 */
public final class LvContext {
    private LvContext() {
    }

    public record Control(String controllerTeam, String controllerName, String recognizedName, boolean occupied, boolean contested,
                          long revision, String explanation) {
    }

    /** The control of a bound Living Villages village, or empty for any other settlement or when the integration is inactive. */
    public static Optional<Control> control(MinecraftServer server, UUID settlement) {
        if (!LvConfig.companionContext()) return Optional.empty();
        var rt = LvRuntime.get(server).orElse(null);
        if (rt == null) return Optional.empty();
        var state = rt.view();
        var binding = state.bindingForSettlement(settlement).orElse(null);
        if (binding == null || binding.realm() == null) return Optional.empty();
        var realm = state.realms.get(binding.realm());
        if (realm == null || realm.team().isEmpty()) return Optional.empty();
        String recognized = LvPresentation.realmName(state, binding.recognizedRealm());
        boolean contested = rt.access.besieged(binding.village());
        return Optional.of(new Control(realm.team(), realm.name(), recognized, binding.occupied(), contested, state.revision,
                binding.occupied() ? "Living Villages realm " + realm.name() + " occupies it; " + recognized + " is still the recognized government; local law continues"
                        : "Living Villages realm " + realm.name() + " governs it; local law continues"));
    }

    /**
     * Context values for an entity in a bound village ({@code mcakingdoms.realm}, {@code mcakingdoms.realm_recognized},
     * {@code mcakingdoms.realm_occupied}, {@code mcakingdoms.realm_war}, {@code mcakingdoms.realm_enemy}); empty elsewhere.
     */
    public static Map<String, String> values(MinecraftServer server, Entity entity) {
        if (!LvConfig.companionContext()) return Map.of();
        var rt = LvRuntime.get(server).orElse(null);
        if (rt == null || !(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) return Map.of();
        var settlement = rt.kingdoms().getSettlementAt(level, entity.blockPosition()).orElse(null);
        if (settlement == null) return Map.of();
        var state = rt.view();
        var binding = state.bindingForSettlement(settlement.id()).orElse(null);
        if (binding == null) return Map.of();
        var out = new LinkedHashMap<String, String>();
        out.put("mcakingdoms.realm", LvPresentation.realmName(state, binding.realm()));
        out.put("mcakingdoms.realm_recognized", LvPresentation.realmName(state, binding.recognizedRealm()));
        out.put("mcakingdoms.realm_occupied", Boolean.toString(binding.occupied()));
        var conflict = LvWarBridge.openFor(state, binding.village()).orElse(null);
        out.put("mcakingdoms.realm_war", conflict == null ? "none" : conflict.phase().name().toLowerCase(Locale.ROOT));
        if (conflict != null) out.put("mcakingdoms.realm_enemy", LvPresentation.realmName(state,
                binding.village().equals(conflict.defenderVillage()) ? conflict.attackerRealm() : conflict.defenderRealm()));
        return Map.copyOf(out);
    }
}
