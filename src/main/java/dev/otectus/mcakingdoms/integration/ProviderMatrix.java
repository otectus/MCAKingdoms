package dev.otectus.mcakingdoms.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.*;

/**
 * The provider matrix (handoff 8.4), logged once each time a server starts and printed by {@code /kingdoms integrations}:
 * for each provider in a fixed order, the installed version, the range or exact build this mod is tested with, the
 * capabilities its probes found, the effects that are on, who owns the domain, and anything the probes could not read.
 * Installed is not usable: a capability listed here was probed, not assumed from a version string.
 */
public final class ProviderMatrix {
    private ProviderMatrix() {
    }

    public record Row(String mod, String installed, String tested, String capabilities, String effects, String owner, String diagnostic) {
        String line() { return String.format(Locale.ROOT, "%-16s installed %-14s tested %-28s capabilities [%s] effects [%s] owner: %s%s",
                mod, installed, tested, capabilities, effects, owner, diagnostic.isEmpty() ? "" : "; " + diagnostic); }
    }

    private static String version(String mod) {
        return ModList.get().getModContainerById(mod).map(m -> m.getModInfo().getVersion().toString()).orElse("absent");
    }

    /** The range this mod declares for {@code mod} in its own mods.toml; "optional" when not declared. */
    private static String declared(String mod) {
        return ModList.get().getModContainerById("mcakingdoms").flatMap(self -> self.getModInfo().getDependencies().stream()
                .filter(d -> d.getModId().equals(mod)).findFirst().map(d -> d.getVersionRange().toString())).orElse("optional");
    }

    public static List<Row> rows(MinecraftServer server) {
        var rows = new ArrayList<Row>();
        rows.add(new Row("minecraft", version("minecraft"), declared("minecraft"), "", "", "Mojang", ""));
        rows.add(new Row("forge", version("forge"), declared("forge"), "", "", "Forge", ""));
        String mca = version("mca");
        rows.add(new Row("mca", mca, declared("mca"), mca.equals("absent") ? "" : "family records, villages, life stages", "civic identity, dynasty lineage (read only)",
                "MCA Reborn owns people, families, villages", ""));
        String townstead = version("townstead");
        String townsteadCaps = "";
        try { townsteadCaps = String.join(", ", dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi.get(server).capabilities().stream().map(Enum::name).sorted().toList()); }
        catch (RuntimeException unavailable) { townsteadCaps = "unreadable"; }
        rows.add(new Row("townstead", townstead, declared("townstead"), townsteadCaps, "life stages, buildings (read only)", "Townstead owns schedules, needs, buildings", ""));
        rows.add(new Row("mcaquests", version("mcaquests"), declared("mcaquests"), "completion receipts: " + dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.capability(),
                "guild credit, paid workshop jobs", "MCA Quests owns objectives and rewards", ""));
        rows.add(new Row("mcareputation", version("mcareputation"), declared("mcareputation"), "", "institutional standing outbox", "MCA Reputation owns personal opinions", ""));
        rows.add(new Row("mcacrime", version("mcacrime"), declared("mcacrime"), "institutional legality: " + dev.otectus.mcakingdoms.compat.crime.InstitutionalCrimeBridge.providerPresent(),
                "workshop legality checks", "MCA Crime owns cases and enforcement", ""));
        String recruits = version("recruits");
        boolean exact = recruits.equals("1.15.2");
        rows.add(new Row("recruits", recruits, "exactly 1.15.2", recruits.equals("absent") ? "" : exact ? "native units, sieges, claims" : "none (version gate)",
                exact && dev.otectus.mcakingdoms.warfare.WarfareConfig.enabled() ? "military bridge" : "off", "Recruits owns units and combat",
                recruits.equals("absent") || exact ? "" : "only Recruits 1.15.2 is validated; the military bridge stays off"));
        String villages = version(dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.MOD_ID);
        String villageCaps = "", villageNote = "";
        boolean bridge = false;
        if (!villages.equals("absent")) {
            var snapshot = dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.snapshot(server);
            villageCaps = String.join(", ", snapshot.capabilities().stream().filter(dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.Status::ready)
                    .map(status -> status.capability().name().toLowerCase(Locale.ROOT)).toList());
            bridge = dev.otectus.mcakingdoms.compat.livingvillages.LvRuntime.get(server).isPresent();
            villageNote = snapshot.overall() == dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.Readiness.READY ? ""
                    : snapshot.overall().name().toLowerCase(Locale.ROOT) + ": " + snapshot.of(dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.Capability.STABLE_BINDING).reason()
                    + " (gate " + snapshot.gate() + ")";
        }
        rows.add(new Row(dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.MOD_ID, villages, "exactly 1.1.0 (audited jar) with Recruits 1.15.2", villageCaps,
                bridge ? "bindings, realms, war preflight, agreements, residents" : "off", "Living Villages owns its villages, economy, armies and combat", villageNote));
        rows.add(new Row("mcaconversations", version("mcaconversations"), declared("mcaconversations"), "", "kingdom gates for dialogue", "MCA Conversations owns dialogue", ""));
        String capitals = version(dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.MOD_ID);
        String capitalsCaps = "", capitalsNote = "";
        if (!capitals.equals("absent")) {
            var caps = dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.capabilities();
            capitalsCaps = caps.readable() ? "read capitals" : "unreadable";
            capitalsNote = caps.readable() ? "" : "crown actions stay read-only";
        }
        rows.add(new Row("mcacapitals", capitals, String.join(" or ", dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.TESTED_VERSIONS.stream().sorted().toList()), capitalsCaps,
                capitals.equals("absent") ? "" : "interop " + dev.otectus.mcakingdoms.config.McaKingdomsConfig.CAPITALS_INTEROP.get().name().toLowerCase(Locale.ROOT),
                "MCA Capitals owns the crowns of its governing capitals (never written)", capitalsNote));
        rows.add(new Row("mcakingdoms", version("mcakingdoms"), "", "",
                "dynasty " + dev.otectus.mcakingdoms.politics.dynasty.DynastyConfig.enabled() + ", heraldry " + dev.otectus.mcakingdoms.heraldry.HeraldryConfig.enabled()
                        + ", development " + dev.otectus.mcakingdoms.development.DevelopmentMode.current().name().toLowerCase(Locale.ROOT)
                        + ", warfare " + dev.otectus.mcakingdoms.warfare.WarfareConfig.enabled(), "MCA: Kingdoms owns settlements, governments, records", ""));
        return rows;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void started(ServerStartedEvent event) {
        try {
            var logger = LogUtils.getLogger();
            logger.info("[MCA: Kingdoms] Provider matrix (installed, tested, probed capabilities, effects on, owner):");
            for (var row : rows(event.getServer())) logger.info("[MCA: Kingdoms]   {}", row.line());
        } catch (RuntimeException failure) {
            LogUtils.getLogger().warn("[MCA: Kingdoms] The provider matrix could not be built: {}", failure.toString());
        }
    }
}
