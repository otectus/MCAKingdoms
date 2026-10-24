package dev.otectus.mcakingdoms.api;

import net.minecraft.world.entity.Entity;

import java.util.Optional;

@FunctionalInterface
public interface CivicEvidenceProvider {
    Optional<CivicIdentityHint> observe(Entity entity);
}
