package dev.otectus.mcakingdoms.interaction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcakingdoms.api.factions.organization.OrganizationExplanation;
import dev.otectus.mcakingdoms.api.factions.organization.OrganizationLifecycleResult;
import dev.otectus.mcakingdoms.api.factions.organization.OrganizationLifecycleView;
import dev.otectus.mcakingdoms.api.factions.organization.OrganizationMembershipSnapshot;
import dev.otectus.mcakingdoms.api.factions.organization.OrganizationMergeView;
import dev.otectus.mcakingdoms.api.politics.AgreementClauseView;
import dev.otectus.mcakingdoms.api.politics.InstitutionView;
import dev.otectus.mcakingdoms.api.politics.PoliticalFact;
import dev.otectus.mcakingdoms.api.politics.PoliticalTransition;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.api.progression.ProgressionPredicate;
import dev.otectus.mcakingdoms.evolution.EvolutionState;
import dev.otectus.mcakingdoms.evolution.ProtectionState;
import dev.otectus.mcakingdoms.evolution.RecruitTransferData;
import dev.otectus.mcakingdoms.evolution.drama.DramaState;
import dev.otectus.mcakingdoms.warfare.CampaignState;
import dev.otectus.mcakingdoms.warfare.mobilization.MobilizationDoctrine;
import dev.otectus.mcakingdoms.warfare.mobilization.MobilizationSavedData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every name a player reads in Kingdom Tasks has an entry in each bundled language: task titles, help and field labels,
 * categories, the enum labels shown in choices and records, and political history types. English entries must match
 * the fallback the code carries, so the catalogue search and the screen agree.
 */
class TranslationCoverageTest {
    /** Enum types passed to {@link ActionRegistry#enumLabel} or {@link ActionRegistry#enums}; add a type here when a new call site labels it. */
    static final List<Enum<?>[]> LABELLED_ENUMS = List.of(
            ProgressionPredicate.Kind.values(), CampaignState.Goal.values(), CampaignState.Sovereignty.values(),
            CampaignState.Phase.values(), CampaignState.TreatyPhase.values(), MobilizationDoctrine.values(),
            ProtectionState.Duty.values(), ProtectionState.Phase.values(), ProtectionState.Status.values(),
            EvolutionState.Phase.values(), EvolutionState.Outcome.values(), RecruitTransferData.Phase.values(),
            DramaState.Kind.values(), DramaState.Phase.values(), OrganizationLifecycleView.State.values(),
            OrganizationMergeView.State.values(), OrganizationLifecycleResult.Status.values(), Politics.Permission.values(),
            Politics.State.values(), Politics.AgreementState.values(), Politics.PetitionState.values(), Politics.Clause.values(),
            AgreementClauseView.Status.values(), PoliticalTransition.ElectionState.values(), PoliticalFact.Correction.values(),
            InstitutionView.Status.values(), CampaignState.Relation.values(), MobilizationSavedData.Phase.values(),
            OrganizationMembershipSnapshot.Status.values(), OrganizationExplanation.Decision.values(),
            dev.otectus.mcakingdoms.document.DocumentLedger.Kind.values(), dev.otectus.mcakingdoms.compat.capitals.CapitalsSavedData.Status.values(),
            dev.otectus.mcakingdoms.politics.dynasty.DynastySavedData.Status.values(), dev.otectus.mcakingdoms.politics.dynasty.DynastySavedData.Cause.values(),
            dev.otectus.mcakingdoms.politics.dynasty.DynastySavedData.ClaimKind.values(), dev.otectus.mcakingdoms.politics.dynasty.DynastySavedData.ClaimState.values(),
            dev.otectus.mcakingdoms.politics.dynasty.DynastySavedData.TitleState.values(), dev.otectus.mcakingdoms.politics.dynasty.Lineage.Relation.values(),
            dev.otectus.mcakingdoms.politics.dynasty.Lineage.Stage.values(), dev.otectus.mcakingdoms.politics.dynasty.Legitimacy.Level.values(),
            dev.otectus.mcakingdoms.development.DevelopmentMode.values(), dev.otectus.mcakingdoms.development.DevelopmentSavedData.State.values(),
            dev.otectus.mcakingdoms.development.DevelopmentSavedData.Provenance.values(), dev.otectus.mcakingdoms.development.DevelopmentTemplate.Role.values(),
            dev.otectus.mcakingdoms.compat.livingvillages.LvState.Term.values(), dev.otectus.mcakingdoms.compat.livingvillages.LvState.TreatyStatus.values(),
            dev.otectus.mcakingdoms.compat.livingvillages.LvState.BindingStatus.values(), dev.otectus.mcakingdoms.compat.livingvillages.LvState.OperationKind.values());
    private static final List<String> LOCALES = List.of("en_us", "es_es", "zh_cn");

    private static JsonObject lang(String locale) throws IOException {
        return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/mcakingdoms/lang/" + locale + ".json")))
                .getAsJsonObject();
    }

    /** Key to English fallback for every catalogue string. */
    private static Map<String, String> catalogue() {
        InteractionTasks.init();
        Map<String, String> keys = new LinkedHashMap<>();
        for (ActionRegistry.Task task : ActionRegistry.all()) {
            keys.put(task.titleKey(), task.title());
            keys.put(task.helpKey(), task.help());
            keys.put(task.categoryKey(), task.category());
            for (ActionRegistry.Field field : task.fields()) keys.put("task.mcakingdoms." + task.id() + ".field." + field.key(), field.label());
        }
        for (Enum<?>[] values : LABELLED_ENUMS)
            for (Enum<?> value : values) keys.put(ActionRegistry.enumKey(value), ActionRegistry.words(value.name()));
        for (PoliticalFact.Type type : PoliticalFact.Type.values())
            keys.put("politics.mcakingdoms.fact." + type.name().toLowerCase(Locale.ROOT), ActionRegistry.words(type.name()));
        for (Unavailable cause : Unavailable.values()) keys.put("unavailable.mcakingdoms." + cause.name().toLowerCase(Locale.ROOT), null);
        return keys;
    }

    @Test
    void everyCatalogueStringIsInEveryBundledLanguage() throws IOException {
        Map<String, String> keys = catalogue();
        Set<String> missing = new TreeSet<>();
        for (String locale : LOCALES) {
            JsonObject lang = lang(locale);
            for (String key : keys.keySet()) if (!lang.has(key)) missing.add(locale + ": " + key);
        }
        assertTrue(missing.isEmpty(), missing.size() + " untranslated: " + missing);
    }

    /** Task titles, help and field labels: the code fallback and the English entry are one text, kept in step. Enum words may be refined in English. */
    @Test
    void englishTaskTextMatchesTheCodeFallbacks() throws IOException {
        JsonObject english = lang("en_us");
        List<String> drift = new ArrayList<>();
        catalogue().forEach((key, fallback) -> {
            if (key.startsWith("task.mcakingdoms.") && fallback != null && english.has(key) && !english.get(key).getAsString().equals(fallback))
                drift.add(key + ": lang \"" + english.get(key).getAsString() + "\" vs code \"" + fallback + "\"");
        });
        assertTrue(drift.isEmpty(), "English text differs from the code fallback: " + drift);
    }

    private static final java.util.regex.Pattern FORMAT = java.util.regex.Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%])");

    /** The argument positions a text reads, as Minecraft counts them: {@code %s} takes the next one, {@code %2$s} names one. */
    static Set<Integer> arguments(String value) {
        Set<Integer> used = new TreeSet<>();
        int next = 0;
        var matcher = FORMAT.matcher(value);
        while (matcher.find()) {
            if (matcher.group(2).equals("%")) continue;
            used.add(matcher.group(1) == null ? ++next : Integer.parseInt(matcher.group(1)));
        }
        return used;
    }

    /** A translation that reads other arguments than English shows the raw key, or drops a name or number the player needs. */
    @Test
    void translationsReadTheSameArgumentsAsEnglish() throws IOException {
        JsonObject english = lang("en_us");
        Set<String> problems = new TreeSet<>();
        for (String locale : LOCALES.subList(1, LOCALES.size())) {
            JsonObject other = lang(locale);
            for (String key : english.keySet()) {
                if (!other.has(key)) continue;
                var wanted = arguments(english.get(key).getAsString());
                var found = arguments(other.get(key).getAsString());
                if (!wanted.equals(found)) problems.add(locale + " " + key + ": English reads " + wanted + ", the translation " + found);
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " mismatches: " + problems);
    }

    @Test
    void bundledLanguagesHaveTheSameKeys() throws IOException {
        Set<String> english = lang("en_us").keySet();
        Set<String> problems = new TreeSet<>();
        for (String locale : LOCALES.subList(1, LOCALES.size())) {
            Set<String> other = lang(locale).keySet();
            english.stream().filter(key -> !other.contains(key)).forEach(key -> problems.add(locale + " lacks " + key));
            other.stream().filter(key -> !english.contains(key)).forEach(key -> problems.add(locale + " has extra " + key));
        }
        assertTrue(problems.isEmpty(), problems.toString());
    }
}
