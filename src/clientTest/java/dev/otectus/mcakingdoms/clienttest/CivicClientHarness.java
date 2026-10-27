package dev.otectus.mcakingdoms.clienttest;

import dev.otectus.mcakingdoms.civic.CivicViews;
import dev.otectus.mcakingdoms.client.GuildScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Opt-in two-client acceptance for the authenticated GuildScreen and civic network. */
public final class CivicClientHarness {
    private final Minecraft mc = Minecraft.getInstance();
    private final Path output = Path.of(System.getProperty("mcakingdoms.clientTest.output"));
    private final Path shared = Path.of(System.getProperty("mcakingdoms.clientTest.shared"));
    private final String role = System.getProperty("mcakingdoms.clientTest.role");
    private final long started = System.currentTimeMillis();
    private int stage;
    private int ticks;
    private int entered;
    private boolean connecting;
    private boolean finished;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        ticks++;
        try {
            if (System.currentTimeMillis() - started > 240_000L) {
                throw new AssertionError("Civic multiplayer timeout for " + role + " at stage " + stage);
            }
            if (!connecting && (mc.screen instanceof TitleScreen
                    || mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)) {
                connecting = true;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(2);
                mc.options.guiScale().set(2);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                String address = System.getProperty("mcakingdoms.clientTest.server");
                ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address),
                        new ServerData("Civic acceptance", address, false), false);
            }
            if (ticks % 200 == 0) {
                System.out.println("CIVIC_CLIENT role=" + role + " stage=" + stage
                        + " player=" + (mc.player != null) + " ready=" + ready());
            }
            // CivicNetwork intentionally drops requests less than four server ticks apart. Keep
            // a full client-second between screen reads and mutations so startup tick skew cannot
            // turn a valid click into a silently rate-limited request.
            if (mc.player == null || ticks - entered < 20) return;

            if (reviewing != null) { review(); return; }
            switch (stage) {
                case 0 -> {
                    if (!Files.exists(shared.resolve("READY"))) return;
                    mc.setScreen(new GuildScreen(null));
                    next();
                }
                case 1 -> {
                    if (!ready()) return;
                    check(!view().active(), "fresh player starts unaffiliated");
                    checkReadableQualificationEvidence();
                    shot("01-" + role + "-guild-normal.png");
                    click("join");
                    next();
                }
                case 2 -> {
                    if (!ready() || !view().active()) return;
                    checkReadableOutcome("join");
                    mark(role + "-JOINED");
                    next();
                }
                case 3 -> {
                    if (!both("leader-JOINED", "peer-JOINED")) return;
                    if (role.equals("leader")) {
                        click("leave");
                        next();
                    } else if (Files.exists(shared.resolve("leader-LEFT"))) {
                        click("refresh");
                        next();
                    }
                }
                case 4 -> {
                    if (!ready()) return;
                    if (role.equals("leader")) {
                        check(!view().active(), "leader leave affects leader profile");
                        checkReadableOutcome("leave");
                        mark("leader-LEFT");
                        if (!Files.exists(shared.resolve("peer-ISOLATED"))) return;
                        click("join");
                        next();
                    } else {
                        check(view().active(), "leader leave does not alter peer profile");
                        mark("peer-ISOLATED");
                        if (!Files.exists(shared.resolve("leader-REJOINED"))) return;
                        click("leave");
                        next();
                    }
                }
                case 5 -> {
                    if (!ready()) return;
                    if (role.equals("leader")) {
                        check(view().active(), "leader can rejoin with its own profile");
                        checkReadableOutcome("rejoin");
                        mark("leader-REJOINED");
                        if (!Files.exists(shared.resolve("peer-LEFT"))) return;
                        click("refresh");
                        next();
                    } else {
                        check(!view().active(), "peer leave affects peer profile");
                        checkReadableOutcome("leave");
                        mark("peer-LEFT");
                        if (!Files.exists(shared.resolve("leader-ISOLATED"))) return;
                        click("join");
                        next();
                    }
                }
                case 6 -> {
                    if (!ready()) return;
                    if (role.equals("leader")) {
                        check(view().active(), "peer leave does not alter leader profile");
                        mark("leader-ISOLATED");
                    } else {
                        check(view().active(), "peer can rejoin with its own profile");
                        checkReadableOutcome("rejoin");
                        mark("peer-REJOINED");
                    }
                    compactScreenshot();
                    next();
                }
                case 7 -> {
                    if (!ready() || role.equals("leader") && !Files.exists(shared.resolve("peer-REJOINED"))) return;
                    click("hospitality"); next();
                }
                case 8 -> {
                    if (!ready()) return;
                    checkReadableOutcome("unavailable hospitality");
                    check(view().active(), "hospitality refusal preserves requester membership");
                    click("workshop"); next();
                }
                case 9 -> {
                    if (!ready()) return;
                    checkReadableOutcome("unavailable workshop");
                    check(view().active(), "workshop refusal preserves requester membership");
                    Files.writeString(shared.resolve(role + "-PASS.txt"),
                            "PASS GuildScreen with reviewed Kingdom Tasks join-leave-rejoin profile isolation, R2 hospitality/workshop refusal reported as refused, readable qualification reasons, normal and compact screenshots\n");
                    finished = true;
                    mc.stop();
                }
                default -> throw new AssertionError("Unexpected civic client stage " + stage);
            }
        } catch (Throwable failure) {
            finished = true;
            failure.printStackTrace();
            try {
                Files.writeString(shared.resolve(role + "-FAIL.txt"), failure.toString());
            } catch (Exception ignored) {
            }
            mc.stop();
        }
    }

    private boolean ready() {
        return mc.screen instanceof GuildScreen && field("view") != null && field("pending") == null;
    }

    private CivicViews.View view() {
        return (CivicViews.View) field("view");
    }

    private void checkReadableQualificationEvidence() {
        List<String> lines = view().lines().stream().map(line -> line.getString().trim())
                .filter(line -> !line.isEmpty()).toList();
        check(lines.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains("qualification")),
                "qualification explanation is present");
        check(lines.stream().noneMatch(line -> line.contains("organization.") || line.contains("civic.")),
                "qualification reasons are localized, not raw keys: " + lines);
    }

    /** The last reviewed action's result, read on the Tasks result page before returning to the Guilds screen. */
    private String lastOutcome = "", lastStatus = "";
    /** The action being reviewed in Kingdom Tasks (join, leave or a service), or null. */
    private String reviewing;

    private void checkReadableOutcome(String operation) {
        check(!lastOutcome.isBlank(), operation + " has a visible outcome");
        check(!lastOutcome.contains("organization.") && !lastOutcome.contains("civic."), operation + " outcome is localized: " + lastOutcome);
        if (operation.startsWith("unavailable")) check(lastStatus.equals("refused"), operation + " is reported as refused, not done: " + lastStatus);
        else check(lastStatus.equals("done"), operation + " is reported as done: " + lastStatus);
    }

    /** Joining, leaving and services are reviewed in Kingdom Tasks: confirm the named action, read the result, return. */
    private void review() {
        if (!(mc.screen instanceof dev.otectus.mcakingdoms.client.KingdomActionsScreen tasks)) return;
        Object pending = taskField(tasks, "pending");
        var reply = (dev.otectus.mcakingdoms.interaction.InteractionNetwork.Reply) taskField(tasks, "reply");
        if (pending != null || reply == null) return;
        if (reply.mode().equals("review")) {
            String action = reply.actionComponent().getString();
            check(!action.isBlank(), reviewing + " review names its action");
            Button apply = mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b -> b.getMessage().getString().equals(action)).findFirst().orElseThrow(() -> new AssertionError("No confirm button " + action));
            check(mc.screen.mouseClicked(apply.getX() + apply.getWidth() / 2.0, apply.getY() + apply.getHeight() / 2.0, 0), "confirmed " + reviewing);
            return;
        }
        if (reply.mode().equals("result") || reply.mode().equals("error") || reply.mode().equals("unavailable")) {
            lastOutcome = reply.detailText();
            lastStatus = reply.mode().equals("result") ? reply.statusOrEmpty() : "refused";
            System.out.println("CIVIC_CLIENT " + reviewing + " -> " + lastStatus + ": " + lastOutcome);
            reviewing = null;
            ((net.minecraft.client.gui.screens.Screen) mc.screen).onClose();
        }
    }

    private Object taskField(Object screen, String name) {
        try {
            Field field = dev.otectus.mcakingdoms.client.KingdomActionsScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(screen);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    private void compactScreenshot() {
        mc.getWindow().setGuiScale(mc.getWindow().getHeight() / 240.0);
        mc.screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        check(mc.screen.height >= 239 && mc.screen.height <= 241, "compact GuildScreen height");
        for (Object child : mc.screen.children()) {
            if (child instanceof Button button) {
                check(button.getY() >= 0 && button.getY() + button.getHeight() <= mc.screen.height,
                        "compact button remains inside screen: " + button.getMessage().getString());
            }
        }
        shot("02-" + role + "-guild-compact.png");
    }

    /** join, leave, refresh, hospitality or workshop, through the Guilds screen as a player would. */
    private void click(String name) {
        if (name.equals("hospitality") || name.equals("workshop")) {
            var record = (dev.otectus.mcakingdoms.client.rich.RichPanel) field("record");
            var view = view().guild();
            int index = -1;
            for (int i = 0; i < view.services().size(); i++) if (view.services().get(i).permission().endsWith(":" + name)) index = i + 1;
            check(index > 0, name + " service is listed on the Guilds screen");
            String key = "use_" + index;
            var target = record.targets().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow(() -> new AssertionError("No " + name + " action"));
            reviewing = name;
            target.activate();
            return;
        }
        String label = switch (name) {
            case "refresh" -> net.minecraft.network.chat.Component.translatable("civic.mcakingdoms.refresh").getString();
            default -> null;
        };
        Button button = mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> label != null ? b.getMessage().getString().equals(label)
                        : b.getMessage().getString().startsWith(name.equals("join") ? "Join " : "Leave ")).findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " button"));
        check(button.active, name + " button enabled");
        if (label == null) reviewing = name;
        check(mc.screen.mouseClicked(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0), "clicked " + name);
    }

    private Object field(String name) {
        try {
            Field field = GuildScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(mc.screen);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    private void shot(String file) {
        Screenshot.grab(output.toFile(), file, mc.getMainRenderTarget(),
                message -> System.out.println("CIVIC_CLIENT screenshot=" + file + " " + message.getString()));
    }

    private void mark(String name) throws Exception {
        Files.writeString(shared.resolve(name), "ready\n");
    }

    private boolean both(String first, String second) {
        return Files.exists(shared.resolve(first)) && Files.exists(shared.resolve(second));
    }

    private void next() {
        stage++;
        entered = ticks;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("CIVIC_CLIENT PASS " + message);
    }
}
