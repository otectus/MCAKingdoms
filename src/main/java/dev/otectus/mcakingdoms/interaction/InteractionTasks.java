package dev.otectus.mcakingdoms.interaction;

import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

public final class InteractionTasks {
    private static boolean initialized;
    public static synchronized void init(){if(initialized)return;initialized=true;
        hints();BasicTargets.init();HelpTasks.init();CoreTasks.init();ResidenceTasks.init();PoliticalTasks.init();CivicTasks.init();StandingTasks.init();WarfareTasks.init();EvolutionTasks.init();ActionRegistry.targets("scope",c->{var values=new java.util.ArrayList<>(c.choices("scenario"));values.addAll(c.choices("obligation"));return values;});
        NextSteps.init();Requirements.init();}
    private InteractionTasks(){}
    private static Component hint(String kind,String fallback,Object... args){return ActionRegistry.tr("interaction.mcakingdoms.hint."+kind,fallback,args);}
    /** Recruits' state, which several selectors depend on: missing, unsupported, switched off, or usable. */
    private static java.util.Optional<Component> recruitsUnavailable(){
        var version=ModList.get().getModContainerById("recruits").map(m->m.getModInfo().getVersion().toString());
        if(version.isEmpty())return java.util.Optional.of(Unavailable.PROVIDER_MISSING.explain(hint("recruits","Recruits 1.15.2")));
        if(!version.get().equals("1.15.2"))return java.util.Optional.of(Unavailable.PROVIDER_UNSUPPORTED.explain(hint("recruits.version","Recruits %s (the integration is audited for 1.15.2)",version.get())));
        if(!dev.otectus.mcakingdoms.warfare.WarfareConfig.ENABLED.get()||!dev.otectus.mcakingdoms.warfare.WarfareConfig.MILITARY.get())
            return java.util.Optional.of(Unavailable.DISABLED.explain(hint("military","military actions")));
        return java.util.Optional.empty();
    }
    /** Plain explanations shown when a selector has nothing to offer: the established cause where one is known, then what to do. */
    private static void hints(){
        ActionRegistry.targetHint("settlement",c->{var knowledge=dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(c.server());return knowledge.writable()
                ?hint("settlement","No settlement is known to you yet. Walk into a village or town so its record is discovered, or ask an operator to register one.")
                :Unavailable.READ_ONLY.explain(knowledge.diagnostic());});
        ActionRegistry.targetHint("kingdom",c->Unavailable.CONTENT_MISSING.explain(hint("kingdom","no kingdom definitions are loaded from data packs")));
        ActionRegistry.targetHint("player",c->hint("player","No other players are online right now."));
        ActionRegistry.targetHint("person",c->hint("person","No one is nearby and visible. Stand within 32 blocks of the person with a clear line of sight."));
        ActionRegistry.targetHint("npc",c->hint("npc","No villager or recruit is nearby and visible. Stand within 32 blocks with a clear line of sight."));
        ActionRegistry.targetHint("nearby_npc",c->hint("nearby_npc","No contact is close enough. Stand within %s blocks of them with a clear line of sight.",dev.otectus.mcakingdoms.civic.CivicConfig.CONTACT_RANGE.get()));
        ActionRegistry.targetHint("organization",c->Unavailable.CONTENT_MISSING.explain(hint("organization","no guild or organization definitions are loaded from data packs")));
        ActionRegistry.targetHint("native_faction",c->recruitsUnavailable().<Object>map(r->r).orElse(hint("native_faction","No native factions are known. A Recruits faction team must exist.")));
        ActionRegistry.targetHint("campaign",c->!dev.otectus.mcakingdoms.warfare.WarfareConfig.ENABLED.get()?Unavailable.DISABLED.explain(hint("warfare","warfare"))
                :hint("campaign","No campaigns are known to you. Declare one from the War Room of a settlement you can see."));
        ActionRegistry.targetHint("accord",c->!dev.otectus.mcakingdoms.warfare.WarfareConfig.ENABLED.get()?Unavailable.DISABLED.explain(hint("warfare","warfare"))
                :hint("accord","No accords are known to you. Accords are proposed from a settlement's War Room."));
        ActionRegistry.targetHint("deployment",c->recruitsUnavailable().<Object>map(r->r).orElse(hint("deployment","You have no native deployments. Deploy a recruit first.")));
        ActionRegistry.targetHint("site",c->!dev.otectus.mcakingdoms.warfare.WarfareConfig.WORLD_CONTEXT.get()?Unavailable.DISABLED.explain(hint("world","world sites and routes"))
                :hint("site","No world sites have been discovered by you yet. Explore, or ask an operator to map sites."));
        ActionRegistry.targetHint("route",c->!dev.otectus.mcakingdoms.warfare.WarfareConfig.WORLD_CONTEXT.get()?Unavailable.DISABLED.explain(hint("world","world sites and routes"))
                :hint("route","No routes are authorized for you yet. Routes connect institutions you have discovered."));
        ActionRegistry.targetHint("election",c->hint("election","No election is open in a kingdom where you may vote."));
        ActionRegistry.targetHint("building",c->hint("building","Stand on, or look at, the building block you want to register."));
        ActionRegistry.targetHint("scenario",c->dev.otectus.mcakingdoms.evolution.EvolutionRuntime.get(c.server()).worldSettings().enabled()
                ?hint("scenario","No evolving-world scenario is open for you. Scenarios arise from institutions, family ties and protection events.")
                :hint("scenario.off","World evolution is off for this world, so no new opportunity opens. Existing ones stay readable."));
        ActionRegistry.targetHint("pact",c->hint("pact","No protectorate pacts involve your government."));
        ActionRegistry.targetHint("obligation",c->hint("obligation","No protectorate obligations are open for you."));
        ActionRegistry.targetHint("transfer",c->hint("transfer","No recruit transfers involve you. Transfers are proposed by the recruit's current owner."));
        ActionRegistry.targetHint("reconciliation_transfer",c->hint("reconciliation_transfer","No transfers need reconciliation."));
        ActionRegistry.targetHint("drama",c->hint("drama","No world events involve you."));
        ActionRegistry.targetHint("merge",c->hint("merge","No organization merges are pending for your organizations."));
        ActionRegistry.targetHint("merge_obligation",c->hint("merge_obligation","No accepted work of the merging organization is open for you."));
        ActionRegistry.targetHint("native_recruit",c->recruitsUnavailable().<Object>map(r->r).orElse(hint("native_recruit","No recruit you own is nearby and visible. Stand within 32 blocks of your recruit.")));
        ActionRegistry.targetHint("native_group",c->recruitsUnavailable().<Object>map(r->r).orElse(hint("native_group","No native recruit group was shared with you by that recipient. They share one with Share a recruit transfer destination.")));
        ActionRegistry.targetHint("own_native_group",c->recruitsUnavailable().<Object>map(r->r).orElse(hint("own_native_group","You own no native recruit groups.")));
        ActionRegistry.targetHint("shared_transfer_destination",c->hint("shared_transfer_destination","You have shared no transfer destinations."));
        ActionRegistry.targetHint("organization_template",c->Unavailable.CONTENT_MISSING.explain(hint("organization_template","no organization charter templates are loaded from data packs")));
        ActionRegistry.targetHint("organization_lifecycle",c->hint("organization_lifecycle","No organization history or status is available to you."));
        ActionRegistry.targetHint("chapter",c->!dev.otectus.mcakingdoms.civic.CivicConfig.ENABLED.get()?Unavailable.DISABLED.explain(hint("civic","guild chapter services"))
                :hint("chapter","No guild chapters are known to you. Discover the settlement that hosts the chapter first."));
        ActionRegistry.targetHint("contact",c->hint("contact","No dismissible contacts are recorded for you."));
        ActionRegistry.targetHint("own_membership",c->hint("own_membership","You hold no active guild memberships. Join a guild first."));
        ActionRegistry.targetHint("guild_permission",c->hint("guild_permission","This organization defines no services."));
        ActionRegistry.targetHint("standing_event",c->hint("standing_event","No pending standing events await review."));
        ActionRegistry.targetHint("mca_community",c->ModList.get().isLoaded("mca")?hint("mca_community","No native communities are visible. MCA's villages must be loaded.")
                :Unavailable.PROVIDER_MISSING.explain(hint("mca","MCA Reborn")));
        ActionRegistry.targetHint("withdrawable",c->hint("withdrawable","No agreements or petitions of yours are open."));
        ActionRegistry.targetHint("political_record",c->hint("political_record","No political records are open for your government."));
        ActionRegistry.targetHint("political_permission",c->hint("political_permission","No permissions are defined."));
        ActionRegistry.targetHint("scope",c->hint("scope","No opportunity or obligation is open for you."));
        ActionRegistry.targetHint("commodity",c->hint("commodity","No items are registered."));
    }
}
