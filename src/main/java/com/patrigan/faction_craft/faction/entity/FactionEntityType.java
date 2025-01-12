package com.patrigan.faction_craft.faction.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.patrigan.faction_craft.capabilities.factionentity.FactionEntityHelper;
import com.patrigan.faction_craft.faction.Faction;
import net.minecraft.entity.*;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

import java.util.Arrays;
import java.util.List;

import static net.minecraftforge.registries.ForgeRegistries.ENTITIES;

public class FactionEntityType {
    public static final Codec<FactionEntityType> CODEC = RecordCodecBuilder.create(builder ->
            builder.group(
                    ResourceLocation.CODEC.fieldOf("entity_type").forGetter(data -> data.entityType),
                    CompoundNBT.CODEC.optionalFieldOf("tag", new CompoundNBT()).forGetter(data -> data.tag),
                    Codec.INT.fieldOf("weight").forGetter(data -> data.weight),
                    Codec.INT.fieldOf("strength").forGetter(data -> data.strength),
                    FactionRank.CODEC.fieldOf("rank").forGetter(data -> data.rank),
                    FactionRank.CODEC.fieldOf("maximum_rank").forGetter(data -> data.maximumRank),
                    EntityBoostConfig.CODEC.optionalFieldOf("boosts", EntityBoostConfig.DEFAULT).forGetter(data -> data.entityBoostConfig),
                    Codec.INT.fieldOf("minimum_wave").forGetter(data -> data.minimumWave)
            ).apply(builder, FactionEntityType::new));

    private final ResourceLocation entityType;
    private final CompoundNBT tag;
    private final int weight;
    private final int strength;
    private final FactionRank rank;
    private final FactionRank maximumRank;
    private final EntityBoostConfig entityBoostConfig;
    private final int minimumWave;

    public FactionEntityType(ResourceLocation entityType, CompoundNBT tag, int weight, int strength, FactionRank rank, FactionRank maximumRank, EntityBoostConfig entityBoostConfig, int minimumWave) {
        this.entityType = entityType;
        this.tag = tag;
        this.weight = weight;
        this.strength = strength;
        this.rank = rank;
        this.maximumRank = maximumRank;
        this.entityBoostConfig = entityBoostConfig;
        this.minimumWave = minimumWave;
    }

    public static FactionEntityType load(CompoundNBT compoundNbt) {
        return new FactionEntityType(
                new ResourceLocation(compoundNbt.getString("entityType")),
                compoundNbt.getCompound("tag"),
                compoundNbt.getInt("weight"),
                compoundNbt.getInt("strength"),
                FactionRank.byName(compoundNbt.getString("rank"), FactionRank.SOLDIER),
                FactionRank.byName(compoundNbt.getString("maximumRank"), null),
                EntityBoostConfig.load(compoundNbt.getCompound("entityBoostConfig")),
                compoundNbt.getInt("minimumWave"));
    }

    public ResourceLocation getEntityType() {
        return entityType;
    }

    public CompoundNBT getTag() {
        return tag;
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

    public boolean canBeBannerHolder() {
        FactionRank currentRank = this.getRank();
        List<FactionRank> possibleCaptains = Arrays.asList(FactionRank.CAPTAIN, FactionRank.GENERAL, FactionRank.LEADER);
        while (currentRank != null) {
            if (possibleCaptains.contains(currentRank)) {
                return true;
            } else {
                if (currentRank.equals(getMaximumRank())) {
                    return false;
                }
            }
            currentRank = currentRank.promote();
        }
        return false;
    }

    public boolean hasRank(FactionRank requiredRank) {
        FactionRank currentRank = rank;
        while (currentRank != null) {
            if (currentRank.equals(requiredRank)) {
                return true;
            }
            currentRank = currentRank.promote();
        }
        return false;
    }


    //See and use SummonCommand approach for tag and add to the world
    public Entity createEntity(ServerWorld level, Faction faction, BlockPos spawnBlockPos, boolean bannerHolder, SpawnReason spawnReason) {
        EntityType<?> entityType = ENTITIES.getValue(this.getEntityType());
        Entity entity = null;
        if (!this.getTag().isEmpty()) {
            CompoundNBT compoundnbt = this.getTag().copy();
            compoundnbt.putString("id", this.getEntityType().toString());
            entity = EntityType.loadEntityRecursive(compoundnbt, level, createdEntity -> {
                createdEntity.moveTo(spawnBlockPos.getX() + 0.5D, spawnBlockPos.getY() + 1.0D, spawnBlockPos.getZ() + 0.5D, createdEntity.yRot, createdEntity.xRot);
                return createdEntity;
            });
        } else {
            entity = entityType.create(level);
            entity.setPos(spawnBlockPos.getX() + 0.5D, spawnBlockPos.getY() + 1.0D, spawnBlockPos.getZ() + 0.5D);
        }
        if (entity == null) {
            return null;
        }
        if (entity instanceof MobEntity) {
            MobEntity mobEntity = (MobEntity) entity;
            if (bannerHolder) {
                faction.makeBannerHolder(mobEntity);
            }
            if (net.minecraftforge.common.ForgeHooks.canEntitySpawn(mobEntity, level, spawnBlockPos.getX(), spawnBlockPos.getY(), spawnBlockPos.getZ(), null, spawnReason) == -1)
                return null;
            if (this.tag.isEmpty()) {
                mobEntity.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnBlockPos), SpawnReason.EVENT, null, null);
            }
            mobEntity.setOnGround(true);
        }
        entity.getRootVehicle().getSelfAndPassengers()
            .forEach(stackedEntity -> {
                if (stackedEntity instanceof MobEntity)
                    FactionEntityHelper.getFactionEntityCapabilityLazy((MobEntity) stackedEntity)
                        .ifPresent(cap -> {
                            cap.setFaction(faction);
                            cap.setFactionEntityType(this);
                        });
            });

        level.addFreshEntityWithPassengers(entity.getRootVehicle());
        return entity;
    }

    public CompoundNBT save(CompoundNBT compoundNbt) {
        compoundNbt.putString("entityType", this.entityType.toString());
        compoundNbt.put("tag", tag);
        compoundNbt.putInt("weight", weight);
        compoundNbt.putInt("strength", strength);
        compoundNbt.putString("rank", rank.getName());
        compoundNbt.putString("maximumRank", maximumRank.getName());
        CompoundNBT boostConfigNbt = new CompoundNBT();
        compoundNbt.put("entityBoostConfig", entityBoostConfig.save(boostConfigNbt));
        compoundNbt.putInt("minimumWave", minimumWave);
        return compoundNbt;
    }

    public enum FactionRank {
        LEADER("leader", null),
        SUPPORT("support", null),
        MOUNT("mount", null),
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

        public static FactionRank byName(String name, FactionRank defaultRank) {
            for (FactionRank factionRank : values()) {
                if (factionRank.name.equals(name)) {
                    return factionRank;
                }
            }

            return defaultRank;
        }

        public FactionRank promote() {
            return promotion;
        }

        public String getName() {
            return name;
        }
    }
}
