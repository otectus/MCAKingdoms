package dev.otectus.mcakingdoms.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A settlement's public work (development project) was completed and the completion is committed. Posted once, after
 * the save; a listener's failure changes nothing. {@code project} identifies the completion for at-most-once handling.
 */
public final class PublicWorkCompletedEvent extends Event {
    private final MinecraftServer server;
    private final UUID project;
    private final UUID settlement;
    private final ResourceLocation kingdom;
    private final ResourceLocation template;
    private final UUID proposer;
    private final UUID approver;
    private final boolean built;

    public PublicWorkCompletedEvent(MinecraftServer server, UUID project, UUID settlement, ResourceLocation kingdom, ResourceLocation template,
                                    UUID proposer, UUID approver, boolean built) {
        this.server = Objects.requireNonNull(server, "server");
        this.project = Objects.requireNonNull(project, "project");
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.kingdom = Objects.requireNonNull(kingdom, "kingdom");
        this.template = Objects.requireNonNull(template, "template");
        this.proposer = Objects.requireNonNull(proposer, "proposer");
        this.approver = approver;
        this.built = built;
    }

    public MinecraftServer server() { return server; }
    public UUID project() { return project; }
    public UUID settlement() { return settlement; }
    public ResourceLocation kingdom() { return kingdom; }
    public ResourceLocation template() { return template; }
    public UUID proposer() { return proposer; }
    public Optional<UUID> approver() { return Optional.ofNullable(approver); }
    /** True when blocks were placed; false for a work completed by its deliveries alone. */
    public boolean built() { return built; }
}
