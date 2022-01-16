package com.patrigan.faction_craft.faction.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.patrigan.faction_craft.capabilities.factionentity.FactionEntityHelper;
import com.patrigan.faction_craft.faction.Faction;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MobEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;

import java.util.Arrays;
import java.util.List;

import static net.minecraftforge.registries.ForgeRegistries.ENTITIES;

public class FactionEntityType {
    public static final Codec<FactionEntityType> CODEC = RecordCodecBuilder.create(builder ->
            builder.group(
                    ResourceLocation.CODEC.fieldOf("entity_type").forGetter(data -> data.entityType),
                    Codec.INT.fieldOf("weight").forGetter(data -> data.weight),
                    Codec.INT.fieldOf("strength").forGetter(data -> data.strength),
                    FactionRank.CODEC.fieldOf("rank").forGetter(data -> data.rank),
                    FactionRank.CODEC.fieldOf("maximum_rank").forGetter(data -> data.maximumRank),
                    EntityBoostConfig.CODEC.optionalFieldOf("boosts", EntityBoostConfig.DEFAULT).forGetter(data -> data.entityBoostConfig),
                    Codec.INT.fieldOf("minimum_wave").forGetter(data -> data.minimumWave)
                    ).apply(builder, FactionEntityType::new));

    private final ResourceLocation entityType;
    private final int weight;
    private final int strength;
    private final FactionRank rank;
    private final FactionRank maximumRank;
    private final EntityBoostConfig entityBoostConfig;
    private final int minimumWave;

    public FactionEntityType(ResourceLocation entityType, int weight, int strength, FactionRank rank, FactionRank maximumRank, EntityBoostConfig entityBoostConfig, int minimumWave) {
        this.entityType = entityType;
        this.weight = weight;
        this.strength = strength;
        this.rank = rank;
        this.maximumRank = maximumRank;
        this.entityBoostConfig = entityBoostConfig;
        this.minimumWave = minimumWave;
    }

    public ResourceLocation getEntityType() {
        return entityType;
    }

    public int getWeight() {
        return weight;
    }

    public int getStrength() {
        return strength;
    }

    public FactionRank getRank() {
        return rank;
    }

    public FactionRank getMaximumRank() {
        return maximumRank;
    }

    public EntityBoostConfig getBoostConfig() {
        return entityBoostConfig;
    }

    public int getMinimumWave() {
        return minimumWave;
    }

    public boolean canBeCaptain() {
        FactionRank rank = this.getRank();
        List<FactionRank> possibleCaptains = Arrays.asList(FactionRank.CAPTAIN, FactionRank.GENERAL, FactionRank.LEADER);
        while(rank != null) {
            if(possibleCaptains.contains(rank)){
                return true;
            }else{
                if(rank.equals(getMaximumRank())){
                    return false;
                }
            }
            rank = rank.promote();
        }
        return false;
    }

    public Entity createEntity(ServerWorld level, Faction faction, boolean bannerHolder) {
        EntityType<?> entityType = ENTITIES.getValue(this.getEntityType());
        Entity entity =  entityType.create(level);
        if(entity instanceof MobEntity){
            MobEntity mobEntity = (MobEntity) entity;
            faction.getBoostConfig().getMandatoryBoosts().forEach(boost -> boost.apply(mobEntity));
            entityBoostConfig.getMandatoryBoosts().forEach(boost -> boost.apply(mobEntity));
            if(bannerHolder){
                mobEntity.setItemSlot(EquipmentSlotType.HEAD, faction.getBannerInstance());
                mobEntity.setDropChance(EquipmentSlotType.HEAD, 2.0F);
            }
            FactionEntityHelper.getFactionEntityCapabilityLazy(mobEntity).ifPresent(cap -> cap.setFaction(faction));
        }
        return entity;
    }

    public enum FactionRank {
        LEADER("leader", null),
        SUPPORT("support", null),
        GENERAL("general", LEADER),
        CAPTAIN("captain", GENERAL),
        SOLDIER("soldier", CAPTAIN);

        public static final Codec<FactionRank> CODEC = Codec.STRING.flatComapMap(s -> FactionRank.byName(s, null), d -> DataResult.success(d.getName()));

        private final String name;
        private final FactionRank promotion;

        FactionRank(String name, FactionRank promotion) {
            this.name = name;
            this.promotion = promotion;
        }

        public FactionRank promote(){ return promotion;}

        public static FactionRank byName(String name, FactionRank defaultRank) {
            for(FactionRank factionRank : values()) {
                if (factionRank.name.equals(name)) {
                    return factionRank;
                }
            }

            return defaultRank;
        }

        public String getName() {
            return name;
        }
    }
}