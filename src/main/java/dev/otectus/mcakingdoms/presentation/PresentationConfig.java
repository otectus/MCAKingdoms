package dev.otectus.mcakingdoms.presentation;

import net.minecraftforge.common.ForgeConfigSpec;

public final class PresentationConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue SHOW_SETTLEMENT_OVERLAY;
    public static final ForgeConfigSpec.BooleanValue FIRST_VISIT_ONLY;
    public static final ForgeConfigSpec.BooleanValue KINGDOM_BORDERS_ONLY;
    public static final ForgeConfigSpec.IntValue OVERLAY_DURATION_TICKS;
    public static final ForgeConfigSpec.IntValue OVERLAY_Y;
    public static final ForgeConfigSpec.BooleanValue SHOW_INTRO;
    public static final ForgeConfigSpec.EnumValue<TasksStart> TASKS_START;
    public static final ForgeConfigSpec.BooleanValue REDUCED_MOTION;

    /** What the Kingdom Tasks key opens first. */
    public enum TasksStart { START, ALL_TASKS }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("entryOverlay");
        SHOW_SETTLEMENT_OVERLAY = builder
                .comment("Show a localized title when entering a recognized settlement. Set to false to never show it.")
                .define("enabled", true);
        FIRST_VISIT_ONLY = builder
                .comment("Show the title only the first time you enter each settlement. Set to false to show it on every entry.")
                .define("firstVisitOnly", true);
        KINGDOM_BORDERS_ONLY = builder
                .comment("Show the title only when the settlement belongs to a different kingdom than the last one you entered.",
                        "With firstVisitOnly also on, only a first visit that crosses into a different kingdom shows it.")
                .define("kingdomBordersOnly", false);
        OVERLAY_DURATION_TICKS = builder
                .comment("How long an entry title remains visible, in client ticks.")
                .defineInRange("durationTicks", 80, 20, 400);
        OVERLAY_Y = builder
                .comment("Vertical screen position of the entry title, in pixels from the top.")
                .defineInRange("y", 54, 0, 1000);
        builder.pop();
        builder.push("onboarding");
        SHOW_INTRO = builder
                .comment("Show the short introduction at the top of Kingdom Tasks' start view. Getting started stays available either way.")
                .define("showIntro", true);
        builder.pop();
        builder.push("tasks");
        TASKS_START = builder
                .comment("What the Kingdom Tasks key opens: START shows where you are, suggested next steps and shortcuts;",
                        "ALL_TASKS opens the complete task list directly.")
                .defineEnum("openTo", TasksStart.START);
        builder.pop();
        builder.push("guide");
        REDUCED_MOTION = builder
                .comment("Show the first item of a recipe ingredient that accepts several items, instead of cycling through them every two seconds.")
                .define("reducedMotion", false);
        builder.pop();
        SPEC = builder.build();
    }

    private PresentationConfig() {
    }
}
