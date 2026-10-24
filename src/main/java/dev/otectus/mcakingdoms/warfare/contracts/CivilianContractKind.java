package dev.otectus.mcakingdoms.warfare.contracts;

import net.minecraft.resources.ResourceLocation;

/** Authored native quest contracts. Political consequences consume the durable proof separately. */
public enum CivilianContractKind {
    RELIEF("relief"),
    RECONNAISSANCE("reconnaissance"),
    MEDIATION("mediation"),
    AUTONOMY("autonomy"),
    DEFENSE("defense"),
    ESCORT("escort"),
    EVOLVING_AID("evolving_aid", "mcakingdoms:evolution/aid"),
    EVOLVING_MEDIATION("evolving_mediation", "mcakingdoms:evolution/mediation"),
    EVOLVING_AUTONOMY("evolving_autonomy", "mcakingdoms:evolution/autonomy"),
    EVOLVING_DEFENSE("evolving_defense", "mcakingdoms:evolution/defense");

    private final String id;
    private final ResourceLocation quest;

    // Warfare contracts are pack-authored quests in the modpack's "ultima" namespace; evolution quests ship with this mod.
    CivilianContractKind(String id) {
        this(id, "ultima:warfare/" + id);
    }
    CivilianContractKind(String id, String quest) {
        this.id = id;
        this.quest = new ResourceLocation(quest);
    }

    public String id() { return id; }
    public ResourceLocation quest() { return quest; }
    public boolean scoped() { return id.startsWith("evolving_"); }

    public static CivilianContractKind parse(String id) {
        for (var value : values()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException("Unknown civilian contract kind: " + id);
    }
}
