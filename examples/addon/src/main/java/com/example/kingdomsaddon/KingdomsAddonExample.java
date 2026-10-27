package com.example.kingdomsaddon;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * A minimal addon that treats MCA: Kingdoms as optional. Nothing here touches an MCA: Kingdoms class unless that mod is
 * loaded, so this addon also runs without it; all API use lives in {@link KingdomsBridge}.
 */
@Mod(KingdomsAddonExample.MOD_ID)
public final class KingdomsAddonExample {
    public static final String MOD_ID = "kingdomsaddon";

    public KingdomsAddonExample() {
        if (ModList.get().isLoaded("mcakingdoms")) MinecraftForge.EVENT_BUS.register(new KingdomsBridge());
    }
}
