package dev.otectus.mcakingdoms.data;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.otectus.mcakingdoms.development.DevelopmentDefinitions;
import dev.otectus.mcakingdoms.development.DevelopmentTemplate;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.politics.dynasty.NobleRank;
import dev.otectus.mcakingdoms.politics.dynasty.SuccessionLaw;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

/**
 * Authoring commands (P08), beside {@code /kingdoms reload}: {@code /kingdoms definitions diagnose} shows the last reload
 * attempt domain by domain; {@code validate} checks the data packs now loaded for the 0.3.0 domains (succession laws,
 * court ranks, development templates) without installing anything; {@code explain <id>} shows one active definition
 * of those domains as the server reads it. The other domains validate when {@code /kingdoms reload} runs, each keeping
 * its last good definitions when rejected.
 */
public final class DefinitionCommands {
    private static final String P = "command.mcakingdoms.definitions.";

    private DefinitionCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("definitions").requires(source -> source.hasPermission(2))
                .then(Commands.literal("diagnose").executes(DefinitionCommands::diagnose))
                .then(Commands.literal("validate").executes(DefinitionCommands::validate))
                .then(Commands.literal("explain").then(Commands.argument("id", StringArgumentType.string()).executes(DefinitionCommands::explain)))));
    }

    private static Component t(String key, String fallback, Object... args) { return ActionRegistry.tr(P + key, fallback, args); }

    private static void line(CommandContext<CommandSourceStack> c, Component text) { c.getSource().sendSuccess(() -> text, false); }

    private static int diagnose(CommandContext<CommandSourceStack> c) {
        var latest = ReloadReport.latest();
        if (latest.isEmpty()) { line(c, t("none", "No definitions have been loaded yet this run.")); return 0; }
        ReloadReport.describe(latest, null).forEach(text -> line(c, text));
        var outcomes = latest.get().outcomes();
        for (var domain : ReloadReport.Domain.values()) {
            var outcome = outcomes.get(domain);
            if (outcome == null) { line(c, t("domain.not_reported", "%s: not reported in this attempt.", domain.label())); continue; }
            if (outcome.accepted()) line(c, t("domain.accepted", "%s: accepted (%s).", domain.label(), outcome.counts().toString()));
            else {
                line(c, t("domain.rejected", "%s: rejected; the previous definitions stay active.", domain.label()));
                outcome.problems().stream().limit(5).forEach(problem -> line(c, Component.literal("  " + problem)));
            }
        }
        return 1;
    }

    private static int validate(CommandContext<CommandSourceStack> c) {
        var manager = c.getSource().getServer().getResourceManager();
        var problems = new ArrayList<String>();
        int laws = 0, ranks = 0, templates = 0;
        for (var entry : manager.listResources("mcakingdoms/succession_laws", p -> p.getPath().endsWith(".json")).entrySet()) {
            var id = id(entry.getKey(), "mcakingdoms/succession_laws");
            try { if (!SuccessionLaw.builtInId(id)) SuccessionLaw.validate(id, DefinitionProblems.parse(entry.getKey(), entry.getValue())); else throw new IllegalArgumentException("a built-in law cannot be replaced"); laws++; }
            catch (RuntimeException invalid) { problems.add(entry.getKey() + ": " + DefinitionProblems.top(invalid)); }
        }
        for (var entry : manager.listResources("mcakingdoms/noble_ranks", p -> p.getPath().endsWith(".json")).entrySet()) {
            try { NobleRank.validate(id(entry.getKey(), "mcakingdoms/noble_ranks"), DefinitionProblems.parse(entry.getKey(), entry.getValue())); ranks++; }
            catch (RuntimeException invalid) { problems.add(entry.getKey() + ": " + DefinitionProblems.top(invalid)); }
        }
        for (var entry : manager.listResources("mcakingdoms/development_templates", p -> p.getPath().endsWith(".json")).entrySet()) {
            try { DevelopmentTemplate.validate(id(entry.getKey(), "mcakingdoms/development_templates"), DefinitionProblems.parse(entry.getKey(), entry.getValue())); templates++; }
            catch (RuntimeException invalid) { problems.add(entry.getKey() + ": " + DefinitionProblems.top(invalid)); }
        }
        line(c, problems.isEmpty() ? t("valid", "Valid: %s succession law(s), %s court rank(s), %s development template(s). Nothing was installed; /kingdoms reload installs them.", laws, ranks, templates)
                : t("invalid", "%s problem(s) found; a reload would keep the previous definitions of the affected domain.", problems.size()));
        problems.stream().limit(10).forEach(problem -> c.getSource().sendFailure(Component.literal(problem)));
        line(c, t("other_domains", "Kingdoms, governments, scenarios, world events, organizations, gates, civic rules and world context are validated by /kingdoms reload."));
        return problems.isEmpty() ? 1 : 0;
    }

    private static ResourceLocation id(ResourceLocation file, String directory) {
        return new ResourceLocation(file.getNamespace(), file.getPath().substring(directory.length() + 1, file.getPath().length() - 5));
    }

    private static int explain(CommandContext<CommandSourceStack> c) {
        var id = ResourceLocation.tryParse(StringArgumentType.getString(c, "id"));
        if (id == null) { c.getSource().sendFailure(t("unknown", "No active succession law, court rank or development template has that id.")); return 0; }
        var law = SuccessionLaw.get(id);
        if (law.isPresent()) {
            var l = law.get();
            line(c, t("law", "Succession law %s (%s): %s", id.toString(), dev.otectus.mcakingdoms.politics.dynasty.DynastyService.lawTitle(l), l.mode().name().toLowerCase(Locale.ROOT)));
            line(c, t("law.detail", "Named successor first: %s; council confirms: %s; minors under regency: %s; considers: %s; players: %s; villagers: %s.",
                    l.designatedFirst(), l.confirmation() == SuccessionLaw.Confirmation.COUNCIL, l.minorRegency(), l.relations().toString().toLowerCase(Locale.ROOT), l.playersEligible(), l.npcsEligible()));
            return 1;
        }
        var rank = NobleRank.get(id);
        if (rank.isPresent()) {
            var r = rank.get();
            line(c, t("rank", "Court rank %s: tier %s, landed %s, court function %s; styled by %s kingdom(s). It grants no authority.", id.toString(), r.tier(), r.landed(), r.court(), r.styles().size()));
            return 1;
        }
        var template = DevelopmentTemplate.get(id);
        if (template.isPresent()) {
            var w = template.get();
            line(c, t("template", "Development template %s: %s, from mode %s; %s demand(s); %s block(s); %s tier point(s).", id.toString(), w.role().name().toLowerCase(Locale.ROOT),
                    w.minimumMode().name().toLowerCase(Locale.ROOT), w.demand().size(), w.blocks().size(), w.tierPoints()));
            return 1;
        }
        c.getSource().sendFailure(t("unknown", "No active succession law, court rank or development template has that id."));
        return 0;
    }
}
