package dev.otectus.mcakingdoms.api.politics;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** Trusted server read. Player-facing consumers must enforce discovery before disclosing location. */
public record InstitutionView(UUID id, ResourceLocation kingdom, UUID settlement,
                              ResourceLocation dimension, BlockPos position, String type,
                              String titleKey, Status status, long revision) {
    /** Appended in 0.3.0: {@code BUILDING_INCOMPLETE}, a recognized building MCA records as no longer complete. */
    public enum Status { AVAILABLE, UNLOADED, PROVIDER_UNAVAILABLE, SUSPENDED, DEFINITION_UNAVAILABLE, BUILDING_CHANGED, BUILDING_INCOMPLETE }
    public InstitutionView { position = position.immutable(); }
    public boolean operational() { return status == Status.AVAILABLE; }
}
