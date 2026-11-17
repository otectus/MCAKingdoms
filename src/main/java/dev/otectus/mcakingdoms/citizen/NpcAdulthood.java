package dev.otectus.mcakingdoms.citizen;

import dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadLifeStageView;
import dev.otectus.mcakingdoms.compat.mca.McaFamilyEvidence;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one adulthood rule shared by political appointments, guild contacts and dynasty checks (spec 8.3), so they never
 * disagree about the same NPC. With Townstead installed, an MCA villager's stage is its Root's own life stage and the
 * canonical stage it presents as ({@code adult}/{@code senior} are adult; {@code baby}/{@code child}/{@code teen} are
 * minors), whatever the Root calls its stages and whether or not it ages; anything Townstead cannot answer is
 * {@link Verdict#UNKNOWN}, never assumed adult. Without Townstead, MCA's own age state, then vanilla's baby flag.
 * Answers are cached for a short time per villager and dropped when Townstead reports a life change.
 */
public final class NpcAdulthood {
    public enum Verdict { ADULT, MINOR, UNKNOWN }

    /** Why the verdict is what it is: a short reason id, the life stage read (if any) and the source. */
    public record Result(Verdict verdict, Reason reason, String stage) {
        public boolean adult() {
            return verdict == Verdict.ADULT;
        }
    }

    public enum Reason {
        /** Townstead's Root life stage presents as adult or senior, or as a minor stage. */
        TOWNSTEAD_STAGE,
        /** MCA's own age state. */
        MCA_AGE,
        /** Vanilla's baby flag. */
        VANILLA_AGE,
        NOT_LOADED,
        /** Townstead's villager or Root data cannot be read on this server. */
        TOWNSTEAD_UNREADABLE,
        /** Townstead has no snapshot for this villager. */
        TOWNSTEAD_UNAVAILABLE,
        /** The villager's stage is not one its Root defines, or presents as something unrecognized. */
        STAGE_UNRECOGNIZED,
        /** Not a kind of NPC whose age can be verified. */
        UNSUPPORTED_KIND
    }

    private static final int CACHE = 512;
    private static final long TTL_TICKS = 100;
    private static final Map<UUID, Cached> CACHE_BY_ID = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Cached> eldest) { return size() > CACHE; }
    };

    private record Cached(Result result, MinecraftServer server, long at) {
    }

    private NpcAdulthood() {
    }

    /** Drops a cached answer, for example after Townstead reports a birthday, a death or a Root change. */
    public static void invalidate(UUID villager) {
        synchronized (CACHE_BY_ID) {
            CACHE_BY_ID.remove(villager);
        }
    }

    public static void clear() {
        synchronized (CACHE_BY_ID) {
            CACHE_BY_ID.clear();
        }
    }

    public static Result evaluate(MinecraftServer server, Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return new Result(Verdict.UNKNOWN, Reason.NOT_LOADED, "");
        long now = server.getTickCount();
        synchronized (CACHE_BY_ID) {
            Cached cached = CACHE_BY_ID.get(entity.getUUID());
            if (cached != null && cached.server() == server && now - cached.at() >= 0 && now - cached.at() < TTL_TICKS) return cached.result();
        }
        Result result = compute(server, entity);
        synchronized (CACHE_BY_ID) {
            CACHE_BY_ID.put(entity.getUUID(), new Cached(result, server, now));
        }
        return result;
    }

    private static Result compute(MinecraftServer server, Entity entity) {
        var type = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        boolean mca = type != null && type.getNamespace().equals("mca");
        if (mca && ModList.get() != null && ModList.get().isLoaded("townstead")) {
            try {
                var townstead = KingdomsTownsteadApi.get(server);
                if (!townstead.capabilities().contains(TownsteadCapability.READ_VILLAGER) || !townstead.capabilities().contains(TownsteadCapability.READ_ORIGIN))
                    return new Result(Verdict.UNKNOWN, Reason.TOWNSTEAD_UNREADABLE, "");
                var view = townstead.villager(entity);
                if (view.isEmpty()) return new Result(Verdict.UNKNOWN, Reason.TOWNSTEAD_UNAVAILABLE, "");
                String stage = view.get().lifeStage();
                Optional<String> presents = view.get().rootId().flatMap(townstead::origin).stream().flatMap(root -> root.lifeStages().stream())
                        .filter(candidate -> candidate.id().equalsIgnoreCase(stage)).map(TownsteadLifeStageView::presentsAs).findFirst();
                return presents.map(value -> classify(value, stage)).orElse(new Result(Verdict.UNKNOWN, Reason.STAGE_UNRECOGNIZED, stage));
            } catch (IllegalStateException notAttached) {
                return new Result(Verdict.UNKNOWN, Reason.TOWNSTEAD_UNREADABLE, "");
            }
        }
        var age = McaFamilyEvidence.ageState(entity);
        if (age.isPresent()) return switch (age.get()) {
            case "ADULT" -> new Result(Verdict.ADULT, Reason.MCA_AGE, "adult");
            case "BABY", "TODDLER", "CHILD", "TEEN" -> new Result(Verdict.MINOR, Reason.MCA_AGE, age.get().toLowerCase(Locale.ROOT));
            default -> new Result(Verdict.UNKNOWN, Reason.STAGE_UNRECOGNIZED, age.get().toLowerCase(Locale.ROOT));
        };
        if (entity instanceof Villager villager) return villager.isBaby()
                ? new Result(Verdict.MINOR, Reason.VANILLA_AGE, "baby") : new Result(Verdict.ADULT, Reason.VANILLA_AGE, "adult");
        return new Result(Verdict.UNKNOWN, Reason.UNSUPPORTED_KIND, "");
    }

    /** A canonical {@code presentsAs} word; anything else (a custom word) is not guessed at. */
    static Result classify(String presentsAs, String stage) {
        return switch (presentsAs == null ? "" : presentsAs.toLowerCase(Locale.ROOT)) {
            case "adult", "senior" -> new Result(Verdict.ADULT, Reason.TOWNSTEAD_STAGE, stage);
            case "baby", "child", "teen" -> new Result(Verdict.MINOR, Reason.TOWNSTEAD_STAGE, stage);
            default -> new Result(Verdict.UNKNOWN, Reason.STAGE_UNRECOGNIZED, stage);
        };
    }
}
