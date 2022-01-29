package com.patrigan.faction_craft.boost;


import com.patrigan.faction_craft.capabilities.appliedboosts.AppliedBoostsHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.patrigan.faction_craft.FactionCraft.MODID;

@Mod.EventBusSubscriber(modid = MODID)
public class BoostEvents {

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event){
        Entity entity = event.getEntity();
        if(!entity.level.isClientSide() && entity instanceof Mob) {
            Mob mobEntity = (Mob) event.getEntity();
            AppliedBoostsHelper.getAppliedBoostsCapabilityLazy(mobEntity).ifPresent(cap -> {
                cap.getAppliedBoosts().forEach(boost -> boost.updateAIOnJoin(mobEntity));
            });
        }
    }
}
