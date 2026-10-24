package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.recruits.TransferAccounting;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.UUID;

@Pseudo
@Mixin(targets="com.talhanation.recruits.entities.AbstractRecruitEntity",remap=false)
public abstract class RecruitsTransferNativeWriteMixin {
    @Redirect(method="disband",at=@At(value="INVOKE",target="Lcom/talhanation/recruits/world/RecruitsPlayerUnitManager;removeRecruits(Ljava/util/UUID;I)V"),remap=false)
    private void mcakingdoms$release(@Coerce Object manager,UUID owner,int count){TransferAccounting.nativeWrite((Entity)(Object)this,manager,"removeRecruits",owner,count);}
    @Redirect(method="hire",at=@At(value="INVOKE",target="Lcom/talhanation/recruits/world/RecruitsPlayerUnitManager;addRecruits(Ljava/util/UUID;I)V"),remap=false)
    private void mcakingdoms$hire(@Coerce Object manager,UUID owner,int count){TransferAccounting.nativeWrite((Entity)(Object)this,manager,"addRecruits",owner,count);}
}
