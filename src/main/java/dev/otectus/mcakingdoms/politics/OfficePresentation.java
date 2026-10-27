package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.api.KingdomView;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.PoliticalCommittedEvent;
import dev.otectus.mcakingdoms.api.politics.PoliticalFact;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.network.NetworkHandler;
import dev.otectus.mcakingdoms.network.OfficeBadgePacket;
import dev.otectus.mcakingdoms.network.PoliticalNoticePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

/**
 * Visible political identity (A02), derived only from committed authority. When a political decision commits, the
 * kingdom's office holders are compared with the last committed snapshot; the committed fact's type decides the words.
 * Players who opted in receive a notice once per committed moment (a founding, an accession by appointment, election or
 * succession, a step down, a dismissal, or a memorial for a confirmed death), never a replay on reconnect, and only for
 * public facts, through a bounded queue. Office badges travel only to players already tracking the holder, when they
 * start tracking it or when its offices change; nothing is scanned per frame and nothing is broadcast to every player.
 * Names, families, clothing and behaviour are never touched: this is presentation only.
 */
public final class OfficePresentation {
    private static final String LEADER = "mcakingdoms:leader";
    private static final int QUEUE = 16, SPACING_TICKS = 20;
    private static final Map<MinecraftServer, OfficePresentation> RUNNING = new WeakHashMap<>();

    private final MinecraftServer server;
    private final Map<String, Map<String, Politics.Office>> offices = new HashMap<>();
    private final Map<UUID, List<OfficeBadgePacket.Role>> roles = new HashMap<>();
    private final Map<UUID, ArrayDeque<PoliticalNoticePacket>> queued = new HashMap<>();
    private final Map<UUID, Integer> lastSent = new HashMap<>();
    /** The last notices announced (not per player), newest last; for diagnostics and tests. */
    private final ArrayDeque<PoliticalNoticePacket> recent = new ArrayDeque<>();
    /** Display names of office holders seen loaded, so a memorial can name someone whose body is already gone. */
    private final Map<UUID, String> names = new HashMap<>();

    private OfficePresentation(MinecraftServer server) {
        this.server = server;
    }

    /** Starts presentation for this server from the committed governments; no notice is sent for the existing state. */
    public static OfficePresentation start(MinecraftServer server) {
        var presentation = new OfficePresentation(server);
        presentation.rebuild();
        RUNNING.put(server, presentation);
        return presentation;
    }

    public static void stop(MinecraftServer server) {
        RUNNING.remove(server);
    }

    public static Optional<OfficePresentation> get(MinecraftServer server) {
        return Optional.ofNullable(RUNNING.get(server));
    }

    private static boolean isLeader(String officeKey) {
        return officeKey.equals(LEADER) || officeKey.startsWith(LEADER + "@");
    }

    private void rebuild() {
        offices.clear();
        roles.clear();
        var politics = KingdomsPoliticsApi.get(server);
        for (KingdomView kingdom : McaKingdomsApi.get(server).getKingdoms()) {
            politics.government(kingdom.id().toString()).ifPresent(g -> offices.put(g.kingdom(), Map.copyOf(g.offices())));
        }
        offices.forEach((kingdom, map) -> map.forEach((key, office) -> roles.computeIfAbsent(office.holder().id(), id -> new ArrayList<>()).add(role(kingdom, key, office))));
    }

    private OfficeBadgePacket.Role role(String kingdom, String officeKey, Politics.Office office) {
        String kingdomKey = McaKingdomsApi.get(server).getKingdom(new net.minecraft.resources.ResourceLocation(kingdom))
                .map(KingdomView::translationKey).orElse(kingdom);
        return new OfficeBadgePacket.Role(office.terms().title(), kingdomKey, isLeader(officeKey));
    }

    /** One role in words: the leadership is named as such; other offices by their title (shared by badges and pickers). */
    public static Component describe(OfficeBadgePacket.Role role) {
        Component kingdom = Component.translatable(role.kingdomKey());
        return role.leader() ? Component.translatableWithFallback("badge.mcakingdoms.leader", "Leader of %s", kingdom)
                : Component.translatableWithFallback("badge.mcakingdoms.office", "%s of %s", Component.translatable(role.titleKey()), kingdom);
    }

    /** Every public office of {@code holder} in words, joined, or empty when none; for choices that name people. */
    public static Optional<Component> describe(MinecraftServer server, UUID holder) {
        var held = get(server).map(p -> p.roles(holder)).orElse(List.of());
        if (held.isEmpty()) return Optional.empty();
        var out = Component.empty();
        for (int i = 0; i < held.size(); i++) { if (i > 0) out.append(", "); out.append(describe(held.get(i))); }
        return Optional.of(out);
    }

    /** The public offices this person holds now, leadership first. */
    public List<OfficeBadgePacket.Role> roles(UUID holder) {
        var list = new ArrayList<>(roles.getOrDefault(holder, List.of()));
        list.sort(Comparator.comparing((OfficeBadgePacket.Role r) -> !r.leader()));
        return List.copyOf(list.subList(0, Math.min(list.size(), 8)));
    }

    // ---------------------------------------------------------------------------------------------------- committed changes

    @SubscribeEvent
    public void committed(PoliticalCommittedEvent event) {
        if (event.server() != server) return;
        String kingdom = event.notice().kingdom();
        var politics = KingdomsPoliticsApi.get(server);
        Map<String, Politics.Office> before = offices.getOrDefault(kingdom, Map.of());
        Map<String, Politics.Office> after = politics.government(kingdom).map(g -> Map.copyOf(g.offices())).orElse(Map.of());
        if (before.equals(after)) return;
        offices.put(kingdom, after);
        Set<UUID> changed = new LinkedHashSet<>();
        before.values().forEach(o -> changed.add(o.holder().id()));
        after.values().forEach(o -> changed.add(o.holder().id()));
        rebuildRoles();
        changed.forEach(this::syncBadge);
        if (!McaKingdomsConfig.POLITICAL_NOTICES.get() || !(politics instanceof GovernmentService government)) return;
        Optional<PoliticalFact> fact = government.committedFact(event.notice().id());
        if (fact.isEmpty() || fact.get().visibility() != PoliticalFact.Visibility.PUBLIC) return;
        for (String key : union(before.keySet(), after.keySet())) {
            Politics.Office was = before.get(key), now = after.get(key);
            boolean appointed = now != null && (was == null || !was.holder().equals(now.holder()));
            boolean vacated = was != null && (now == null || !was.holder().equals(now.holder()));
            Component text = words(fact.get().type(), kingdom, key, appointed ? now : null, vacated ? was : null, government);
            if (text != null) announce(fact.get().id().toString() + "|" + key, text, kingdom, appointed ? now : was, fact.get().type() == PoliticalFact.Type.NPC_OFFICE_VACATED);
        }
    }

    private void rebuildRoles() {
        roles.clear();
        offices.forEach((kingdom, map) -> map.forEach((key, office) -> roles.computeIfAbsent(office.holder().id(), id -> new ArrayList<>()).add(role(kingdom, key, office))));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    private Component name(Politics.Person person) {
        if (person.kind() == Politics.Kind.PLAYER) return Component.literal(dev.otectus.mcakingdoms.interaction.Names.player(server, person.id()));
        for (var level : server.getAllLevels()) {
            Entity entity = level.getEntity(person.id());
            if (entity != null) return Component.literal(remember(entity));
        }
        String known = names.get(person.id());
        return known != null ? Component.literal(known) : n("unnamed", "an official");
    }

    private String remember(Entity entity) {
        String name = entity.getName().getString();
        if (names.size() < 4096 || names.containsKey(entity.getUUID())) names.put(entity.getUUID(), name);
        return name;
    }

    /** An office holder's death is seen before the political record is updated: keep their name for the memorial. */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void death(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity().getServer() == server && roles.containsKey(event.getEntity().getUUID())) remember(event.getEntity());
    }

    private Component kingdomName(String kingdom) {
        return McaKingdomsApi.get(server).getKingdom(new net.minecraft.resources.ResourceLocation(kingdom))
                .<Component>map(k -> Component.translatable(k.translationKey())).orElse(Component.literal(kingdom));
    }

    private static Component title(Politics.Office office) {
        return Component.translatable(office.terms().title());
    }

    private static Component n(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("notice.mcakingdoms.political." + key, fallback, args);
    }

    /**
     * The words for one office change under one committed fact, or null when the fact is not a lifecycle moment worth a
     * notice. Leaders are called leaders and the constitution keeps its own name; no one is crowned unless their charter
     * says so in its own title.
     */
    Component words(PoliticalFact.Type type, String kingdom, String officeKey, Politics.Office appointed, Politics.Office vacated,
                    GovernmentService government) {
        Component realm = kingdomName(kingdom);
        boolean leader = isLeader(officeKey);
        if (appointed != null) {
            Component who = name(appointed.holder());
            return switch (type) {
                case GOVERNMENT_FOUNDED -> leader ? n("founded", "%s has a government, %s, led by %s.", realm,
                        government.government(kingdom).<Component>map(g -> Component.translatable(g.constitution().title())).orElse(Component.empty()), who) : null;
                case ELECTION_RESOLVED -> leader ? n("elected", "%s was elected to lead %s.", who, realm) : null;
                case SUCCESSION_CONFIRMED -> leader ? n("succeeded", "%s succeeded to the leadership of %s.", who, realm) : null;
                case OFFICE_APPOINTED -> leader ? n("accession", "%s now leads %s.", who, realm)
                        : n("appointed", "%s was appointed %s of %s.", who, title(appointed), realm);
                default -> null;
            };
        }
        if (vacated != null) {
            Component who = name(vacated.holder());
            return switch (type) {
                case LEADER_ABDICATED -> leader ? n("abdicated", "%s stepped down from the leadership of %s; it is vacant.", who, realm) : null;
                case NPC_OFFICE_VACATED -> n("memorial", "In memory of %s, %s of %s, whose death leaves the office vacant.", who, title(vacated), realm);
                case OFFICE_REMOVED -> n("removed", "%s is no longer %s of %s; the office is vacant.", who, title(vacated), realm);
                default -> null;
            };
        }
        return null;
    }

    /** Queues a public notice for every online player who may see the office's place; delivery is spaced and bounded. */
    private void announce(String eventId, Component text, String kingdom, Politics.Office office, boolean memorial) {
        var packet = new PoliticalNoticePacket(UUID.nameUUIDFromBytes(eventId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Component.Serializer.toJson(text), memorial);
        recent.addLast(packet);
        while (recent.size() > 32) recent.removeFirst();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // A local office names a settlement: only players who know it hear about it.
            if (office != null && office.settlement() != null && !SettlementKnowledge.get(server).visible(player, office.settlement())) continue;
            var queue = queued.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
            if (queue.size() < QUEUE) queue.addLast(packet);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() != server || queued.isEmpty()) return;
        int now = server.getTickCount();
        for (var iterator = queued.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) { iterator.remove(); continue; } // offline players learn from history, never from a replay
            if (now - lastSent.getOrDefault(entry.getKey(), Integer.MIN_VALUE / 2) < SPACING_TICKS) continue;
            NetworkHandler.sendNotice(player, entry.getValue().pollFirst());
            lastSent.put(entry.getKey(), now);
            if (entry.getValue().isEmpty()) iterator.remove();
        }
    }

    // ---------------------------------------------------------------------------------------------------- badges

    private void syncBadge(UUID holder) {
        if (!McaKingdomsConfig.OFFICE_BADGES.get()) return;
        for (var level : server.getAllLevels()) {
            Entity entity = level.getEntity(holder);
            if (entity != null) {
                NetworkHandler.sendBadgeToTrackers(entity, new OfficeBadgePacket(entity.getId(), roles(holder)));
                return;
            }
        }
    }

    @SubscribeEvent
    public void startTracking(PlayerEvent.StartTracking event) {
        if (!McaKingdomsConfig.OFFICE_BADGES.get() || !(event.getEntity() instanceof ServerPlayer viewer) || viewer.getServer() != server) return;
        var held = roles(event.getTarget().getUUID());
        if (held.isEmpty()) return;
        remember(event.getTarget());
        NetworkHandler.sendBadge(viewer, new OfficeBadgePacket(event.getTarget().getId(), held));
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        queued.remove(event.getEntity().getUUID());
        lastSent.remove(event.getEntity().getUUID());
    }

    /** The last announced notices, newest last. */
    public List<PoliticalNoticePacket> recent() {
        return List.copyOf(recent);
    }

    /** Pending notices for this player, for tests and diagnostics. */
    public int pending(UUID player) {
        return queued.getOrDefault(player, new ArrayDeque<>()).size();
    }

    /** What would be queued for this player next, for tests. */
    public Optional<PoliticalNoticePacket> peek(UUID player) {
        return Optional.ofNullable(queued.getOrDefault(player, new ArrayDeque<>()).peekFirst());
    }
}
