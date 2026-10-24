package dev.otectus.mcakingdoms.api.factions.event;

import dev.otectus.mcakingdoms.api.factions.FactionStandingRequest;
import dev.otectus.mcakingdoms.api.factions.FactionStandingResult;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class FactionStandingChangedEvent extends Event {
    private final FactionStandingResult result;
    private final FactionStandingRequest request;

    public FactionStandingChangedEvent(FactionStandingResult result, FactionStandingRequest request) {
        this.result = Objects.requireNonNull(result, "result");
        this.request = Objects.requireNonNull(request, "request");
    }

    public FactionStandingResult result() {
        return result;
    }

    public FactionStandingRequest request() {
        return request;
    }
}
