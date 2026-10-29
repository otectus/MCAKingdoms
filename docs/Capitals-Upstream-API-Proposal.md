# Proposal: a public integration API for MCA Capitals

**From:** MCA: Kingdoms (otectus). **For:** the MCA Capitals maintainer (MajesttyX). **Status:** draft for discussion,
not sent. Sending it is the project owner's decision.

MCA: Kingdoms 0.3.0 coexists with MCA Capitals by reading its public accessors (`CapitalManager.getAllCapitalsSnapshot()`
and the `CapitalRecord` getters) and deferring every crown action of a kingdom Capitals governs. That is read-only
compatibility. For players to act on a Capitals crown from Kingdoms' screens, or for either mod to react to the other's
committed events, Capitals would need a small, stable, permission-checked API. Kingdoms will not reach into private
services or item handlers to get there. This page proposes that API. It is written to be implementable without
changing Capitals' saved data.

## Principles

- Capitals stays the only writer of its records. Every operation runs Capitals' own permission and state checks.
- Operations are **idempotent** by a caller-supplied request id and **revision-checked** by an expected capital revision.
- Results are typed: applied, pending, refused (with a stable reason key) or unknown. No result is a free-form message.
- Events are posted once, after a durable commit, with a stable event id that a consumer can deduplicate.
- The API lives in a separate package (for example `com.majesttyx.mcacapitals.api`) with no references to Capitals'
  implementation classes, so other mods can compile against it alone.

## Read API

```java
public interface CapitalsApi {
    int API_VERSION = 1;
    /** The capital's id, MCA village (dimension, id), state, revision, and its public court. */
    Optional<CapitalSnapshot> capital(MinecraftServer server, UUID capitalId);
    List<CapitalSnapshot> capitals(MinecraftServer server);           // bounded, id order
    Optional<CapitalSnapshot> capitalForVillage(MinecraftServer server, ResourceLocation dimension, int villageId);
}

public record CapitalSnapshot(UUID id, ResourceLocation dimension, int villageId, State state, long revision,
                              Optional<UUID> sovereign, Optional<UUID> consort, Optional<UUID> heir, List<UUID> succession,
                              Map<UUID, String> titles, boolean mourning) { }
```

`revision` must increase on every committed change to the capital. It lets a consumer refuse a stale action, and it
lets Kingdoms invalidate cached pages exactly.

## Operation API

```java
public interface CapitalsOperations {
    /** Every operation: who acts (Capitals checks their authority), the request id, and the revision the actor saw. */
    record Actor(ServerPlayer player, UUID requestId, long expectedRevision) { }

    Result nameHeir(Actor actor, UUID capitalId, UUID heir);
    Result abdicate(Actor actor, UUID capitalId);
    Result grantTitle(Actor actor, UUID capitalId, UUID person, String title);
    Result revokeTitle(Actor actor, UUID capitalId, UUID person, String title);

    /** What an actor may do now, with Capitals' own reason keys, so another mod can grey out what Capitals would refuse. */
    Map<String, Optional<String>> availability(ServerPlayer actor, UUID capitalId);

    record Result(Status status, long revision, String reasonKey) { enum Status { APPLIED, PENDING, REFUSED, UNKNOWN } }
}
```

Replaying the same `requestId` with the same arguments must return the first result. Replaying it with different
arguments must be refused.

## Events

```java
public abstract class CapitalCommittedEvent extends Event {   // posted on the Forge bus after the save
    public final UUID eventId; public final UUID capitalId; public final long revision;
}
// SovereignChanged, HeirChanged, TitleGranted, TitleRevoked, MourningStarted, MourningEnded, CapitalFounded, CapitalDissolved
```

## Ownership handshake

```java
public interface CapitalsOwnership {
    /** Capitals declines to govern the crown of these kingdom ids (an observer mode), or returns false if it cannot. */
    boolean observe(MinecraftServer server, UUID capitalId);
}
```

With this handshake MCA: Kingdoms could offer its `KINGDOMS_PRIMARY` mode safely. Without it, Kingdoms offers only
`CAPITALS_PRIMARY` (Capitals governs) and read-only states.

## What MCA: Kingdoms would do with it

- Show Capitals' availability next to each crown action in the Kingdom Tasks, and call the operation instead of
  refusing it.
- Record each committed Capitals event once in a filtered history projection, by `eventId`, without re-announcing it.
- Use `revision` to invalidate its read-through pages exactly instead of polling.

## Tests the API should come with

Idempotent replay; a stale revision refused; an unauthorized actor refused with a reason key; events posted only after
the save; no event on a refused or replayed operation; behaviour when a capital is dissolved mid-request.
