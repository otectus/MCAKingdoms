package dev.otectus.mcakingdoms.api.event;

import dev.otectus.mcakingdoms.api.SettlementView;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class SettlementCreatedEvent extends Event {
    private final SettlementView settlement;

    public SettlementCreatedEvent(SettlementView settlement) {
        this.settlement = Objects.requireNonNull(settlement, "settlement");
    }

    public SettlementView settlement() {
        return settlement;
    }
}
