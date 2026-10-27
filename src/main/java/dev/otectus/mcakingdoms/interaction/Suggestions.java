package dev.otectus.mcakingdoms.interaction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.factions.organization.OrganizationApi;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.function.Supplier;

/**
 * Kingdom Tasks' start view: where the player stands and up to three next steps derived from their own current state.
 *
 * <p>Each suggestion comes from a read the player is already allowed to make: a consent, signature or vote waiting on
 * them, the recognized settlement they stand in, a guild contact within reach, or the first-village guide for someone who
 * knows no settlement yet. Nothing undiscovered, private to others or unavailable on this server is suggested, and no
 * suggestion assumes the player wants to govern or fight. Opening one is an ordinary task request, checked again.
 */
final class Suggestions {
    static final int LIMIT = 3;

    private Suggestions() {
    }

    private static Component s(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("interaction.mcakingdoms.start." + key, fallback, args);
    }

    private record Suggestion(String kind, String target, Map<String, String> prefill, Component label, Component reason) { }

    static InteractionNetwork.Reply start(ServerPlayer player, InteractionNetwork.Request request) {
        var server = player.getServer();
        var api = McaKingdomsApi.get(server);
        JsonObject out = new JsonObject();
        var here = api.getSettlementAt(player.serverLevel(), player.blockPosition());
        Component kingdomName = here.flatMap(settlement -> api.getKingdom(settlement.kingdomId()))
                .<Component>map(k -> Component.translatable(k.translationKey())).orElse(Component.empty());
        here.ifPresent(settlement -> {
            JsonObject place = new JsonObject();
            place.addProperty("id", settlement.id().toString());
            place.addProperty("name", settlement.displayName());
            place.addProperty("kingdom", InteractionNetwork.encode(kingdomName));
            out.add("here", place);
        });
        List<Suggestion> suggestions = new ArrayList<>();
        // Things waiting on this player come first: they are the reason to open the menu now.
        add(suggestions, () -> transferConsents(player));
        add(suggestions, () -> signatures(player));
        add(suggestions, () -> votes(player));
        add(suggestions, () -> worldEvents(player));
        here.ifPresent(settlement -> suggestions.add(new Suggestion("task", "settlement.here", Map.of(),
                s("here", "Read %s", settlement.displayName()), s("here.reason", "You are in %s, a settlement of %s.", settlement.displayName(), kingdomName))));
        // A01: someone with no civic residence who stands in a settlement is offered the way to record one.
        if (here.isPresent() && api.getResidence(player).isEmpty() && dev.otectus.mcakingdoms.config.McaKingdomsConfig.PLAYER_REGISTRATION.get()) {
            boolean ready = dev.otectus.mcakingdoms.citizen.ResidenceRegistration.evidence(player).found();
            String name = here.get().displayName();
            suggestions.add(ready
                    ? new Suggestion("task", "citizen.register_residence", Map.of(), s("residence", "Register %s as your residence", name),
                            s("residence.reason", "Your bed is in %s and you have no civic residence yet.", name))
                    : new Suggestion("task", "citizen.residence_status", Map.of(), s("residence.check", "How to make %s your residence", name),
                            s("residence.check.reason", "You have no civic residence yet. Founding and civic services need one.")));
        }
        add(suggestions, () -> guildContacts(player));
        if (SettlementKnowledge.get(server).all(player, api, Optional.empty(), 1).isEmpty())
            suggestions.add(new Suggestion("guide", "ledger_recipe", Map.of(), s("first_village", "Find and record your first village"),
                    s("first_village.reason", "You do not know any settlement yet. Walk into a village; a Village Ledger lists the ones you find.")));
        JsonArray list = new JsonArray();
        for (Suggestion suggestion : suggestions) {
            if (list.size() >= LIMIT) break;
            if (suggestion.kind().equals("task")) {
                var task = ActionRegistry.find(suggestion.target());
                if (task.isEmpty() || task.get().operator() && !player.hasPermissions(2) || !TaskMeta.availability(task.get(), server).available()) continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("kind", suggestion.kind());
            item.addProperty("target", suggestion.target());
            JsonObject prefill = new JsonObject();
            suggestion.prefill().forEach(prefill::addProperty);
            item.addProperty("prefill", prefill.toString());
            item.addProperty("label", InteractionNetwork.encode(suggestion.label()));
            item.addProperty("reason", InteractionNetwork.encode(suggestion.reason()));
            list.add(item);
        }
        out.add("suggestions", list);
        out.addProperty("operator", player.hasPermissions(2));
        return new InteractionNetwork.Reply(request.request(), InteractionNetwork.NONE, InteractionNetwork.NONE, "start",
                InteractionNetwork.encode(InteractionNetwork.msg("catalogue.title", "Kingdoms")),
                InteractionNetwork.encode(list.isEmpty() ? s("none", "Nothing is waiting for you. Search for a goal or browse a category.")
                        : s("help", "Suggested from where you are and what is waiting for you.")),
                List.of(), out.toString(), 0, false, false, false);
    }

    /** A source that fails (a provider absent, saved data read-only) simply suggests nothing. */
    private static void add(List<Suggestion> into, Supplier<List<Suggestion>> source) {
        try {
            into.addAll(source.get());
        } catch (RuntimeException unavailable) {
            // The start view stays useful without this source.
        }
    }

    private static List<Suggestion> transferConsents(ServerPlayer player) {
        var server = player.getServer();
        return new dev.otectus.mcakingdoms.evolution.RecruitTransferService(server).transfers(player).stream()
                .filter(t -> t.recipient().equals(player.getUUID()) && t.phase() == dev.otectus.mcakingdoms.evolution.RecruitTransferData.Phase.OFFERED)
                .limit(LIMIT).map(t -> new Suggestion("task", "transfer.consent", Map.of("transfer", t.id().toString()),
                        s("transfer", "Review the recruit transfer from %s", Names.player(server, t.owner())),
                        s("transfer.reason", "%s offered you a recruit; it waits for your consent.", Names.player(server, t.owner())))).toList();
    }

    private static List<Suggestion> signatures(ServerPlayer player) {
        if (!(dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(player.getServer()) instanceof dev.otectus.mcakingdoms.politics.GovernmentService government)) return List.of();
        return government.awaitingSignature(player, LIMIT).stream().map(a -> new Suggestion("task", "politics.sign",
                Map.of("kingdom", a.kingdom(), "target", a.agreement().toString()), s("sign", "Sign: %s", a.title()),
                s("sign.reason", "This agreement waits for the signature of %s, which you may give.", Names.kingdomName(player.getServer(), a.kingdom())))).toList();
    }

    private static List<Suggestion> votes(ServerPlayer player) {
        var politics = dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(player.getServer());
        return politics.elections(player, 0, 64).stream()
                .filter(e -> e.state() == dev.otectus.mcakingdoms.api.politics.PoliticalTransition.ElectionState.OPEN && e.viewerEligible() && !e.viewerVoted())
                .limit(LIMIT).map(e -> new Suggestion("task", "politics.election_vote", Map.of("election", e.id().toString()),
                        s("vote", "Vote in the election for %s", Names.kingdomName(player.getServer(), e.kingdom())),
                        s("vote.reason", "Voting is open and your vote is not recorded yet."))).toList();
    }

    private static List<Suggestion> worldEvents(ServerPlayer player) {
        return dev.otectus.mcakingdoms.evolution.drama.DramaRuntime.get(player.getServer()).dramas(player).stream().filter(d -> d.canConsent())
                .limit(LIMIT).map(d -> new Suggestion("task", "drama.consent", Map.of("drama", d.id().toString()),
                        s("world_event", "Answer the world event %s", d.title()), s("world_event.reason", "Your consent is requested."))).toList();
    }

    private static List<Suggestion> guildContacts(ServerPlayer player) {
        var server = player.getServer();
        var civic = dev.otectus.mcakingdoms.civic.CivicRuntime.get(server);
        var organizations = OrganizationApi.get(server);
        List<Suggestion> out = new ArrayList<>();
        for (var definition : organizations.definitions()) {
            if (out.size() >= 1) break;
            if (civic.nearbyContact(player, definition.id()).isEmpty()) continue;
            Component name = Component.translatableWithFallback(definition.nameKey(), PlayerWords.text(definition.nameKey()));
            out.add(new Suggestion("task", "civic.explain", Map.of("organization", definition.id().toString()),
                    s("guild", "Check what %s services you qualify for", name), s("guild.reason", "A %s contact is within reach.", name)));
        }
        return out;
    }
}
