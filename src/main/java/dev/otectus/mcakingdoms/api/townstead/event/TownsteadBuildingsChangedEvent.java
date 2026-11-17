package dev.otectus.mcakingdoms.api.townstead.event;

import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingFingerprint;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.Event;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A settlement's buildings changed between two consistent readings. {@link #previous}/{@link #current} keep the 0.2.x
 * fingerprint; {@link #previousStates}/{@link #currentStates} add family, tier, completion and geometry, so a completion
 * or shape change with the same type and size is posted too (the legacy lists are then equal).
 */
public final class TownsteadBuildingsChangedEvent extends Event {
    private final UUID settlementId;
    private final ResourceLocation dimension;
    private final List<TownsteadBuildingFingerprint> previous;
    private final List<TownsteadBuildingFingerprint> current;
    private final List<TownsteadBuildingState> previousStates;
    private final List<TownsteadBuildingState> currentStates;

    public TownsteadBuildingsChangedEvent(UUID settlementId, ResourceLocation dimension,
                                          List<TownsteadBuildingFingerprint> previous,
                                          List<TownsteadBuildingFingerprint> current) {
        this(settlementId, dimension, previous, current, List.of(), List.of());
    }

    public TownsteadBuildingsChangedEvent(UUID settlementId, ResourceLocation dimension,
                                          List<TownsteadBuildingFingerprint> previous,
                                          List<TownsteadBuildingFingerprint> current,
                                          List<TownsteadBuildingState> previousStates,
                                          List<TownsteadBuildingState> currentStates) {
        this.settlementId = Objects.requireNonNull(settlementId, "settlementId");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.previous = List.copyOf(previous);
        this.current = List.copyOf(current);
        this.previousStates = List.copyOf(previousStates);
        this.currentStates = List.copyOf(currentStates);
    }

    public UUID settlementId() { return settlementId; }
    public ResourceLocation dimension() { return dimension; }
    public List<TownsteadBuildingFingerprint> previous() { return previous; }
    public List<TownsteadBuildingFingerprint> current() { return current; }
    /** Empty when the event was built with the 0.2.x constructor. */
    public List<TownsteadBuildingState> previousStates() { return previousStates; }
    /** Empty when the event was built with the 0.2.x constructor. */
    public List<TownsteadBuildingState> currentStates() { return currentStates; }
}
