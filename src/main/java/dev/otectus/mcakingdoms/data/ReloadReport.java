package dev.otectus.mcakingdoms.data;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What one definition reload attempt actually did, domain by domain. A reload is not reported as successful because
 * its future completed: a domain that rejects content keeps its previous definitions, and the command, the reload task
 * and the server health view all describe the same attempt from this record.
 *
 * <p>Six domains validate while the reload prepares; a rejection there fails the whole reload, so nothing changes.
 * The civic network rules and the world-context selectors validate as they apply; a rejection there keeps that
 * domain's previous definitions while the rest of the reload commits. Internal only, never part of the API.
 */
public final class ReloadReport {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Problems shown to players per domain; the log keeps every problem. */
    static final int SHOWN_PROBLEMS = 3;
    /** Characters of one problem shown to players. */
    static final int PROBLEM_LENGTH = 300;

    /** Definition domains. {@code fatal}: a rejection fails the whole reload instead of only this domain. */
    public enum Domain {
        CORE(true, "Kingdom and naming definitions"),
        POLITICS(true, "Political definitions"),
        EVOLUTION(true, "Evolution scenarios"),
        DRAMA(true, "World event templates"),
        ORGANIZATIONS(true, "Organization definitions"),
        GATES(true, "Kingdom gates"),
        CIVIC(false, "Civic network rules"),
        WORLD_CONTEXT(false, "World-context selectors"),
        DYNASTY(false, "Succession laws and court ranks"),
        DEVELOPMENT(false, "Development templates");

        public final boolean fatal;
        final String english;

        Domain(boolean fatal, String english) {
            this.fatal = fatal;
            this.english = english;
        }

        public Component label() {
            return Component.translatableWithFallback("reload.mcakingdoms.domain." + name().toLowerCase(Locale.ROOT), english);
        }
    }

    public enum State { IN_PROGRESS, ACCEPTED, PARTIAL, FAILED }

    /** One domain's outcome: accepted counts, or the problems that rejected it. */
    public record Outcome(Domain domain, boolean accepted, Map<String, Integer> counts, List<String> problems) {
        public Outcome {
            counts = Map.copyOf(counts);
            problems = List.copyOf(problems);
        }
    }

    /** One reload attempt, from the listener registration that starts it to its commit or failure. */
    public static final class Attempt {
        private final long id;
        private final Set<Domain> activeBefore;
        private final long started = System.currentTimeMillis();
        private final Map<Domain, Outcome> outcomes = new EnumMap<>(Domain.class);
        private volatile boolean committed;
        private volatile String failure = "";

        private Attempt(long id, Set<Domain> activeBefore) {
            this.id = id;
            this.activeBefore = Set.copyOf(activeBefore);
        }

        public long id() { return id; }
        /** True when no definitions had been committed before this attempt, so nothing could be retained. */
        public boolean initial() { return activeBefore.isEmpty(); }
        /** Whether the domain had committed definitions before this attempt, which a rejection then keeps. */
        public boolean hadDefinitions(Domain domain) { return activeBefore.contains(domain); }
        public long startedMillis() { return started; }
        public boolean committed() { return committed; }
        public String failure() { return failure; }

        public synchronized Map<Domain, Outcome> outcomes() { return Map.copyOf(outcomes); }

        public synchronized State state() {
            boolean fatal = outcomes.values().stream().anyMatch(o -> !o.accepted() && o.domain().fatal);
            if (fatal || !failure.isEmpty()) return State.FAILED;
            if (!committed) return State.IN_PROGRESS;
            return outcomes.values().stream().anyMatch(o -> !o.accepted()) ? State.PARTIAL : State.ACCEPTED;
        }

        private synchronized void record(Outcome outcome) {
            // A domain reports once per attempt; a later report (apply after prepare) replaces the earlier one.
            outcomes.put(outcome.domain(), outcome);
        }
    }

    private static final AtomicLong IDS = new AtomicLong();
    private static volatile Attempt current;
    /** Domains with committed definitions since the last reset; only these can keep definitions after a rejection. */
    private static final Set<Domain> ACTIVE = java.util.Collections.synchronizedSet(EnumSet.noneOf(Domain.class));

    private ReloadReport() {
    }

    /** Starts a new attempt. Called when Minecraft asks for this mod's reload listeners, once per reload. */
    public static Attempt begin() {
        Attempt previous = current;
        if (previous != null && previous.state() == State.IN_PROGRESS) {
            // Nothing committed it: the reload failed somewhere this mod does not see (for example another mod's listener).
            previous.failure = "the reload did not finish; see the server log";
        }
        Attempt attempt;
        synchronized (ACTIVE) { attempt = new Attempt(IDS.incrementAndGet(), Set.copyOf(ACTIVE)); }
        current = attempt;
        return attempt;
    }

    public static void accepted(Domain domain, Map<String, Integer> counts) {
        Attempt attempt = current;
        if (attempt != null) attempt.record(new Outcome(domain, true, counts, List.of()));
    }

    /** Records a rejection and logs every problem; players see a bounded selection. */
    public static void rejected(Domain domain, List<String> problems) {
        Attempt attempt = current;
        List<String> listed = problems.isEmpty() ? List.of("rejected without a recorded reason") : problems;
        boolean retained = attempt == null || attempt.hadDefinitions(domain);
        for (String problem : listed) {
            LOGGER.error("[MCA: Kingdoms] {} rejected during reload: {}. {}", domain.english, problem,
                    !domain.fatal ? (retained ? "The previous " + domain.english.toLowerCase(Locale.ROOT) + " remain active."
                            : "No earlier " + domain.english.toLowerCase(Locale.ROOT) + " were active, so this domain has none.")
                            : retained ? "The whole reload is refused; every domain keeps its previous definitions."
                            : "No earlier definitions were loaded, so this world cannot use this datapack set.");
        }
        if (attempt != null) attempt.record(new Outcome(domain, false, Map.of(), listed));
    }

    /** The reload's definitions became active (datapack sync after a reload, or server start). */
    public static void committed() {
        Attempt attempt = current;
        if (attempt == null || attempt.committed) return;
        attempt.committed = true;
        attempt.outcomes().values().stream().filter(Outcome::accepted).map(Outcome::domain).forEach(ACTIVE::add);
        LOGGER.info("[MCA: Kingdoms] Definition reload {}: {}", attempt.id, plain(attempt));
    }

    /** The reload future failed; records the cause when no domain explained it. */
    public static void failed(Attempt attempt, Throwable error) {
        if (attempt == null || attempt.committed) return;
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()
                && (cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        attempt.failure = bounded(String.valueOf(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
    }

    public static Optional<Attempt> latest() {
        return Optional.ofNullable(current);
    }

    public static long latestId() {
        Attempt attempt = current;
        return attempt == null ? 0L : attempt.id;
    }

    /** The first attempt that began after {@code previousId}, when it is still the latest one. */
    public static Optional<Attempt> since(long previousId) {
        Attempt attempt = current;
        return attempt != null && attempt.id > previousId ? Optional.of(attempt) : Optional.empty();
    }

    /** Server stopped: the next start loads with no previous definitions. */
    public static void reset() {
        current = null;
        ACTIVE.clear();
    }

    /** Player-facing description, as translatable paragraphs. {@code error} is the reload future's failure, if any. */
    public static List<Component> describe(Optional<Attempt> found, Throwable error) {
        List<Component> lines = new ArrayList<>();
        if (found.isEmpty()) {
            lines.add(error == null
                    ? tr("unobserved", "The reload finished without reading MCA: Kingdoms definitions. Their current state is unchanged.")
                    : tr("failed.before", "The reload failed before MCA: Kingdoms definitions were read: %s. Their current state is unchanged.",
                    bounded(rootMessage(error))));
            return lines;
        }
        Attempt attempt = found.get();
        if (error != null) failed(attempt, error);
        Map<Domain, Outcome> outcomes = attempt.outcomes();
        switch (attempt.state()) {
            case ACCEPTED -> lines.add(tr("accepted", "Reload complete. Definitions in use: %s.", counts(outcomes)));
            case IN_PROGRESS -> lines.add(tr("in_progress", "The reload has not finished. Check this action again before reloading once more."));
            case PARTIAL -> {
                boolean retained = outcomes.values().stream().filter(o -> !o.accepted()).allMatch(o -> attempt.hadDefinitions(o.domain()));
                lines.add(!retained
                        ? tr("partial.initial", "Reload completed with rejected definitions; the affected domains have no definitions until a valid reload.")
                        : tr("partial", "Reload completed with rejected definitions; previous definitions remain active for the affected domains."));
                lines.add(tr("partial.accepted", "Accepted and in use: %s.", counts(outcomes)));
            }
            case FAILED -> {
                boolean explained = outcomes.values().stream().anyMatch(o -> !o.accepted() && o.domain().fatal);
                if (!explained && !attempt.failure.isEmpty()) {
                    lines.add(tr("failed.other", "Reload failed: %s. MCA: Kingdoms definitions did not change.", attempt.failure));
                } else {
                    boolean retained = outcomes.values().stream().filter(o -> !o.accepted() && o.domain().fatal).allMatch(o -> attempt.hadDefinitions(o.domain()));
                    lines.add(!retained
                            ? tr("failed.initial", "Loading failed. No earlier definitions were active, so the rejected domains have none.")
                            : tr("failed", "Reload failed. No definitions changed; the definitions that were active before remain in use."));
                }
            }
        }
        for (Outcome outcome : outcomes.values()) {
            if (outcome.accepted()) continue;
            int shown = 0;
            for (String problem : outcome.problems()) {
                if (shown == SHOWN_PROBLEMS) {
                    lines.add(tr("more", "%s: %s more problem(s) are in the server log.", outcome.domain().label(),
                            outcome.problems().size() - SHOWN_PROBLEMS));
                    break;
                }
                lines.add(tr("problem", "%s rejected — %s", outcome.domain().label(), bounded(problem)));
                shown++;
            }
            if (!outcome.domain().fatal) {
                lines.add(!attempt.hadDefinitions(outcome.domain())
                        ? tr("domain.none", "%s: no earlier definitions were active, so this domain has none until a valid reload.", outcome.domain().label())
                        : tr("domain.retained", "%s: the previous definitions remain active.", outcome.domain().label()));
            }
        }
        if (attempt.state() != State.ACCEPTED && attempt.state() != State.IN_PROGRESS) {
            lines.add(tr("next", "Fix the named files, then reload again. Datapack authors: see docs/Datapack-Reference.md."));
        }
        return lines;
    }

    /** The accepted definition counts of an attempt, for "Definitions in use: …". */
    public static Component inUse(Attempt attempt) {
        return counts(attempt.outcomes());
    }

    /** The one-line English summary used in logs and the console health view. */
    public static String plain(Attempt attempt) {
        StringBuilder text = new StringBuilder();
        for (Component line : describe(Optional.of(attempt), null)) {
            if (!text.isEmpty()) text.append(' ');
            text.append(line.getString());
        }
        return text.toString();
    }

    private static Component counts(Map<Domain, Outcome> outcomes) {
        MutableComponent joined = Component.empty();
        boolean first = true;
        for (Outcome outcome : outcomes.values()) {
            if (!outcome.accepted()) continue;
            for (var count : new LinkedHashMap<>(outcome.counts()).entrySet()) {
                if (!first) joined.append(", ");
                first = false;
                joined.append(Component.translatableWithFallback("reload.mcakingdoms.count." + count.getKey(),
                        "%s " + count.getKey().replace('_', ' '), count.getValue()));
            }
        }
        return first ? Component.translatableWithFallback("reload.mcakingdoms.count.none", "no definitions") : joined;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    static String bounded(String value) {
        String text = value == null ? "" : value.strip();
        return text.length() > PROBLEM_LENGTH ? text.substring(0, PROBLEM_LENGTH - 1) + "…" : text;
    }

    private static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("reload.mcakingdoms." + key, fallback, args);
    }
}
