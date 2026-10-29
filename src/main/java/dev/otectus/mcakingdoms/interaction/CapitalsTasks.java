package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.compat.capitals.CapitalsInterop;
import dev.otectus.mcakingdoms.compat.capitals.CapitalsReport;
import dev.otectus.mcakingdoms.compat.capitals.CapitalsSavedData;
import net.minecraft.network.chat.Component;

import java.util.*;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/** Operator tasks for MCA Capitals co-install (C3): the dry-run report, accepting a capital, and releasing a mapping. */
final class CapitalsTasks {
    private CapitalsTasks() {
    }

    static void init() {
        targets("capital_governing", CapitalsTasks::governing);
        targets("capital_mapping", CapitalsTasks::mappings);
        targetHint("capital_governing", tr("politics.mcakingdoms.capitals.hint.governing", "MCA Capitals has no governing capital now, or it is not installed."));
        targetHint("capital_mapping", tr("politics.mcakingdoms.capitals.hint.mapping", "No MCA Capitals capital is mapped."));
        add(new Task("capitals.report", "Administration", "Review MCA Capitals mappings",
                "Shows each MCA Capitals capital, the kingdom its village belongs to, the mapping now and the one the evidence supports, and what becomes read-only. Changes nothing.",
                true, false, List.of(), c -> "", c -> CapitalsReport.lines(c.server()).stream().map(line -> (Object) line).toList()));
        add(new Task("capitals.accept", "Administration", "Accept an MCA Capitals capital as a kingdom's crown",
                "MCA Capitals then governs that kingdom's crown; any other capital in the kingdom is quarantined. MCA Capitals itself is not changed.",
                true, true, List.of(pick("capital", "Capital", "capital_governing")), CapitalsTasks::version,
                c -> CapitalsInterop.get(c.server()).accept(c.player.getUUID(), c.uuid("capital"))));
        add(new Task("capitals.release", "Administration", "Release an MCA Capitals mapping",
                "Returns a kingdom's crown to MCA: Kingdoms after its capital stopped governing or MCA Capitals was removed. Refused while the capital still governs.",
                true, true, List.of(pick("capital", "Mapping", "capital_mapping")), CapitalsTasks::version,
                c -> CapitalsInterop.get(c.server()).release(c.player.getUUID(), c.uuid("capital"))));
    }

    private static String version(Context c) {
        return CapitalsSavedData.get(c.server()).revision() + ":" + CapitalsInterop.get(c.server()).capitals().hashCode();
    }

    private static List<Choice> governing(Context c) {
        var interop = CapitalsInterop.get(c.server());
        var out = new ArrayList<Choice>();
        for (var capital : interop.capitals()) {
            if (!capital.governing()) continue;
            Component where = interop.settlement(capital).<Component>map(s -> tr("politics.mcakingdoms.capitals.report.where", "%s in %s", s.displayName(), Names.kingdomName(c.server(), s.kingdomId().toString())))
                    .orElse(tr("politics.mcakingdoms.capitals.report.unrecognized", "a village MCA: Kingdoms has not recognized yet"));
            out.add(Choice.of(capital.id().toString(), tr("politics.mcakingdoms.capitals.choice", "Capital #%s", capital.id().toString().substring(0, 4)), where));
        }
        return out;
    }

    private static List<Choice> mappings(Context c) {
        var out = new ArrayList<Choice>();
        for (var binding : CapitalsSavedData.get(c.server()).bindings().values()) {
            if (binding.status() == CapitalsSavedData.Status.RELEASED) continue;
            out.add(Choice.of(binding.capital().toString(), tr("politics.mcakingdoms.capitals.choice", "Capital #%s", binding.capital().toString().substring(0, 4)),
                    tr("politics.mcakingdoms.capitals.mapping", "%s · %s", Names.kingdomName(c.server(), binding.kingdom()), enumLabel(binding.status()))));
        }
        return out;
    }

}
