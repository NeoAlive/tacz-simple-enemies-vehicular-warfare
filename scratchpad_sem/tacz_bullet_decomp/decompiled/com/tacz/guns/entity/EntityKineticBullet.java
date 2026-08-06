package com.tacz.guns.entity;

import com.google.common.collect.Lists;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.GunProperty;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ITargetEntity;
import com.tacz.guns.api.entity.KnockBackModifier;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import com.tacz.guns.api.event.common.GunDamageSourcePart;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.client.particle.AmmoParticleSpawner;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.config.sync.SyncConfig;
import com.tacz.guns.entity.EntityKineticBullet.EntityResult;
import com.tacz.guns.entity.EntityKineticBullet.MaybeMultipartEntity;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.init.ModDamageTypes;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.network.message.event.ServerMessageGunHurt;
import com.tacz.guns.network.message.event.ServerMessageGunKill;
import com.tacz.guns.particles.BulletHoleOption;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import com.tacz.guns.resource.modifier.custom.DamageModifier;
import com.tacz.guns.resource.modifier.custom.ExplosionModifier;
import com.tacz.guns.resource.modifier.custom.IgniteModifier;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.ExplosionData;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.resource.pojo.data.gun.Ignite;
import com.tacz.guns.resource.pojo.data.gun.ExtraDamage.DistanceDamagePair;
import com.tacz.guns.util.EntityUtil;
import com.tacz.guns.util.ExplodeUtil;
import com.tacz.guns.util.TacHitResult;
import com.tacz.guns.util.block.BlockRayTrace;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.EntityType.Builder;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkHooks;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus.Internal;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3f;

public class EntityKineticBullet extends Projectile implements IEntityAdditionalSpawnData {
   public static final EntityType<EntityKineticBullet> TYPE = Builder.m_20704_(EntityKineticBullet::new, MobCategory.MISC)
      .m_20698_()
      .m_20716_()
      .m_20719_()
      .m_20699_(0.0625F, 0.0625F)
      .m_20702_(5)
      .m_20717_(5)
      .setShouldReceiveVelocityUpdates(false)
      .m_20712_("bullet");
   public static final TagKey<EntityType<?>> USE_MAGIC_DAMAGE_ON = TagKey.m_203882_(Registries.f_256939_, new ResourceLocation("tacz:use_magic_damage_on"));
   public static final TagKey<EntityType<?>> USE_VOID_DAMAGE_ON = TagKey.m_203882_(Registries.f_256939_, new ResourceLocation("tacz:use_void_damage_on"));
   public static final TagKey<EntityType<?>> PRETEND_MELEE_DAMAGE_ON = TagKey.m_203882_(
      Registries.f_256939_, new ResourceLocation("tacz:pretend_melee_damage_on")
   );
   public static final String TRACER_COLOR_OVERRIDER_KEY = "tacz:tracer_override";
   public static final String TRACER_SIZE_OVERRIDER_KEY = "tacz:tracer_size";
   private static final ExplosionData DEFAULT_EXPLOSION_DATA = new ExplosionData(false, 0.0F, 0.0F, false, 30.0F, false);
   private ResourceLocation ammoId = DefaultAssets.EMPTY_AMMO_ID;
   private int life = 200;
   @Deprecated
   private float speed = 1.0F;
   private float gravity = 0.0F;
   private float friction = 0.01F;
   private LinkedList<DistanceDamagePair> damageAmount = Lists.newLinkedList();
   private float distanceAmount = 0.0F;
   private float knockback = 0.0F;
   private boolean explosion = false;
   private boolean igniteEntity = false;
   private boolean igniteBlock = false;
   private int igniteEntityTime = 2;
   private float explosionDamage = 3.0F;
   private float explosionRadius = 3.0F;
   private int explosionDelayCount = Integer.MAX_VALUE;
   private boolean explosionKnockback = false;
   private boolean explosionDestroyBlock = false;
   private float damageModifier = 1.0F;
   private int pierce = 1;
   private Vec3 startPos;
   private boolean isTracerAmmo;
   private float cameraXRot;
   private float cameraYRot;
   private Vector3f firstPersonRenderOffset;
   private ResourceLocation gunId;
   private ResourceLocation gunDisplayId;
   private float armorIgnore;
   private float headShot;
   private float shotDamageMultiplier = 1.0F;

   public EntityKineticBullet(EntityType<? extends Projectile> type, Level worldIn) {
      super(type, worldIn);
   }

   public EntityKineticBullet(EntityType<? extends Projectile> type, double x, double y, double z, Level worldIn) {
      this(type, worldIn);
      this.m_6034_(x, y, z);
   }

   public EntityKineticBullet(
      Level worldIn,
      LivingEntity throwerIn,
      ItemStack gunItem,
      ResourceLocation ammoId,
      ResourceLocation gunId,
      ResourceLocation gunDisplayId,
      boolean isTracerAmmo,
      GunData gunData,
      BulletData bulletData
   ) {
      this(TYPE, worldIn, throwerIn, gunItem, ammoId, gunId, gunDisplayId, isTracerAmmo, gunData, bulletData);
   }

   public EntityKineticBullet(
      Level worldIn,
      LivingEntity throwerIn,
      ItemStack gunItem,
      ResourceLocation ammoId,
      ResourceLocation gunId,
      boolean isTracerAmmo,
      GunData gunData,
      BulletData bulletData
   ) {
      this(TYPE, worldIn, throwerIn, gunItem, ammoId, gunId, DefaultAssets.DEFAULT_GUN_DISPLAY_ID, isTracerAmmo, gunData, bulletData);
   }

   protected EntityKineticBullet(
      EntityType<? extends Projectile> type,
      Level worldIn,
      LivingEntity throwerIn,
      ItemStack gunItem,
      ResourceLocation ammoId,
      ResourceLocation gunId,
      ResourceLocation gunDisplayId,
      boolean isTracerAmmo,
      GunData gunData,
      BulletData bulletData
   ) {
      this(type, throwerIn.m_20185_(), throwerIn.m_20188_() - 0.1F, throwerIn.m_20189_(), worldIn);
      this.m_5602_(throwerIn);
      this.gunId = gunId;
      AttachmentCacheProperty cacheProperty = Objects.requireNonNull(IGunOperator.fromLivingEntity(throwerIn).getCacheProperty());
      float armorIgnore = this.modifyProperty(GunProperties.ARMOR_IGNORE, Float.class, (Float)cacheProperty.getCache(GunProperties.ARMOR_IGNORE));
      float headshot = this.modifyProperty(GunProperties.HEADSHOT_MULTIPLIER, Float.class, (Float)cacheProperty.getCache(GunProperties.HEADSHOT_MULTIPLIER));
      float knockback = this.modifyProperty(GunProperties.KNOCKBACK, Float.class, (Float)cacheProperty.getCache(GunProperties.KNOCKBACK));
      this.armorIgnore = Mth.m_14036_(armorIgnore, 0.0F, 1.0F);
      this.headShot = Math.max(headshot, 0.0F);
      this.knockback = Math.max(knockback, 0.0F);
      this.ammoId = ammoId;
      float lifeSecond = this.modifyProperty("bullet_life", Float.class, bulletData.getLifeSecond());
      this.life = Mth.m_14045_((int)(lifeSecond * 20.0F), 1, Integer.MAX_VALUE);
      this.gravity = Mth.m_14036_(this.modifyProperty("bullet_gravity", Float.class, bulletData.getGravity()), 0.0F, Float.MAX_VALUE);
      this.friction = Mth.m_14036_(this.modifyProperty("bullet_friction", Float.class, bulletData.getFriction()), 0.0F, Float.MAX_VALUE);
      Ignite ignite = (Ignite)cacheProperty.getCache(IgniteModifier.ID);
      this.igniteEntity = this.modifyProperty("ignite_entity", Boolean.class, bulletData.getIgnite().isIgniteEntity() || ignite.isIgniteEntity());
      this.igniteEntityTime = Math.max(this.modifyProperty("ignite_entity_time", Integer.class, bulletData.getIgniteEntityTime()), 0);
      this.igniteBlock = this.modifyProperty("ignite_block", Boolean.class, bulletData.getIgnite().isIgniteBlock() || ignite.isIgniteBlock());
      this.damageAmount = (LinkedList<DistanceDamagePair>)cacheProperty.getCache(DamageModifier.ID);
      this.distanceAmount = this.modifyProperty(GunProperties.EFFECTIVE_RANGE, Float.class, (Float)cacheProperty.getCache(GunProperties.EFFECTIVE_RANGE));
      int pierce = this.modifyProperty(GunProperties.PIERCE, Integer.class, (Integer)cacheProperty.getCache(GunProperties.PIERCE));
      this.pierce = Mth.m_14045_(pierce, 1, Integer.MAX_VALUE);
      ExplosionData explosionData = Objects.requireNonNullElse((ExplosionData)cacheProperty.getCache(ExplosionModifier.ID), DEFAULT_EXPLOSION_DATA);
      this.explosion = this.modifyProperty("explode_enabled", Boolean.class, explosionData.isExplode());
      if (this.explosion) {
         Float explosionDamage = this.modifyProperty("explosion_damage", Float.class, explosionData.getDamage());
         Float explosionRadius = this.modifyProperty("explosion_radius", Float.class, explosionData.getRadius());
         this.explosionDamage = (float)Mth.m_14008_(
            (double)explosionDamage.floatValue() * (Double)SyncConfig.DAMAGE_BASE_MULTIPLIER.get(), 0.0, Float.MAX_VALUE
         );
         this.explosionRadius = Mth.m_14036_(explosionRadius, 0.0F, Float.MAX_VALUE);
         this.explosionKnockback = this.modifyProperty("explosion_knockback", Boolean.class, explosionData.isKnockback());
         int delayTickCount = (int)(this.modifyProperty("explosion_delay", Float.class, explosionData.getDelay()) * 20.0F);
         if (delayTickCount < 0) {
            delayTickCount = Integer.MAX_VALUE;
         }

         this.explosionDestroyBlock = (Boolean)AmmoConfig.EXPLOSIVE_AMMO_DESTROYS_BLOCK.get()
            && this.modifyProperty("explosion_destroys_block", Boolean.class, explosionData.isDestroyBlock());
         this.explosionDelayCount = Math.max(delayTickCount, 1);
      }

      double posX = throwerIn.f_19790_ + (throwerIn.m_20185_() - throwerIn.f_19790_) / 2.0;
      double posY = throwerIn.f_19791_ + (throwerIn.m_20186_() - throwerIn.f_19791_) / 2.0 + (double)throwerIn.m_20192_();
      double posZ = throwerIn.f_19792_ + (throwerIn.m_20189_() - throwerIn.f_19792_) / 2.0;
      this.m_6034_(posX, posY, posZ);
      this.startPos = this.m_20182_();
      this.isTracerAmmo = isTracerAmmo;
      this.gunDisplayId = gunDisplayId;
   }

   @Internal
   public void applyShotgunDamageSpread(int bulletCount) {
      if (bulletCount > 1) {
         this.damageModifier = 1.0F / (float)bulletCount;
      }
   }

   @Internal
   public void setShotDamageMultiplier(float multiplier) {
      this.shotDamageMultiplier = Math.max(multiplier, 0.0F);
   }

   protected void m_8097_() {
   }

   public void m_8119_() {
      super.m_8119_();
      this.onBulletTick();
      if (this.m_9236_().f_46443_) {
         DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> AmmoParticleSpawner.addParticle(this));
      }

      Vec3 movement = this.m_20184_();
      double x = movement.f_82479_;
      double y = movement.f_82480_;
      double z = movement.f_82481_;
      double distance = movement.m_165924_();
      this.m_146922_((float)Math.toDegrees(Mth.m_14136_(x, z)));
      this.m_146926_((float)Math.toDegrees(Mth.m_14136_(y, distance)));
      if (this.f_19860_ == 0.0F && this.f_19859_ == 0.0F) {
         this.f_19859_ = this.m_146908_();
         this.f_19860_ = this.m_146909_();
      }

      this.m_146926_(m_37273_(this.f_19860_, this.m_146909_()));
      this.m_146922_(m_37273_(this.f_19859_, this.m_146908_()));
      double nextPosX = this.m_20185_() + x;
      double nextPosY = this.m_20186_() + y;
      double nextPosZ = this.m_20189_() + z;
      this.m_6034_(nextPosX, nextPosY, nextPosZ);
      float friction = this.friction;
      float gravity = this.gravity;
      if (this.m_20069_()) {
         for (int i = 0; i < 4; i++) {
            this.m_9236_().m_7106_(ParticleTypes.f_123795_, nextPosX - x * 0.25, nextPosY - y * 0.25, nextPosZ - z * 0.25, x, y, z);
         }

         friction = 0.4F;
         gravity *= 0.6F;
      }

      this.m_20256_(this.m_20184_().m_82490_((double)(1.0F - friction)));
      this.m_20256_(this.m_20184_().m_82520_(0.0, (double)(-gravity), 0.0));
      if (this.f_19797_ >= this.life - 1) {
         this.m_146870_();
      }
   }

   protected void onBulletTick() {
      if (!this.m_9236_().m_5776_()) {
         if (this.explosion) {
            if (this.explosionDelayCount <= 0) {
               ExplodeUtil.createExplosion(
                  this.m_19749_(), this, this.explosionDamage, this.explosionRadius, this.explosionKnockback, this.explosionDestroyBlock, this.m_20182_()
               );
               this.m_146870_();
               return;
            }

            this.explosionDelayCount--;
         }

         Vec3 startVec = this.m_20182_();
         Vec3 endVec = startVec.m_82549_(this.m_20184_());
         HitResult result = BlockRayTrace.rayTraceBlocks(this.m_9236_(), new ClipContext(startVec, endVec, Block.COLLIDER, Fluid.NONE, this));
         BlockHitResult resultB = (BlockHitResult)result;
         if (resultB.m_6662_() != Type.MISS) {
            endVec = resultB.m_82450_();
         }

         List<EntityResult> hitEntities = null;
         if (this.pierce > 1 && !this.explosion) {
            hitEntities = EntityUtil.findEntitiesOnPath(this, startVec, endVec);
         } else {
            EntityResult entityResult = EntityUtil.findEntityOnPath(this, startVec, endVec);
            if (entityResult != null) {
               hitEntities = Collections.singletonList(entityResult);
            }
         }

         if (hitEntities != null && !hitEntities.isEmpty()) {
            EntityResult[] hitEntityResult = hitEntities.toArray(new EntityResult[0]);

            for (int i = 0; (i < this.pierce || i < 1) && i < hitEntityResult.length - 1; i++) {
               int k = i;

               for (int j = i + 1; j < hitEntityResult.length; j++) {
                  if (hitEntityResult[j].hitVec.m_82554_(startVec) < hitEntityResult[k].hitVec.m_82554_(startVec)) {
                     k = j;
                  }
               }

               EntityResult t = hitEntityResult[i];
               hitEntityResult[i] = hitEntityResult[k];
               hitEntityResult[k] = t;
            }

            for (EntityResult entityResultx : hitEntityResult) {
               result = new TacHitResult(entityResultx);
               this.onHitEntity((TacHitResult)result, startVec, endVec);
               this.pierce--;
               if (this.pierce < 1 || this.explosion) {
                  this.m_146870_();
                  return;
               }
            }
         }

         this.onHitBlock(resultB, startVec, endVec);
      }
   }

   public void shoot(double pitch, double yaw, float pVelocity, Vector2d vector2d) {
      Vector3d left = new Vector3d(vector2d.x, vector2d.y, 8.0);
      left.rotateX(pitch * (float) (Math.PI / 180.0));
      left.rotateY(-yaw * (float) (Math.PI / 180.0));
      Vec3 vec3 = new Vec3(left.x, left.y, left.z).m_82541_().m_82490_((double)pVelocity);
      this.m_20334_(vec3.f_82479_, vec3.f_82480_, vec3.f_82481_);
      double d0 = vec3.m_165924_();
      this.m_146922_((float)(Mth.m_14136_(vec3.f_82479_, vec3.f_82481_) * 180.0F / (float)Math.PI));
      this.m_146926_((float)(Mth.m_14136_(vec3.f_82480_, d0) * 180.0F / (float)Math.PI));
      this.f_19859_ = this.m_146908_();
      this.f_19860_ = this.m_146909_();
   }

   public void shootFromRotation(Entity pShooter, float pX, float pY, float pZ, float pVelocity, Vector2d vector2d) {
      this.shoot((double)pX, (double)pY, pVelocity, vector2d);
      Vec3 vec3 = pShooter.m_20184_();
      this.m_20256_(this.m_20184_().m_82520_(vec3.f_82479_, pShooter.m_20096_() ? 0.0 : vec3.f_82480_, vec3.f_82481_));
   }

   protected void onHitEntity(TacHitResult result, Vec3 startVec, Vec3 endVec) {
      if (result.m_82443_() instanceof ITargetEntity targetEntity) {
         DamageSource source = this.m_269291_().m_269390_(this, this.m_19749_());
         targetEntity.onProjectileHit(this, result, source, this.getDamage(result.m_82450_()));
      } else {
         Entity entity = result.m_82443_();
         Entity owner = this.m_19749_();
         LivingEntity attacker = owner instanceof LivingEntity ? (LivingEntity)owner : null;
         Pair<DamageSource, DamageSource> sources = this.createDamageSources(MaybeMultipartEntity.of(entity));
         boolean headshot = result.isHeadshot();
         float damage = this.getDamage(result.m_82450_());
         float headShotMultiplier = Math.max(this.headShot, 0.0F);
         EntityHurtByGunEvent.Pre preEvent = new EntityHurtByGunEvent.Pre(
            this, entity, attacker, this.gunId, this.gunDisplayId, damage, sources, headshot, headShotMultiplier, LogicalSide.SERVER
         );
         boolean cancelled = MinecraftForge.EVENT_BUS.post(preEvent);
         if (!cancelled) {
            entity = preEvent.getHurtEntity();
            MaybeMultipartEntity parts = MaybeMultipartEntity.of(entity);
            attacker = preEvent.getAttacker();
            ResourceLocation newGunId = preEvent.getGunId();
            damage = preEvent.getBaseAmount();
            sources = Pair.of(preEvent.getDamageSource(GunDamageSourcePart.NON_ARMOR_PIERCING), preEvent.getDamageSource(GunDamageSourcePart.ARMOR_PIERCING));
            headshot = preEvent.isHeadShot();
            headShotMultiplier = preEvent.getHeadshotMultiplier();
            if (entity != null) {
               if (this.igniteEntity && (Boolean)AmmoConfig.IGNITE_ENTITY.get()) {
                  entity.m_20254_(this.igniteEntityTime);
                  if (this.m_9236_() instanceof ServerLevel serverLevel) {
                     serverLevel.m_8767_(
                        ParticleTypes.f_123756_, entity.m_20185_(), entity.m_20186_() + (double)entity.m_20192_(), entity.m_20189_(), 1, 0.0, 0.0, 0.0, 0.0
                     );
                  }
               }

               if (headshot) {
                  damage *= headShotMultiplier;
               }

               if (parts.core() instanceof LivingEntity livingCore) {
                  KnockBackModifier modifier = KnockBackModifier.fromLivingEntity(livingCore);
                  modifier.setKnockBackStrength((double)this.knockback);
                  this.tacAttackEntity(parts, damage, sources);
                  modifier.resetKnockBackStrength();
               } else {
                  this.tacAttackEntity(parts, damage, sources);
               }

               if (this.explosion) {
                  parts.core().f_19802_ = 0;
                  ExplodeUtil.createExplosion(
                     this.m_19749_(), this, this.explosionDamage, this.explosionRadius, this.explosionKnockback, this.explosionDestroyBlock, result.m_82450_()
                  );
               }

               if (parts.core() instanceof LivingEntity livingCore && !this.m_9236_().f_46443_) {
                  int attackerId = attacker == null ? 0 : attacker.m_19879_();
                  if (livingCore.m_21224_()) {
                     MinecraftForge.EVENT_BUS
                        .post(
                           new EntityKillByGunEvent(
                              this, livingCore, attacker, newGunId, this.gunDisplayId, damage, sources, headshot, headShotMultiplier, LogicalSide.SERVER
                           )
                        );
                     NetworkHandler.sendToDimension(
                        new ServerMessageGunKill(
                           this.m_19879_(), livingCore.m_19879_(), attackerId, newGunId, this.gunDisplayId, damage, headshot, headShotMultiplier
                        ),
                        livingCore
                     );
                  } else {
                     MinecraftForge.EVENT_BUS
                        .post(
                           new EntityHurtByGunEvent.Post(
                              this, livingCore, attacker, newGunId, this.gunDisplayId, damage, sources, headshot, headShotMultiplier, LogicalSide.SERVER
                           )
                        );
                     NetworkHandler.sendToDimension(
                        new ServerMessageGunHurt(
                           this.m_19879_(), livingCore.m_19879_(), attackerId, newGunId, this.gunDisplayId, damage, headshot, headShotMultiplier
                        ),
                        livingCore
                     );
                  }
               }
            }
         }
      }
   }

   protected void onHitBlock(BlockHitResult result, Vec3 startVec, Vec3 endVec) {
      if (result.m_6662_() != Type.MISS) {
         BlockPos pos = result.m_82425_();
         Vec3 hitVec = result.m_82450_();
         if (!MinecraftForge.EVENT_BUS.post(new AmmoHitBlockEvent(this.m_9236_(), result, this.m_9236_().m_8055_(pos), this))) {
            super.m_8060_(result);
            if (this.explosion) {
               ExplodeUtil.createExplosion(
                  this.m_19749_(), this, this.explosionDamage, this.explosionRadius, this.explosionKnockback, this.explosionDestroyBlock, hitVec
               );
               this.m_146870_();
            } else {
               if (this.m_9236_() instanceof ServerLevel serverLevel) {
                  BulletHoleOption bulletHoleOption = new BulletHoleOption(
                     result.m_82434_(), result.m_82425_(), this.ammoId.toString(), this.gunId.toString(), this.gunDisplayId.toString()
                  );
                  serverLevel.m_8767_(bulletHoleOption, hitVec.f_82479_, hitVec.f_82480_, hitVec.f_82481_, 1, 0.0, 0.0, 0.0, 0.0);
                  if (this.igniteBlock) {
                     serverLevel.m_8767_(ParticleTypes.f_123756_, hitVec.f_82479_, hitVec.f_82480_, hitVec.f_82481_, 1, 0.0, 0.0, 0.0, 0.0);
                  }
               }

               if (this.igniteBlock && (Boolean)AmmoConfig.IGNITE_BLOCK.get()) {
                  BlockPos offsetPos = pos.m_121945_(result.m_82434_());
                  if (BaseFireBlock.m_49255_(this.m_9236_(), offsetPos, result.m_82434_())) {
                     BlockState fireState = BaseFireBlock.m_49245_(this.m_9236_(), offsetPos);
                     this.m_9236_().m_7731_(offsetPos, fireState, 11);
                     ((ServerLevel)this.m_9236_())
                        .m_8767_(
                           ParticleTypes.f_123756_,
                           hitVec.f_82479_ - 1.0 + this.f_19796_.m_188500_() * 2.0,
                           hitVec.f_82480_,
                           hitVec.f_82481_ - 1.0 + this.f_19796_.m_188500_() * 2.0,
                           4,
                           0.0,
                           0.0,
                           0.0,
                           0.0
                        );
                  }
               }

               this.m_146870_();
            }
         }
      }
   }

   public float getDamage(Vec3 hitVec) {
      float base = 0.0F;
      double playerDistance = hitVec.m_82554_(this.startPos);

      for (DistanceDamagePair pair : this.damageAmount) {
         float effectiveDistance = this.damageAmount.get(0).getDistance() == pair.getDistance() ? this.distanceAmount : pair.getDistance();
         if (playerDistance < (double)effectiveDistance) {
            float damage = pair.getDamage();
            base = Math.max(damage * this.damageModifier, 0.0F);
            break;
         }
      }

      float modifiedDamage = this.modifyProperty(GunProperties.DAMAGE, Float.class, base);
      return Math.max(modifiedDamage * this.shotDamageMultiplier, 0.0F);
   }

   private <T> T modifyProperty(GunProperty<?> prop, Class<T> type, T original) {
      return this.modifyProperty(prop.name(), type, original);
   }

   private <T> T modifyProperty(String id, Class<T> type, T original) {
      if (this.m_19749_() instanceof LivingEntity shooter) {
         ItemStack gun = shooter.m_21205_();
         if (gun.m_41720_() instanceof AbstractGunItem gunInterface && Objects.equals(this.gunId, gunInterface.getGunId(gun))) {
            ShooterDataHolder dataHolder = IGunOperator.fromLivingEntity(shooter).getDataHolder();
            return (T)gunInterface.modifyProperty(dataHolder, gun, shooter, id, type, original);
         }
      }

      return original;
   }

   private Pair<DamageSource, DamageSource> createDamageSources(MaybeMultipartEntity parts) {
      EntityType<?> hitPartType = parts.hitPart().m_6095_();
      Entity directCause = (Entity)(hitPartType.m_204039_(PRETEND_MELEE_DAMAGE_ON) ? this.m_19749_() : this);
      DamageSource source1;
      DamageSource source2;
      if (hitPartType.m_204039_(USE_MAGIC_DAMAGE_ON)) {
         source1 = source2 = this.m_269291_().m_269104_(this, this.m_19749_());
      } else if (hitPartType.m_204039_(USE_VOID_DAMAGE_ON)) {
         source1 = ModDamageTypes.Sources.bulletVoid(this.m_9236_().m_9598_(), directCause, this.m_19749_(), false);
         source2 = ModDamageTypes.Sources.bulletVoid(this.m_9236_().m_9598_(), directCause, this.m_19749_(), true);
      } else {
         source1 = ModDamageTypes.Sources.bullet(this.m_9236_().m_9598_(), directCause, this.m_19749_(), false);
         source2 = ModDamageTypes.Sources.bullet(this.m_9236_().m_9598_(), directCause, this.m_19749_(), true);
      }

      return Pair.of(source1, source2);
   }

   private void tacAttackEntity(MaybeMultipartEntity parts, float damage, Pair<DamageSource, DamageSource> sources) {
      DamageSource source1 = (DamageSource)sources.getLeft();
      DamageSource source2 = (DamageSource)sources.getRight();
      float armorDamagePercent = Mth.m_14036_(this.armorIgnore, 0.0F, 1.0F);
      float normalDamagePercent = 1.0F - armorDamagePercent;
      parts.core().f_19802_ = 0;
      parts.hitPart().m_6469_(source1, damage * normalDamagePercent);
      parts.core().f_19802_ = 0;
      parts.hitPart().m_6469_(source2, damage * armorDamagePercent);
   }

   public Packet<ClientGamePacketListener> m_5654_() {
      return NetworkHooks.getEntitySpawningPacket(this);
   }

   public void writeSpawnData(FriendlyByteBuf buffer) {
      buffer.writeFloat(this.m_146909_());
      buffer.writeFloat(this.m_146908_());
      buffer.writeDouble(this.m_20184_().f_82479_);
      buffer.writeDouble(this.m_20184_().f_82480_);
      buffer.writeDouble(this.m_20184_().f_82481_);
      Entity entity = this.m_19749_();
      buffer.writeInt(entity != null ? entity.m_19879_() : 0);
      buffer.m_130085_(this.ammoId);
      buffer.writeFloat(this.gravity);
      buffer.writeBoolean(this.explosion);
      buffer.writeBoolean(this.igniteEntity);
      buffer.writeBoolean(this.igniteBlock);
      buffer.writeFloat(this.explosionRadius);
      buffer.writeFloat(this.explosionDamage);
      buffer.writeInt(this.life);
      buffer.writeFloat(this.speed);
      buffer.writeFloat(this.friction);
      buffer.writeInt(this.pierce);
      buffer.writeBoolean(this.isTracerAmmo);
      buffer.m_130085_(this.gunId);
      buffer.m_130085_(this.gunDisplayId);
   }

   public void readSpawnData(FriendlyByteBuf additionalData) {
      this.m_146926_(additionalData.readFloat());
      this.m_146922_(additionalData.readFloat());
      this.m_20334_(additionalData.readDouble(), additionalData.readDouble(), additionalData.readDouble());
      Entity entity = this.m_9236_().m_6815_(additionalData.readInt());
      if (entity != null) {
         this.m_5602_(entity);
      }

      this.ammoId = additionalData.m_130281_();
      this.gravity = additionalData.readFloat();
      this.explosion = additionalData.readBoolean();
      this.igniteEntity = additionalData.readBoolean();
      this.igniteBlock = additionalData.readBoolean();
      this.explosionRadius = additionalData.readFloat();
      this.explosionDamage = additionalData.readFloat();
      this.life = additionalData.readInt();
      this.speed = additionalData.readFloat();
      this.friction = additionalData.readFloat();
      this.pierce = additionalData.readInt();
      this.isTracerAmmo = additionalData.readBoolean();
      this.gunId = additionalData.m_130281_();
      this.gunDisplayId = additionalData.m_130281_();
   }

   public ResourceLocation getAmmoId() {
      return this.ammoId;
   }

   public ResourceLocation getGunId() {
      return this.gunId;
   }

   public ResourceLocation getGunDisplayId() {
      return this.gunDisplayId;
   }

   public boolean isTracerAmmo() {
      return this.isTracerAmmo;
   }

   public RandomSource getRandom() {
      return this.f_19796_;
   }

   public float getCameraYRot() {
      return this.cameraYRot;
   }

   public void setCameraYRot(float cameraYRot) {
      this.cameraYRot = cameraYRot;
   }

   public float getCameraXRot() {
      return this.cameraXRot;
   }

   public void setCameraXRot(float cameraXRot) {
      this.cameraXRot = cameraXRot;
   }

   public Vector3f getFirstPersonRenderOffset() {
      return this.firstPersonRenderOffset;
   }

   public void setFirstPersonRenderOffset(Vector3f originRenderOffset) {
      this.firstPersonRenderOffset = originRenderOffset;
   }

   public Optional<float[]> getTracerColorOverride() {
      CompoundTag pd = this.getPersistentData();
      if (!pd.m_128425_("tacz:tracer_override", 11)) {
         return Optional.empty();
      } else {
         int[] ints = pd.m_128465_("tacz:tracer_override");
         switch (ints.length) {
            case 0:
               return Optional.empty();
            case 1: {
               float albedo = (float)ints[0] / 255.0F;
               return Optional.of(new float[]{albedo, albedo, albedo, 1.0F});
            }
            case 2: {
               float albedo = (float)ints[0] / 255.0F;
               float alpha = (float)ints[1] / 255.0F;
               return Optional.of(new float[]{albedo, albedo, albedo, alpha});
            }
            case 3: {
               float r = (float)ints[0] / 255.0F;
               float g = (float)ints[1] / 255.0F;
               float b = (float)ints[2] / 255.0F;
               return Optional.of(new float[]{r, g, b, 1.0F});
            }
            default: {
               float r = (float)ints[0] / 255.0F;
               float g = (float)ints[1] / 255.0F;
               float b = (float)ints[2] / 255.0F;
               float a = (float)ints[3] / 255.0F;
               return Optional.of(new float[]{r, g, b, a});
            }
         }
      }
   }

   public float getTracerSizeOverride() {
      CompoundTag pd = this.getPersistentData();
      return pd.m_128425_("tacz:tracer_size", 99) ? pd.m_128457_("tacz:tracer_size") : 1.0F;
   }

   public boolean m_150171_(@Nullable Entity entity) {
      return entity == null ? false : super.m_150171_(entity);
   }
}
