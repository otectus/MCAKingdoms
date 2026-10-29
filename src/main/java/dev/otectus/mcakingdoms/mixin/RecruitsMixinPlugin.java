package dev.otectus.mcakingdoms.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.*;

/** Apply audited native patches only to the exact supported provider. */
public final class RecruitsMixinPlugin implements IMixinConfigPlugin {
    /** Living Villages mixins whose redirects name Minecraft methods by SRG name, so they only match a production runtime. */
    private static final Set<String> PRODUCTION_ONLY = Set.of("LvResidentGuardMixin");
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (simple.startsWith("Lv")) {
            // Living Villages hooks: the audited 1.1.0 jar with Recruits 1.15.2 only (LvGate); never a partial set.
            if (!LvGate.applies()) return false;
            if (PRODUCTION_ONLY.contains(simple) && !LvGate.production()) {
                System.setProperty("mcakingdoms.livingvillages.residents", "skipped:development_runtime");
                return false;
            }
            return true;
        }
        var mods = net.minecraftforge.fml.loading.FMLLoader.getLoadingModList();
        return mods != null && mods.getMods().stream().anyMatch(m -> m.getModId().equals("recruits") && m.getVersion().toString().equals("1.15.2"));
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> other) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.endsWith(".LvResidentGuardMixin")) System.setProperty("mcakingdoms.livingvillages.residents", "applied");
    }
}
