package dev.otectus.mcakingdoms.compat.townstead;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * A scalar copy of one Townstead change event (spec 5.4): no entity, level or snapshot reference survives into a queue.
 * {@code dimension} is null and {@code villageId} -1 when the event names no village; {@code subject} is the villager,
 * faction or other id the event is about, or null; {@code settlement} is the MCA: Kingdoms residence of the villager an
 * event is about, read from Kingdoms' own civic record when the event fired, or null.
 */
public record TownsteadSignal(Kind kind, ResourceLocation dimension, int villageId, UUID subject, String detail, UUID settlement) {
    public enum Kind {
        /** A building was established, upgraded or removed. */
        BUILDINGS,
        SPIRIT,
        /** A village's aggregate need crossed a band. */
        NEEDS,
        /** A villager claimed a home in a village (no server on the event; verify against current records). */
        RESIDENT_HOME,
        /** The world's settings or system switches changed. */
        SETTINGS,
        WORKSITES,
        /** A unit of work was done: an invalidation and narrative hint, never a receipt. */
        WORK_DONE,
        /** A settlement's founding was recorded. */
        FOUNDED,
        /** A faction, bond, Seat or legitimacy change. */
        POLITICS,
        /** Birth, death, marriage, life stage, root, profession or tier. */
        LIFE,
        /** A Chronicle event was recorded. */
        CHRONICLE
    }

    public TownsteadSignal(Kind kind, ResourceLocation dimension, int villageId, UUID subject, String detail) {
        this(kind, dimension, villageId, subject, detail, null);
    }

    public TownsteadSignal {
        Objects.requireNonNull(kind, "kind");
        detail = detail == null ? "" : detail;
        if (detail.length() > 128) detail = detail.substring(0, 128);
    }

    public boolean village() {
        return dimension != null && villageId >= 0;
    }
}
