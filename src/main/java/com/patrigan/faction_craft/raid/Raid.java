package com.patrigan.faction_craft.raid;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.datafixers.util.Pair;
import com.patrigan.faction_craft.boost.Boost;
import com.patrigan.faction_craft.boost.Boosts;
import com.patrigan.faction_craft.capabilities.raider.IRaider;
import com.patrigan.faction_craft.capabilities.raider.RaiderHelper;
import com.patrigan.faction_craft.capabilities.raidmanager.IRaidManager;
import com.patrigan.faction_craft.faction.Faction;
import com.patrigan.faction_craft.faction.FactionEntityType;
import com.patrigan.faction_craft.faction.Factions;
import com.patrigan.faction_craft.raid.target.RaidTarget;
import com.patrigan.faction_craft.raid.target.RaidTargetHelper;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.entity.*;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.network.play.server.SPlaySoundEffectPacket;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.stats.Stats;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.BossInfo;
import net.minecraft.world.Difficulty;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerBossInfo;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

import static com.patrigan.faction_craft.capabilities.raider.RaiderProvider.RAIDER_CAPABILITY;
import static com.patrigan.faction_craft.capabilities.raidmanager.RaidManagerHelper.getRaidManagerCapability;
import static com.patrigan.faction_craft.config.FactionCraftConfig.*;
import static com.patrigan.faction_craft.util.GeneralUtils.getRandomEntry;
import static com.patrigan.faction_craft.util.GeneralUtils.getRandomItem;

public class Raid {
    private static final ITextComponent RAID_NAME_COMPONENT = new TranslationTextComponent("event.minecraft.raid");
    private static final ITextComponent VICTORY = new TranslationTextComponent("event.minecraft.raid.victory");
    private static final ITextComponent DEFEAT = new TranslationTextComponent("event.minecraft.raid.defeat");
    private static final ITextComponent RAID_BAR_VICTORY_COMPONENT = RAID_NAME_COMPONENT.copy().append(" - ").append(VICTORY);
    private static final ITextComponent RAID_BAR_DEFEAT_COMPONENT = RAID_NAME_COMPONENT.copy().append(" - ").append(DEFEAT);

    private final int id;
    private final Collection<Faction> factions;
    private final ServerWorld level;
    private final RaidTarget raidTarget;
    private int badOmenLevel;

    private final ServerBossInfo raidEvent = new ServerBossInfo(RAID_NAME_COMPONENT, BossInfo.Color.RED, BossInfo.Overlay.NOTCHED_10);
    private float totalHealth;

    private int numGroups;
    private int groupsSpawned = 0;
    private Optional<BlockPos> waveSpawnPos = Optional.empty();

    private final Map<Integer, MobEntity> groupToLeaderMap = Maps.newHashMap();
    private final Map<Integer, Set<MobEntity>> groupRaiderMap = Maps.newHashMap();

    private final Set<UUID> heroesOfTheVillage = Sets.newHashSet();

    private boolean started;
    private boolean active;
    private Status status;
    private long ticksActive;
    private int raidCooldownTicks;
    private int postRaidTicks;
    private int celebrationTicks;

    public Raid(int uniqueId, Collection<Faction> factions, ServerWorld level, RaidTarget raidTarget) {
        this.id = uniqueId;
        this.factions = factions;
        if(this.factions.isEmpty()){
            this.factions.add(Factions.getDefaultFaction());
        }
        this.level = level;
        this.raidTarget = raidTarget;
        this.numGroups = this.getNumGroups(level.getDifficulty(), raidTarget);
        this.active = true;
        this.raidEvent.setPercent(0.0F);
        this.status = Status.ONGOING;
    }
    public Raid(ServerWorld level, CompoundNBT compoundNBT) {
        this.level = level;
        ListNBT factionListnbt = compoundNBT.getList("Factions", 10);
        factions = new ArrayList<>();
        for(int i = 0; i < factionListnbt.size(); ++i) {
            CompoundNBT compoundnbt = factionListnbt.getCompound(i);
            ResourceLocation factionName = new ResourceLocation(compoundnbt.getString("Faction"));
            if(Factions.factionExists(factionName)) {
                Faction faction = Factions.getFaction(factionName);
                this.factions.add(faction);
            }
        }
        if(this.factions.isEmpty()){
            this.factions.add(Factions.getDefaultFaction());
        }
        this.id = compoundNBT.getInt("Id");
        this.started = compoundNBT.getBoolean("Started");
        this.active = compoundNBT.getBoolean("Active");
        this.ticksActive = compoundNBT.getLong("TicksActive");
        this.badOmenLevel = compoundNBT.getInt("BadOmenLevel");
        this.groupsSpawned = compoundNBT.getInt("GroupsSpawned");
        this.raidCooldownTicks = compoundNBT.getInt("PreRaidTicks");
        this.postRaidTicks = compoundNBT.getInt("PostRaidTicks");
        this.totalHealth = compoundNBT.getFloat("TotalHealth");
        this.raidTarget = RaidTargetHelper.load(level, compoundNBT.getCompound("RaidTarget"));
        this.numGroups = compoundNBT.getInt("NumGroups");
        this.status = Status.getByName(compoundNBT.getString("Status"));
        this.heroesOfTheVillage.clear();
        if (compoundNBT.contains("HeroesOfTheVillage", 9)) {
            ListNBT listnbt = compoundNBT.getList("HeroesOfTheVillage", 11);
            for(int i = 0; i < listnbt.size(); ++i) {
                this.heroesOfTheVillage.add(NBTUtil.loadUUID(listnbt.get(i)));
            }
        }
    }

    public int getId() {
        return id;
    }

    public BlockPos getCenter() {
        return this.raidTarget.getTargetBlockPos();
    }

    public void tick() {
        if (!this.isStopped()) {
            if (this.status == Status.ONGOING) {
                boolean flag = this.active;
                this.active = this.level.hasChunkAt(this.raidTarget.getTargetBlockPos());
                if (this.level.getDifficulty() == Difficulty.PEACEFUL) {
                    this.stop();
                    return;
                }
                if (flag != this.active) {
                    this.raidEvent.setVisible(this.active);
                }
                if (!this.active) {
                    return;
                }

                raidTarget.updateTargetBlockPos(level);

                if (raidTarget.checkLossCondition(level)) {
                    if (this.groupsSpawned > 0) {
                        this.status = Status.LOSS;
                    } else {
                        this.stop();
                    }
                }

                ++this.ticksActive;
                if (this.ticksActive >= 48000L) {
                    this.stop();
                    return;
                }

                int i = this.getTotalRaidersAlive();
                if (i == 0 && this.hasMoreWaves()) {
                    if(raidCooldownTick()){
                        return;
                    }
                }

                if (this.ticksActive % 20L == 0L) {
                    this.updatePlayers();
                    this.updateRaiders();
                    if (i > 0) {
                        if (i <= 2) {
                            this.raidEvent.setName(RAID_NAME_COMPONENT.copy().append(" - ").append(new TranslationTextComponent("event.minecraft.raid.raiders_remaining", i)));
                        } else {
                            this.raidEvent.setName(RAID_NAME_COMPONENT);
                        }
                    } else {
                        this.raidEvent.setName(RAID_NAME_COMPONENT);
                    }
                }

                boolean flag3 = false;
                int k = 0;

                while(this.shouldSpawnGroup()) {
                    BlockPos blockpos = this.waveSpawnPos.isPresent() ? this.waveSpawnPos.get() : this.findRandomSpawnPos(k, 20);
                    if (blockpos != null) {
                        this.started = true;
                        this.spawnGroup(blockpos);
                        if (!flag3) {
                            this.playSound(blockpos);
                            flag3 = true;
                        }
                    } else {
                        ++k;
                    }

                    if (k > 3) {
                        this.stop();
                        break;
                    }
                }

                if (this.isStarted() && !this.hasMoreWaves() && i == 0) {
                    if (this.postRaidTicks < 40) {
                        ++this.postRaidTicks;
                    } else {
                        this.status = Status.VICTORY;

                        for(UUID uuid : this.heroesOfTheVillage) {
                            Entity entity = this.level.getEntity(uuid);
                            if (entity instanceof LivingEntity && !entity.isSpectator()) {
                                LivingEntity livingentity = (LivingEntity)entity;
                                livingentity.addEffect(new EffectInstance(Effects.HERO_OF_THE_VILLAGE, 48000, this.badOmenLevel - 1, false, false, true));
                                if (livingentity instanceof ServerPlayerEntity) {
                                    ServerPlayerEntity serverplayerentity = (ServerPlayerEntity)livingentity;
                                    serverplayerentity.awardStat(Stats.RAID_WIN);
                                    CriteriaTriggers.RAID_WIN.trigger(serverplayerentity);
                                }
                            }
                        }
                    }
                }
            } else if (this.isOver()) {
                ++this.celebrationTicks;
                if (this.celebrationTicks >= 600) {
                    this.stop();
                    return;
                }

                if (this.celebrationTicks % 20 == 0) {
                    this.updatePlayers();
                    this.raidEvent.setVisible(true);
                    if (this.isVictory()) {
                        this.raidEvent.setPercent(0.0F);
                        this.raidEvent.setName(RAID_BAR_VICTORY_COMPONENT);
                    } else {
                        this.raidEvent.setName(RAID_BAR_DEFEAT_COMPONENT);
                    }
                }
            }
        }
    }

    private void playSound(BlockPos p_221293_1_) {
        float f = 13.0F;
        int i = 64;
        Collection<ServerPlayerEntity> collection = this.raidEvent.getPlayers();

        for(ServerPlayerEntity serverplayerentity : this.level.players()) {
            Vector3d vector3d = serverplayerentity.position();
            Vector3d vector3d1 = Vector3d.atCenterOf(p_221293_1_);
            float f1 = MathHelper.sqrt((vector3d1.x - vector3d.x) * (vector3d1.x - vector3d.x) + (vector3d1.z - vector3d.z) * (vector3d1.z - vector3d.z));
            double d0 = vector3d.x + (double)(13.0F / f1) * (vector3d1.x - vector3d.x);
            double d1 = vector3d.z + (double)(13.0F / f1) * (vector3d1.z - vector3d.z);
            if (f1 <= 64.0F || collection.contains(serverplayerentity)) {
                serverplayerentity.connection.send(new SPlaySoundEffectPacket(SoundEvents.RAID_HORN, SoundCategory.NEUTRAL, d0, serverplayerentity.getY(), d1, 64.0F, 1.0F));
            }
        }

    }

    private boolean raidCooldownTick() {
        if (this.raidCooldownTicks <= 0) {
            if (this.raidCooldownTicks == 0 && this.groupsSpawned > 0) {
                this.raidCooldownTicks = 300;
                this.raidEvent.setName(RAID_NAME_COMPONENT);
                return true;
            }
        } else {
            boolean flag1 = this.waveSpawnPos.isPresent();
            boolean flag2 = !flag1 && this.raidCooldownTicks % 5 == 0;
            if (flag1 && !this.level.getChunkSource().isEntityTickingChunk(new ChunkPos(this.waveSpawnPos.get()))) {
                flag2 = true;
            }

            if (flag2) {
                int j = 0;
                if (this.raidCooldownTicks < 100) {
                    j = 1;
                } else if (this.raidCooldownTicks < 40) {
                    j = 2;
                }

                this.waveSpawnPos = this.getValidSpawnPos(j);
            }

            if (this.raidCooldownTicks == 300 || this.raidCooldownTicks % 20 == 0) {
                this.updatePlayers();
            }

            --this.raidCooldownTicks;
            this.raidEvent.setPercent(MathHelper.clamp((float)(300 - this.raidCooldownTicks) / 300.0F, 0.0F, 1.0F));
        }
        return false;
    }

    private boolean shouldSpawnGroup() {
        return this.raidCooldownTicks == 0 && (this.groupsSpawned < this.numGroups) && this.getTotalRaidersAlive() == 0;
    }

    private void spawnGroup(BlockPos spawnBlockPos) {
        int waveNumber = this.groupsSpawned + 1;
        this.totalHealth = 0.0F;

        float waveMultiplier = STARTING_WAVE_MULTIPLIER.get() + (this.groupsSpawned * MULTIPLIER_INCREASE_PER_WAVE.get());
        float spreadMultiplier = (level.random.nextFloat()*WAVE_TARGET_STRENGTH_SPREAD.get()*2)+1.0F-WAVE_TARGET_STRENGTH_SPREAD.get();
        float difficultyMultiplier = getDifficultyMultiplier(level.getDifficulty());
        int targetStrength = (int) Math.floor(raidTarget.getTargetStrength() * waveMultiplier * spreadMultiplier * difficultyMultiplier);
        Map<Faction, Integer> factionFractions = determineFactionFractions(targetStrength);
        factionFractions.entrySet().forEach(entry -> spawnGroupForFaction(spawnBlockPos, waveNumber, entry.getValue(), entry.getKey()));

        this.waveSpawnPos = Optional.empty();
        ++this.groupsSpawned;
        this.updateBossbar();
    }

    private Map<Faction, Integer> determineFactionFractions(int targetStrength) {
        Map<Faction, Integer> factionFractions = new HashMap<>();
        int perFactionStrength = (int) Math.floor(targetStrength / factions.size());
        factions.forEach(faction -> factionFractions.merge(faction, perFactionStrength, Integer::sum));
        return factionFractions;
    }

    private void spawnGroupForFaction(BlockPos spawnBlockPos, int waveNumber, int targetStrength, Faction faction) {
        int mobsFraction = (int) Math.floor(targetStrength * faction.getRaidConfig().getMobsFraction());

        int waveStrength = 0;
        Map<FactionEntityType, Integer> waveFactionEntities = determineMobs(mobsFraction, waveNumber, faction);
        Map<FactionEntityType, List<MobEntity>> entities = new HashMap<>();
        // Collect Entities
        //TODO: Determine Leader!
        for (Map.Entry<FactionEntityType, Integer> entry : waveFactionEntities.entrySet()) {
            FactionEntityType factionEntityType = entry.getKey();
            Integer amount = entry.getValue();
            for (int i = 0; i <amount; i++) {
                Entity entity = factionEntityType.createEntity(level, faction, false);
                if(entity instanceof MobEntity) {
                    entities.computeIfAbsent(factionEntityType, key -> new ArrayList<>()).add((MobEntity) entity);
                    waveStrength += factionEntityType.getStrength();
                }
            }
        }
        // Apply Boosts
        while(waveStrength < targetStrength) {
            Random random = this.level.random;
            FactionEntityType randomFactionEntityType = getRandomItem(new ArrayList<>(entities.keySet()), random);
            MobEntity randomEntity = getRandomItem(entities.get(randomFactionEntityType), random);
            Boost boost = Boosts.getRandomBoostForEntity(random, randomEntity, randomFactionEntityType.getBoostConfig().getWhitelistBoosts(), randomFactionEntityType.getBoostConfig().getBlacklistBoosts());
            if(boost == null){
                break;
            }
            waveStrength += boost.apply(randomEntity);
        }
        //Add to Raid
        entities.entrySet().stream().flatMap(factionEntityListEntry -> factionEntityListEntry.getValue().stream()).forEach(raiderEntity -> this.joinRaid(waveNumber, raiderEntity, spawnBlockPos, false));
    }

    private Map<FactionEntityType, Integer> determineMobs(int targetStrength, int waveNumber, Faction faction) {
        Map<FactionEntityType, Integer> waveFactionEntities = new HashMap<>();
        int selectedStrength = 0;
        List<Pair<FactionEntityType, Integer>> weightMap = faction.getWeightMapForWave(waveNumber);
        while(selectedStrength < targetStrength) {
            FactionEntityType randomEntry = getRandomEntry(weightMap, level.random);
            waveFactionEntities.merge(randomEntry, 1, Integer::sum);
            selectedStrength += randomEntry.getStrength();
        }
        return waveFactionEntities;
    }

    public float getDifficultyMultiplier(Difficulty difficulty) {
        switch(difficulty) {
            case EASY:
                return TARGET_STRENGTH_DIFFICULTY_MULTIPLIER_EASY.get();
            case NORMAL:
                return TARGET_STRENGTH_DIFFICULTY_MULTIPLIER_NORMAL.get();
            case HARD:
                return TARGET_STRENGTH_DIFFICULTY_MULTIPLIER_HARD.get();
            default:
                return 0;
        }
    }

    public void setLeader(int pRaidId, MobEntity mobEntity) {
        this.groupToLeaderMap.put(pRaidId, mobEntity);
    }

    public void removeLeader(int wave) {
        this.groupToLeaderMap.remove(wave);
    }

    public void joinRaid(int pWave, MobEntity mobEntity, BlockPos spawnBlockPos, boolean p_221317_4_) {
        boolean flag = this.addWaveMob(pWave, mobEntity, true);
        if (flag) {
            LazyOptional<IRaider> raiderCapabilityLazy = RaiderHelper.getRaiderCapabilityLazy(mobEntity);
            if(raiderCapabilityLazy.isPresent()) {
                IRaider iRaider = raiderCapabilityLazy.orElseGet(RAIDER_CAPABILITY::getDefaultInstance);
                iRaider.setRaid(this);
                iRaider.setWave(pWave);
                iRaider.setCanJoinRaid(true);
                iRaider.setTicksOutsideRaid(0);
                if (!p_221317_4_ && spawnBlockPos != null) {
                    mobEntity.setPos((double) spawnBlockPos.getX() + 0.5D, (double) spawnBlockPos.getY() + 1.0D, (double) spawnBlockPos.getZ() + 0.5D);
                    mobEntity.finalizeSpawn(this.level, this.level.getCurrentDifficultyAt(spawnBlockPos), SpawnReason.EVENT, (ILivingEntityData) null, (CompoundNBT) null);
                    mobEntity.setOnGround(true);
                    this.level.addFreshEntityWithPassengers(mobEntity);
                }
            }
        }
    }

    public boolean addWaveMob(int wave, MobEntity mobEntity, boolean fresh) {
        this.groupRaiderMap.computeIfAbsent(wave, (p_221323_0_) -> {
            return Sets.newHashSet();
        });
        Set<MobEntity> set = this.groupRaiderMap.get(wave);
        MobEntity abstractraiderentity = null;

        for(MobEntity abstractraiderentity1 : set) {
            if (abstractraiderentity1.getUUID().equals(mobEntity.getUUID())) {
                abstractraiderentity = abstractraiderentity1;
                break;
            }
        }

        if (abstractraiderentity != null) {
            set.remove(abstractraiderentity);
            set.add(mobEntity);
        }

        set.add(mobEntity);
        if (fresh) {
            this.totalHealth += mobEntity.getHealth();
        }

        this.updateBossbar();
        return true;
    }

    public void removeFromRaid(MobEntity mobEntity, boolean p_221322_2_) {
        LazyOptional<IRaider> raiderCapabilityLazy = RaiderHelper.getRaiderCapabilityLazy(mobEntity);
        if(raiderCapabilityLazy.isPresent()) {
            IRaider iRaider = raiderCapabilityLazy.orElseGet(RAIDER_CAPABILITY::getDefaultInstance);
            Set<MobEntity> set = this.groupRaiderMap.get(iRaider.getWave());
            if (set != null) {
                boolean flag = set.remove(mobEntity);
                if (flag) {
                    if (p_221322_2_) {
                        this.totalHealth -= mobEntity.getHealth();
                    }

                    iRaider.setRaid(null);
                    this.updateBossbar();
                }
            }
        }
    }

    private void updateRaiders() {
        Iterator<Set<MobEntity>> iterator = this.groupRaiderMap.values().iterator();
        Set<MobEntity> set = Sets.newHashSet();

        while(iterator.hasNext()) {
            Set<MobEntity> set1 = iterator.next();

            for(MobEntity mobEntity : set1) {
                BlockPos blockpos = mobEntity.blockPosition();
                if (!mobEntity.removed && mobEntity.level.dimension() == this.level.dimension() && !(this.getCenter().distSqr(blockpos) >= 12544.0D)) {
                    if (mobEntity.tickCount > 600) {
                        IRaider raiderCapability = RaiderHelper.getRaiderCapability(mobEntity);
                        if (this.level.getEntity(mobEntity.getUUID()) == null) {
                            set.add(mobEntity);
                        }

                        if (!this.level.isVillage(blockpos) && mobEntity.getNoActionTime() > 2400) {
                            raiderCapability.setTicksOutsideRaid(raiderCapability.getTicksOutsideRaid() + 1); //TODO: RaiderCapability: TickOutsideRaid
                        }

                        if (raiderCapability.getTicksOutsideRaid() >= 30) {
                            set.add(mobEntity);
                        }
                    }
                } else {
                    set.add(mobEntity);
                }
            }
        }

        for(MobEntity abstractraiderentity1 : set) {
            this.removeFromRaid(abstractraiderentity1, true); //TODO: RaiderCapability: Remove From Raid
        }
    }

    public void updateBossbar() {
        this.raidEvent.setPercent(MathHelper.clamp(this.getHealthOfLivingRaiders() / this.totalHealth, 0.0F, 1.0F));
    }

    public float getHealthOfLivingRaiders() {
        float f = 0.0F;

        for(Set<MobEntity> set : this.groupRaiderMap.values()) {
            for(MobEntity mobEntity : set) {
                f += mobEntity.getHealth();
            }
        }

        return f;
    }

    private Optional<BlockPos> getValidSpawnPos(int p_221313_1_) {
        for(int i = 0; i < 3; ++i) {
            BlockPos blockpos = this.findRandomSpawnPos(p_221313_1_, 1);
            if (blockpos != null) {
                return Optional.of(blockpos);
            }
        }

        return Optional.empty();
    }
    @Nullable
    private BlockPos findRandomSpawnPos(int outerAttempt, int maxInnerAttempts) {
        int i = 2 - outerAttempt;
        BlockPos.Mutable blockpos$mutable = new BlockPos.Mutable();

        for(int i1 = 0; i1 < maxInnerAttempts; ++i1) {
            float f = this.level.random.nextFloat() * ((float)Math.PI * 2F);
            int j = this.raidTarget.getTargetBlockPos().getX() + MathHelper.floor(MathHelper.cos(f) * 32.0F * (float)i) + this.level.random.nextInt(5);
            int l = this.raidTarget.getTargetBlockPos().getZ() + MathHelper.floor(MathHelper.sin(f) * 32.0F * (float)i) + this.level.random.nextInt(5);
            int k = this.level.getHeight(Heightmap.Type.WORLD_SURFACE, j, l);
            blockpos$mutable.set(j, k, l);
            if (raidTarget.isValidSpawnPos(outerAttempt, blockpos$mutable, this.level)) {
                return blockpos$mutable;
            }
        }

        return null;
    }

    public int getNumGroups(Difficulty difficulty, RaidTarget raidTarget) {
        int additionalWaves = raidTarget.getAdditionalWaves();
        switch(difficulty) {
            case EASY:
                return NUMBER_WAVES_EASY.get() + additionalWaves;
            case NORMAL:
                return NUMBER_WAVES_NORMAL.get() + additionalWaves;
            case HARD:
                return NUMBER_WAVES_HARD.get() + additionalWaves;
            default:
                return 0;
        }
    }

    private boolean hasMoreWaves() {
        return !this.isFinalWave();
    }

    private boolean isFinalWave() {
        return this.getGroupsSpawned() == this.numGroups;
    }

    public int getGroupsSpawned() {
        return groupsSpawned;
    }

    private Predicate<ServerPlayerEntity> validPlayer() {
        return (serverPlayerEntity) -> {
            BlockPos blockpos = serverPlayerEntity.blockPosition();
            IRaidManager cap = getRaidManagerCapability(this.level);
            return serverPlayerEntity.isAlive() && cap.getRaidAt(blockpos) == this;
        };
    }

    private void updatePlayers() {
        Set<ServerPlayerEntity> set = Sets.newHashSet(this.raidEvent.getPlayers());
        List<ServerPlayerEntity> list = this.level.getPlayers(this.validPlayer());

        for(ServerPlayerEntity serverplayerentity : list) {
            if (!set.contains(serverplayerentity)) {
                this.raidEvent.addPlayer(serverplayerentity);
            }
        }

        for(ServerPlayerEntity serverplayerentity1 : set) {
            if (!list.contains(serverplayerentity1)) {
                this.raidEvent.removePlayer(serverplayerentity1);
            }
        }

    }

    public int getTotalRaidersAlive() {
        return this.groupRaiderMap.values().stream().mapToInt(Set::size).sum();
    }

    public void stop() {
        this.active = false;
        this.raidEvent.removeAllPlayers();
        this.status = Status.STOPPED;
    }

    public void addHeroOfTheVillage(Entity p_221311_1_) {
        this.heroesOfTheVillage.add(p_221311_1_.getUUID());
    }


    public boolean isBetweenWaves() {
        return this.hasFirstWaveSpawned() && this.getTotalRaidersAlive() == 0 && this.raidCooldownTicks > 0;
    }

    public boolean hasFirstWaveSpawned() {
        return this.groupsSpawned > 0;
    }

    public boolean isStarted() {
        return this.started;
    }

    public boolean isActive() {
        return this.active;
    }

    public boolean isStopped() {
        return this.status == Status.STOPPED;
    }

    public boolean isVictory() {
        return this.status == Status.VICTORY;
    }

    public boolean isLoss() {
        return this.status == Status.LOSS;
    }

    public boolean isOver() {
        return this.isVictory() || this.isLoss();
    }
    
    private enum Status {
        ONGOING,
        VICTORY,
        LOSS,
        STOPPED;

        private static final Status[] VALUES = values();

        private static Status getByName(String pName) {
            for(Status raid$status : VALUES) {
                if (pName.equalsIgnoreCase(raid$status.name())) {
                    return raid$status;
                }
            }

            return ONGOING;
        }

        public String getName() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    public CompoundNBT save(CompoundNBT pNbt) {
        pNbt.putInt("Id", this.id);
        pNbt.putBoolean("Started", this.started);
        pNbt.putBoolean("Active", this.active);
        pNbt.putLong("TicksActive", this.ticksActive);
        pNbt.putInt("BadOmenLevel", this.badOmenLevel);
        pNbt.putInt("GroupsSpawned", this.groupsSpawned);
        pNbt.putInt("PreRaidTicks", this.raidCooldownTicks);
        pNbt.putInt("PostRaidTicks", this.postRaidTicks);
        pNbt.putFloat("TotalHealth", this.totalHealth);
        pNbt.putInt("NumGroups", this.numGroups);
        pNbt.putString("Status", this.status.getName());

        ListNBT factionListnbt = new ListNBT();
        for(Faction faction : this.factions) {
            CompoundNBT compoundnbt = new CompoundNBT();
            compoundnbt.putString("Faction", faction.getName().toString());
            factionListnbt.add(compoundnbt);
        }
        pNbt.put("Factions", factionListnbt);

        CompoundNBT raidTargetNbt = new CompoundNBT();
        raidTarget.save(raidTargetNbt);
        pNbt.put("RaidTarget", raidTargetNbt);

        ListNBT listnbt = new ListNBT();

        for(UUID uuid : this.heroesOfTheVillage) {
            listnbt.add(NBTUtil.createUUID(uuid));
        }
        pNbt.put("HeroesOfTheVillage", listnbt);
        return pNbt;
    }
}