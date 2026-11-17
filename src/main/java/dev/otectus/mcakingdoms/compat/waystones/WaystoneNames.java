package dev.otectus.mcakingdoms.compat.waystones;

import java.util.*;

/**
 * The naming policy for settlement waystones (WAY-01), with no provider types, so every rule is unit-tested.
 *
 * <p>The base is the settlement's display name, literal (never translated per client): control characters are removed,
 * whitespace collapsed and trimmed, and the result kept within Waystones' own name limit without splitting a surrogate
 * pair, leaving room for a suffix. A name already used by any registered waystone, or reserved by an earlier assignment,
 * gets the smallest free suffix " (2)", " (3)" and so on; names are compared ignoring case, as players read them. An
 * unusable settlement name (empty after cleaning) leaves Waystones' native default in place.
 */
public final class WaystoneNames {
    /** Waystones reads an edited name of at most this many characters (EditWaystoneMessage). */
    public static final int NATIVE_LIMIT = 255;
    /** The longest suffix a name may need: " (" + digits + ")". */
    static final int SUFFIX_ROOM = 8;
    /** The most suffixes tried before giving up and keeping the native name. */
    static final int SUFFIX_LIMIT = 9_999;

    private WaystoneNames() {
    }

    /** The settlement name as a waystone may carry it, or empty when nothing usable remains. */
    public static Optional<String> base(String settlementName) {
        if (settlementName == null) return Optional.empty();
        var out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < settlementName.length(); ) {
            int cp = settlementName.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) { space = out.length() > 0; continue; }
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT || cp == 0xFFFD) continue;
            if (space) { out.append(' '); space = false; }
            out.appendCodePoint(cp);
        }
        String clean = truncate(out.toString(), NATIVE_LIMIT - SUFFIX_ROOM);
        return clean.isBlank() ? Optional.empty() : Optional.of(clean.strip());
    }

    /** At most {@code limit} UTF-16 characters, never ending inside a surrogate pair. */
    static String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = limit;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    /** A name as collisions compare it: trimmed, ignoring case. */
    static String key(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }

    /** The name {@code n} of a base: the base itself for 1, otherwise "base (n)". */
    public static String numbered(String base, int n) {
        return n <= 1 ? base : base + " (" + n + ")";
    }

    /**
     * The first free name for {@code base} and its number, given every name already taken (other stones' current names
     * and names reserved by earlier assignments). Empty when no number up to {@link #SUFFIX_LIMIT} is free.
     */
    public static Optional<Assignment> assign(String base, Collection<String> taken) {
        Set<String> used = new HashSet<>();
        for (String name : taken) if (name != null) used.add(key(name));
        return assign(base, used::contains);
    }

    /**
     * As {@link #assign(String, Collection)}, with {@code used} answering for names already compared as {@link #key}
     * does, so one index of the registry serves many assignments (a backfill) instead of one full read each.
     */
    static Optional<Assignment> assign(String base, java.util.function.Predicate<String> used) {
        for (int n = 1; n <= SUFFIX_LIMIT; n++) {
            String candidate = numbered(base, n);
            if (!used.test(key(candidate))) return Optional.of(new Assignment(candidate, n));
        }
        return Optional.empty();
    }

    /** A name given to a stone, and its number (1 for the plain base). Kept, so a restart never renumbers it. */
    public record Assignment(String name, int number) { }

    /** Why a stone keeps the name it has (or Waystones' default), for status and operator lists. */
    public enum Reason {
        NAMED,
        OUTSIDE_SETTLEMENT,
        WAITING_FOR_DISCOVERY,
        PLAYER_NAME_KEPT,
        NOT_A_WAYSTONE,
        UNUSABLE_NAME,
        NO_FREE_NAME,
        PROVIDER_UNSUPPORTED,
        RECORDS_READ_ONLY,
        PENDING_FULL,
        UNRESOLVED
    }
}
