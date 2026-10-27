package dev.otectus.mcakingdoms.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * The public offices one observed entity holds (A02), sent only to players tracking it. An empty list clears its badge.
 * Roles carry translation keys (office title, kingdom name), never display-name text, and never anything private.
 */
public record OfficeBadgePacket(int entityId, List<Role> roles) {
    static final int MAX_ROLES = 8;

    /** One office: its title key, the kingdom's name key, and whether it is the government's leadership. */
    public record Role(String titleKey, String kingdomKey, boolean leader) {
        public Role {
            if (titleKey == null || kingdomKey == null || titleKey.length() > 128 || kingdomKey.length() > 128)
                throw new IllegalArgumentException("Invalid office badge role");
        }
    }

    public OfficeBadgePacket {
        roles = List.copyOf(roles);
        if (roles.size() > MAX_ROLES) throw new IllegalArgumentException("Too many office badge roles");
    }

    public static OfficeBadgePacket decode(FriendlyByteBuf buffer) {
        int entity = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ROLES) throw new IllegalArgumentException("Invalid office badge size " + count);
        List<Role> roles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) roles.add(new Role(buffer.readUtf(128), buffer.readUtf(128), buffer.readBoolean()));
        return new OfficeBadgePacket(entity, roles);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        buffer.writeVarInt(roles.size());
        for (Role role : roles) {
            buffer.writeUtf(role.titleKey(), 128);
            buffer.writeUtf(role.kingdomKey(), 128);
            buffer.writeBoolean(role.leader());
        }
    }
}
