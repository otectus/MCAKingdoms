package dev.otectus.mcakingdoms.heraldry;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The kingdom a banner, shield or noticeboard stands for (B3), and the bounded hints its renderer needs: two colours, the
 * sigil and the heraldry revision. The kingdom is fixed when the object is placed; the hints are recomputed by the server
 * whenever the object loads, so a ruler's new colours reach existing banners and a removed kingdom shows as historical.
 * It stores no government state and grants nothing.
 */
public final class HeraldicBlockEntity extends BlockEntity {
    private String kingdom = "";
    private int primary = 0xE8E2D0, secondary = 0x3B2A1E;
    private String icon = "";
    private long revision;
    private boolean historical;

    public HeraldicBlockEntity(BlockPos pos, BlockState state) {
        super(HeraldicBlocks.HERALDIC.get(), pos, state);
    }

    public String kingdom() { return kingdom; }
    public int primary() { return primary; }
    public int secondary() { return secondary; }
    public boolean historical() { return historical; }
    public boolean bound() { return !kingdom.isEmpty(); }
    /** The sigil texture, or null for an unbound or historical object without one. */
    public ResourceLocation icon() { return icon.isEmpty() ? null : ResourceLocation.tryParse(icon); }

    /** Binds this object to {@code kingdom} (server only) and loads its hints from the server's definitions. */
    void bind(String kingdom) {
        this.kingdom = kingdom == null ? "" : kingdom;
        refresh();
    }

    /** Recomputes the hints from the current definitions and colour choices; keeps the old ones, marked historical, if the kingdom is gone. */
    void refresh() {
        if (!(level instanceof ServerLevel server) || kingdom.isEmpty()) return;
        var view = Heraldry.of(server.getServer(), kingdom);
        boolean changed;
        if (view.isEmpty()) {
            changed = !historical;
            historical = true;
        } else {
            var hints = view.get();
            changed = historical || primary != hints.primary() || secondary != hints.secondary() || !icon.equals(hints.icon().toString()) || revision != hints.revision();
            historical = false; primary = hints.primary(); secondary = hints.secondary(); icon = hints.icon().toString(); revision = hints.revision();
        }
        if (changed) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Server ticker: re-reads the hints every 10 seconds (staggered by position), so colour choices and mourning reach loaded objects. */
    static void serverTick(Level level, BlockPos pos, BlockState state, HeraldicBlockEntity entity) {
        if ((level.getGameTime() + (pos.asLong() & 0xFF)) % 200 == 0) entity.refresh();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        refresh();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Heraldry", hints());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        CompoundTag hints = tag.getCompound("Heraldry");
        String stored = hints.getString("Kingdom");
        kingdom = ResourceLocation.tryParse(stored) == null ? "" : stored;
        primary = hints.contains("Primary") ? hints.getInt("Primary") & 0xFFFFFF : primary;
        secondary = hints.contains("Secondary") ? hints.getInt("Secondary") & 0xFFFFFF : secondary;
        String sigil = hints.getString("Icon");
        // Only a sigil under textures/gui/heraldry/ is ever drawn; anything else is ignored rather than trusted.
        icon = ResourceLocation.tryParse(sigil) == null ? "" : sigil;
        revision = hints.getLong("Revision");
        historical = hints.getBoolean("Historical");
        if (level != null && level.isClientSide()) requestModelDataUpdate();
    }

    private CompoundTag hints() {
        CompoundTag hints = new CompoundTag();
        hints.putString("Kingdom", kingdom);
        hints.putInt("Primary", primary); hints.putInt("Secondary", secondary);
        hints.putString("Icon", icon); hints.putLong("Revision", revision); hints.putBoolean("Historical", historical);
        return hints;
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.put("Heraldry", hints());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection, ClientboundBlockEntityDataPacket packet) {
        if (packet.getTag() != null) load(packet.getTag());
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 8);
    }
}
