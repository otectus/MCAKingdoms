package dev.otectus.mcakingdoms.interaction;

import java.text.Normalizer;
import java.util.*;

/**
 * Predictable search for task titles, record choices and Book topics: case, accents and spacing are ignored, every
 * query word must occur (in any order), and a small explicit vocabulary lets common words find their synonyms. Matching
 * only filters what the caller is already allowed to list; it never widens a result set. Shared by server and client.
 */
public final class SearchText {
    /** Words that find one another. Kept small and explicit: a broad thesaurus would make results unpredictable. */
    private static final List<Set<String>> ALIASES = List.of(
            Set.of("village", "settlement", "town"),
            Set.of("aldea", "asentamiento", "pueblo"),
            Set.of("村庄", "聚居地", "村子"),
            Set.of("guild", "organization", "organisation"),
            Set.of("election", "vote", "ballot"),
            Set.of("agreement", "treaty", "accord"),
            Set.of("recruit", "troop", "soldier"),
            Set.of("government", "politics", "political"),
            // Player vocabulary for guild work and local branches (the plan's job/commission and branch/chapter pairs).
            Set.of("job", "commission", "quest"),
            Set.of("trabajo", "encargo", "mision"),
            Set.of("branch", "chapter"),
            Set.of("ruler", "leader", "monarch"),
            Set.of("peace", "accord", "ceasefire"));
    /**
     * Function words that carry no meaning in a goal ("why can't I join", "where am I"). They are dropped when other words
     * remain; a query made only of them must occur as a phrase instead, because single letters occur almost everywhere.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "i", "me", "my", "am", "is", "are", "be", "to", "of", "for", "in", "on", "at", "do", "does", "how",
            "what", "why", "where", "who", "can", "cant", "cannot", "t", "s", "please", "want", "need", "with", "and", "or", "this",
            "el", "la", "los", "las", "un", "una", "de", "del", "al", "en", "mi", "yo", "como", "por", "que", "puedo", "no", "y", "o");
    private static final Map<String, Set<String>> SYNONYMS = synonyms();

    /** How well a candidate matched; lower is better. {@code ALIAS} is a goal phrase written for that candidate. */
    public enum Rank { TITLE, ALIAS, TITLE_AND_CATEGORY, ANYWHERE, NONE }

    private SearchText() {
    }

    private static Map<String, Set<String>> synonyms() {
        Map<String, Set<String>> map = new HashMap<>();
        for (Set<String> group : ALIASES) for (String word : group) map.put(normalize(word), group.stream().map(SearchText::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet()));
        return Map.copyOf(map);
    }

    /** Lower-case, accent-free, single-spaced text for matching. Non-Latin scripts are kept as they are. */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", " ").strip();
    }

    /**
     * The meaningful words of a query; blank queries have none and match everything. Function words are dropped while
     * another word remains; a query of function words alone keeps them all and is matched as a phrase.
     */
    public static List<String> tokens(String query) {
        List<String> words = words(query);
        List<String> meaningful = words.stream().filter(word -> !STOP_WORDS.contains(word)).toList();
        return meaningful.isEmpty() ? words : meaningful;
    }

    private static List<String> words(String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) return List.of();
        // Punctuation separates words too ("join-guild", "join, guild"); task ids keep their dots for operators.
        // An apostrophe joins the parts of a contraction first, so "can't" is the stop word "cant".
        return Arrays.stream(normalized.replaceAll("(\\p{L})['’](\\p{L})", "$1$2").split("[\\s,;:!?¿¡()\\[\\]\"'“”‘’«»、，。：；！？（）]+"))
                .filter(word -> !word.isEmpty()).distinct().toList();
    }

    /**
     * Whether the whole query is one of the goal phrases, word for word ("why can't I join"). Such a query names a goal
     * precisely, so callers list that task before ordinary title matches of its individual words.
     */
    public static boolean phrase(String query, Collection<String> aliases) {
        String wanted = String.join(" ", words(query));
        if (wanted.isEmpty()) return false;
        for (String alias : aliases) for (String phrase : alias.split("[,，、;]"))
            if (String.join(" ", words(phrase)).equals(wanted)) return true;
        return false;
    }

    /** Whether the tokens are only function words, which must then occur together as one phrase. */
    static boolean phraseOnly(List<String> tokens) {
        return !tokens.isEmpty() && tokens.stream().allMatch(STOP_WORDS::contains);
    }

    /** Whether every token, or one of its synonyms, occurs in {@code haystack} (already normalized). */
    public static boolean matches(List<String> tokens, String haystack) {
        if (phraseOnly(tokens)) {
            String words = " " + haystack.replaceAll("[\\p{P}\\p{S}]+", " ").replaceAll("\\s+", " ").strip() + " ";
            return words.contains(" " + String.join(" ", tokens) + " ");
        }
        for (String token : tokens) if (!contains(haystack, token)) return false;
        return true;
    }

    private static boolean contains(String haystack, String token) {
        if (haystack.contains(token)) return true;
        Set<String> words = SYNONYMS.get(token);
        // A synonym is a word the player did not type, so it must begin a word: "job" finds "quests", never "request".
        if (words != null) for (String word : words) if (beginsWord(haystack, word)) return true;
        // Chinese and Japanese are written without spaces, so a typed phrase need not be one contiguous run of the
        // title: "查看聚居地" finds "查看此聚居地" when each of its characters occurs.
        if (token.codePoints().anyMatch(SearchText::ideographic) && token.codePointCount(0, token.length()) > 1) {
            return token.codePoints().allMatch(point -> !ideographic(point) || haystack.indexOf(point) >= 0)
                    && token.codePoints().noneMatch(point -> !ideographic(point));
        }
        return false;
    }

    private static boolean beginsWord(String haystack, String word) {
        // Scripts written without spaces have no word starts to look for.
        if (word.codePoints().anyMatch(SearchText::ideographic)) return haystack.contains(word);
        for (int at = haystack.indexOf(word); at >= 0; at = haystack.indexOf(word, at + 1))
            if (at == 0 || !Character.isLetterOrDigit(haystack.codePointBefore(at))) return true;
        return false;
    }

    private static boolean ideographic(int point) {
        Character.UnicodeScript script = Character.UnicodeScript.of(point);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * Ranks one candidate. Titles are the readings a player sees (several languages may be given); {@code category} and
     * {@code other} (help text, details, technical ids) are searched after them.
     */
    public static Rank rank(List<String> tokens, Collection<String> titles, Collection<String> categories, Collection<String> other) {
        return rank(tokens, titles, List.of(), categories, other);
    }

    /**
     * As {@link #rank(List, Collection, Collection, Collection)}, with goal phrases ({@code aliases}, such as "paid work"
     * or "where am I") ranked right after the title. Aliases are comma-separated phrases in any bundled language.
     */
    public static Rank rank(List<String> tokens, Collection<String> titles, Collection<String> aliases, Collection<String> categories, Collection<String> other) {
        if (tokens.isEmpty()) return Rank.TITLE;
        String title = joined(titles);
        if (matches(tokens, title)) return Rank.TITLE;
        if (!aliases.isEmpty()) {
            for (String alias : aliases) for (String phrase : alias.split("[,，、;]")) if (!phrase.isBlank() && matches(tokens, normalize(phrase))) return Rank.ALIAS;
        }
        String withCategory = title + " | " + joined(categories);
        if (matches(tokens, withCategory)) return Rank.TITLE_AND_CATEGORY;
        if (matches(tokens, withCategory + " | " + joined(other))) return Rank.ANYWHERE;
        return Rank.NONE;
    }

    private static String joined(Collection<String> values) {
        StringJoiner joiner = new StringJoiner(" | ");
        for (String value : values) if (value != null && !value.isEmpty()) joiner.add(normalize(value));
        return joiner.toString();
    }
}
