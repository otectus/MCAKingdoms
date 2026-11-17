package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Blocker;
import dev.otectus.mcakingdoms.interaction.Keyed;
import net.minecraft.network.chat.Component;

/** The words for a structured blocker (AUD-05): records keep the cause, position and evidence; each reader sees them in their language. */
public final class DevelopmentText {
    private static final String B = DevelopmentService.P + "blocker.";

    private DevelopmentText() {
    }

    /** The blocker as a stored Keyed text (key, English, arguments), the form work reasons are kept in. */
    public static String blocker(Blocker b) {
        String pos = b.pos() == null ? "" : b.pos().toShortString();
        return switch (b.cause()) {
            case DIMENSION -> Keyed.text(B + "dimension", "Construction is not allowed in %s on this server.", b.evidence());
            case OTHER_DIMENSION -> Keyed.text(B + "other_dimension", "The plot is not in the settlement's dimension.");
            case BORDER -> Keyed.text(B + "border", "Part of the plot is outside the world.");
            case UNLOADED -> Keyed.text(B + "unloaded", "Part of the plot is not loaded; stand near it and survey again.");
            case OUTSIDE -> Keyed.text(B + "outside", "Part of the plot is outside %s.", b.evidence());
            case HOME -> Keyed.text(B + "home", "The plot is within a player's protected home.");
            case BUILDING -> Keyed.text(B + "building", "The plot overlaps a recognized village building.");
            case BUILDINGS_UNREADABLE -> Keyed.text(B + "buildings_unreadable",
                    "The settlement's recognized buildings cannot be read right now, so construction waits instead of assuming the plot is clear.");
            case GROUND -> Keyed.text(B + "ground", "A ground work needs natural ground with air above it at %s.", pos);
            case OCCUPIED -> Keyed.text(B + "occupied", "%s is occupied.", pos);
            case FOREIGN_MATCH -> Keyed.text(B + "foreign_match",
                    "%s holds a block like this work's that this work did not place; it is left alone. Remove it, or cancel the work.", pos);
            case SUPPORT -> Keyed.text(B + "support", "%s has nothing to stand on.", pos);
            case BLOCK_ENTITY -> Keyed.text(B + "block_entity", "Storage or machinery is next to %s.", pos);
            case BED -> Keyed.text(B + "bed", "A bed is next to %s.", pos);
            case FARMLAND -> Keyed.text(B + "farmland", "Farmland is next to %s.", pos);
            case DOOR -> Keyed.text(B + "door", "A door or gate is next to %s.", pos);
            case REDSTONE -> Keyed.text(B + "redstone", "A redstone component is next to %s.", pos);
            case POI -> Keyed.text(B + "poi", "A workstation, bed or bell is within 2 blocks of %s.", pos);
            case PROTECTED -> Keyed.text(B + "protected", "The land at %s is protected from the approving official's building.", pos);
            case CHANGED -> Keyed.text(B + "changed", "%s changed since the survey; nothing there is replaced.", pos);
            case UNSUPPORTED -> Keyed.text(B + "unsupported", "%s cannot stand where it was planned; nothing there was placed.", pos);
            case REFUSED -> Keyed.text(B + "refused", "The world did not accept the block at %s, so it does not count as built.", pos);
            case CONSENT -> Keyed.text(B + "consent",
                    "The settlement no longer allows construction. An official allows it again with Allow construction in a settlement, then resumes the work.");
            case AUTHORITY -> Keyed.text(B + "authority",
                    "Whoever approved it no longer holds the Appoint officials responsibility in %s (or operator rights); an official there resumes it.", b.evidence());
            case MODE -> Keyed.text(B + "mode", "The server's development mode no longer allows construction; an official resumes it once it does.");
            case KINGDOM_CHANGED -> Keyed.text(B + "kingdom_changed", "The settlement now belongs to %s; an official there reviews and resumes the work.", b.evidence());
            case INHERITED -> Keyed.text(B + "inherited", "Inherited when %s merged into this settlement; an official reviews and resumes it.", b.evidence());
            case TEMPLATE_MISSING -> Keyed.text(B + "template_missing", "The work is no longer defined on this server.");
            case LEVEL_MISSING -> Keyed.text(B + "level_missing", "The work's dimension is not available.");
        };
    }

    public static Component component(Blocker b) {
        return Keyed.component(blocker(b));
    }
}
