package dev.otectus.mcakingdoms.core;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Level keys for unit tests, which run without the game bootstrap that {@code Registries} needs to initialise. */
public final class TestKeys {
    private TestKeys() {
    }

    @SuppressWarnings("unchecked")
    public static ResourceKey<Level> overworld() {
        try {
            var constructor = ResourceKey.class.getDeclaredConstructor(ResourceLocation.class, ResourceLocation.class);
            constructor.setAccessible(true);
            return (ResourceKey<Level>) constructor.newInstance(new ResourceLocation("minecraft", "dimension"),
                    new ResourceLocation("minecraft", "overworld"));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
