package dev.otectus.mcakingdoms.api.politics;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who stands on one side of an agreement. A {@link Kingdom} is the government of one civic kingdom, as every existing
 * political record names it; a {@link Realm} is a sovereign Recruits: Living Villages faction, which may share a civic
 * kingdom with independent rivals. The two never stand for each other: same-culture realms are separate parties, and a
 * kingdom government represents a realm only through an explicit, recorded association.
 */
public sealed interface PoliticalPartyRef permits PoliticalPartyRef.Kingdom, PoliticalPartyRef.Realm {
    /** {@code kingdom:<id>} or {@code realm:<uuid>}, the stable form stored in records and sent to clients. */
    String format();

    record Kingdom(String kingdomId) implements PoliticalPartyRef {
        public Kingdom {
            Objects.requireNonNull(kingdomId, "kingdomId");
            if (kingdomId.isBlank() || kingdomId.length() > 128) throw new IllegalArgumentException("Invalid kingdom party");
        }

        @Override
        public String format() {
            return "kingdom:" + kingdomId;
        }
    }

    record Realm(UUID realmId) implements PoliticalPartyRef {
        public Realm {
            Objects.requireNonNull(realmId, "realmId");
        }

        @Override
        public String format() {
            return "realm:" + realmId;
        }
    }

    /** The party a {@link #format()} string names, or empty for anything else. */
    static Optional<PoliticalPartyRef> parse(String value) {
        if (value == null) return Optional.empty();
        try {
            if (value.startsWith("realm:")) return Optional.of(new Realm(UUID.fromString(value.substring(6))));
            if (value.startsWith("kingdom:")) return Optional.of(new Kingdom(value.substring(8)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
