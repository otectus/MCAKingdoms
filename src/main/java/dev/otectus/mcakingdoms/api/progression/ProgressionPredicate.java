package dev.otectus.mcakingdoms.api.progression;

import java.util.Objects;

/**
 * Read-only qualifications. None grants faction rank or changes provider progression. The Townstead kinds read the
 * player's own Townstead career: {@code TOWNSTEAD_PROFESSION_TIER} (subject: a canonical profession id, minimum: a tier),
 * {@code TOWNSTEAD_SKILL} (subject: a skill id) and {@code TOWNSTEAD_PROFESSION_ANY} (subject: profession ids separated by
 * {@code |}, minimum: a tier). A Townstead tier is not a vanilla trade level or a guild rank.
 */
public record ProgressionPredicate(Kind kind,String subject,int minimum) {
    public enum Kind { SKILL_LEVEL, DEITY, RACE, LORE_COLLECTED, TOWNSTEAD_PROFESSION_TIER, TOWNSTEAD_SKILL, TOWNSTEAD_PROFESSION_ANY }
    public ProgressionPredicate {
        Objects.requireNonNull(kind);
        if(subject==null||subject.isBlank()||subject.length()>128||minimum<0||minimum>1_000_000)
            throw new IllegalArgumentException("Invalid progression predicate");
    }
}
