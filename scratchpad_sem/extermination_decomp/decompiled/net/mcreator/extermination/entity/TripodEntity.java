package net.mcreator.extermination.entity;

import net.mcreator.extermination.init.ExterminationModEntities;
import net.mcreator.extermination.procedures.TripodDeathTimeIsReachedProcedure;
import net.mcreator.extermination.procedures.TripodEntityDiesProcedure;
import net.mcreator.extermination.procedures.TripodIsHurtProcedure;
import net.mcreator.extermination.procedures.TripodOnEntityTickUpdateProcedure;
import net.mcreator.extermination.procedures.TripodThisEntityKillsAnotherOneProcedure;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Illusioner;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PlayMessages.SpawnEntity;
import net.minecraftforge.registries.ForgeRegistries;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.animation.AnimatableManager.ControllerRegistrar;
import software.bernie.geckolib.core.animation.AnimationController.State;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

public class TripodEntity extends Monster implements GeoEntity {
   public static final EntityDataAccessor<Boolean> SHOOT = SynchedEntityData.m_135353_(TripodEntity.class, EntityDataSerializers.f_135035_);
   public static final EntityDataAccessor<String> ANIMATION = SynchedEntityData.m_135353_(TripodEntity.class, EntityDataSerializers.f_135030_);
   public static final EntityDataAccessor<String> TEXTURE = SynchedEntityData.m_135353_(TripodEntity.class, EntityDataSerializers.f_135030_);
   private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
   private boolean swinging;
   private boolean lastloop;
   private long lastSwing;
   public String animationprocedure = "empty";

   public TripodEntity(SpawnEntity packet, Level world) {
      this((EntityType<TripodEntity>)ExterminationModEntities.TRIPOD.get(), world);
   }

   public TripodEntity(EntityType<TripodEntity> type, Level world) {
      super(type, world);
      this.f_21364_ = 50;
      this.m_21557_(false);
      this.m_21530_();
   }

   protected void m_8097_() {
      super.m_8097_();
      this.f_19804_.m_135372_(SHOOT, false);
      this.f_19804_.m_135372_(ANIMATION, "undefined");
      this.f_19804_.m_135372_(TEXTURE, "tripod");
   }

   public void setTexture(String texture) {
      this.f_19804_.m_135381_(TEXTURE, texture);
   }

   public String getTexture() {
      return (String)this.f_19804_.m_135370_(TEXTURE);
   }

   protected float m_6431_(Pose poseIn, EntityDimensions sizeIn) {
      return 20.0F;
   }

   public Packet<ClientGamePacketListener> m_5654_() {
      return NetworkHooks.getEntitySpawningPacket(this);
   }

   protected void m_8099_() {
      super.m_8099_();
      this.f_21345_.m_25352_(1, new MeleeAttackGoal(this, 1.0, false) {
         protected double m_6639_(LivingEntity entity) {
            return 9.0;
         }
      });
      this.f_21346_.m_25352_(2, new HurtByTargetGoal(this, new Class[0]).m_26044_(new Class[0]));
      this.f_21345_.m_25352_(3, new RandomStrollGoal(this, 1.0));
      this.f_21345_.m_25352_(4, new RandomLookAroundGoal(this));
      this.f_21346_.m_25352_(5, new NearestAttackableTargetGoal(this, Player.class, true, false));
      this.f_21346_.m_25352_(6, new NearestAttackableTargetGoal(this, Villager.class, true, false));
      this.f_21346_.m_25352_(7, new NearestAttackableTargetGoal(this, WanderingTrader.class, true, false));
      this.f_21346_.m_25352_(8, new NearestAttackableTargetGoal(this, AbstractGolem.class, true, false));
      this.f_21346_.m_25352_(9, new NearestAttackableTargetGoal(this, Animal.class, true, false));
      this.f_21346_.m_25352_(10, new NearestAttackableTargetGoal(this, Pillager.class, true, false));
      this.f_21346_.m_25352_(11, new NearestAttackableTargetGoal(this, Vindicator.class, true, false));
      this.f_21346_.m_25352_(12, new NearestAttackableTargetGoal(this, Evoker.class, true, false));
      this.f_21346_.m_25352_(13, new NearestAttackableTargetGoal(this, Illusioner.class, true, false));
      this.f_21346_.m_25352_(14, new NearestAttackableTargetGoal(this, Ravager.class, true, false));
      this.f_21346_.m_25352_(15, new NearestAttackableTargetGoal(this, Witch.class, true, false));
      this.f_21346_.m_25352_(16, new NearestAttackableTargetGoal(this, Husk.class, true, false));
      this.f_21346_.m_25352_(17, new NearestAttackableTargetGoal(this, Zombie.class, true, false));
      this.f_21346_.m_25352_(18, new NearestAttackableTargetGoal(this, ZombieVillager.class, true, false));
      this.f_21345_.m_25352_(19, new AvoidEntityGoal(this, Creeper.class, 15.0F, 1.0, 1.0));
   }

   public MobType m_6336_() {
      return MobType.f_21640_;
   }

   public boolean m_6785_(double distanceToClosestPlayer) {
      return false;
   }

   public void m_7355_(BlockPos pos, BlockState blockIn) {
      this.m_5496_((SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:silence")), 0.15F, 1.0F);
   }

   public SoundEvent m_7975_(DamageSource ds) {
      return (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:silence"));
   }

   public SoundEvent m_5592_() {
      return (SoundEvent)ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("extermination:entity.tripod.death"));
   }

   public boolean m_6469_(DamageSource source, float amount) {
      TripodIsHurtProcedure.execute(this.m_9236_(), this.m_20185_(), this.m_20186_(), this.m_20189_(), this);
      if (source.m_276093_(DamageTypes.f_268631_)) {
         return false;
      } else if (source.m_7640_() instanceof Player) {
         return false;
      } else if (source.m_276093_(DamageTypes.f_268671_)) {
         return false;
      } else if (source.m_276093_(DamageTypes.f_268585_)) {
         return false;
      } else if (source.m_276093_(DamageTypes.f_268722_)) {
         return false;
      } else if (source.m_276093_(DamageTypes.f_268450_)) {
         return false;
      } else if (source.m_276093_(DamageTypes.f_268526_)) {
         return false;
      } else if (source.m_276093_(DamageTypes.f_268482_)) {
         return false;
      } else if (source.m_276093_(DamageTypes.f_268493_)) {
         return false;
      } else {
         return source.m_276093_(DamageTypes.f_268641_) ? false : super.m_6469_(source, amount);
      }
   }

   public void m_6667_(DamageSource source) {
      super.m_6667_(source);
      TripodEntityDiesProcedure.execute(this.m_9236_(), this.m_20185_(), this.m_20186_(), this.m_20189_(), source.m_7639_());
   }

   public void m_7380_(CompoundTag compound) {
      super.m_7380_(compound);
      compound.m_128359_("Texture", this.getTexture());
   }

   public void m_7378_(CompoundTag compound) {
      super.m_7378_(compound);
      if (compound.m_128441_("Texture")) {
         this.setTexture(compound.m_128461_("Texture"));
      }
   }

   public void m_5993_(Entity entity, int score, DamageSource damageSource) {
      super.m_5993_(entity, score, damageSource);
      TripodThisEntityKillsAnotherOneProcedure.execute(this.m_9236_(), this.m_20185_(), this.m_20186_(), this.m_20189_(), this);
   }

   public void m_6075_() {
      super.m_6075_();
      TripodOnEntityTickUpdateProcedure.execute(this.m_9236_(), this.m_20185_(), this.m_20186_(), this.m_20189_(), this);
      this.m_6210_();
   }

   public EntityDimensions m_6972_(Pose p_33597_) {
      return super.m_6972_(p_33597_).m_20388_(1.0F);
   }

   public boolean m_6094_() {
      return false;
   }

   protected void m_7324_(Entity entityIn) {
   }

   protected void m_6138_() {
   }

   public static void init() {
   }

   public static Builder createAttributes() {
      Builder builder = Mob.m_21552_();
      builder = builder.m_22268_(Attributes.f_22279_, 0.35);
      builder = builder.m_22268_(Attributes.f_22276_, 200.0);
      builder = builder.m_22268_(Attributes.f_22284_, 15.0);
      builder = builder.m_22268_(Attributes.f_22281_, 30.0);
      builder = builder.m_22268_(Attributes.f_22277_, 60.0);
      builder = builder.m_22268_(Attributes.f_22278_, 10.0);
      return builder.m_22268_(Attributes.f_22282_, 0.5);
   }

   private PlayState movementPredicate(AnimationState event) {
      if (this.animationprocedure.equals("empty")) {
         if (event.isMoving() || !(event.getLimbSwingAmount() > -0.15F) || !(event.getLimbSwingAmount() < 0.15F)) {
            return event.setAndContinue(RawAnimation.begin().thenLoop("animation.tripod_invaders.walk"));
         } else {
            return this.m_21224_()
               ? event.setAndContinue(RawAnimation.begin().thenPlay("animation.tripod_invaders.death"))
               : event.setAndContinue(RawAnimation.begin().thenLoop("animation.tripod_invaders.idle"));
         }
      } else {
         return PlayState.STOP;
      }
   }

   private PlayState procedurePredicate(AnimationState event) {
      if (!this.animationprocedure.equals("empty") && event.getController().getAnimationState() == State.STOPPED) {
         event.getController().setAnimation(RawAnimation.begin().thenPlay(this.animationprocedure));
         if (event.getController().getAnimationState() == State.STOPPED) {
            this.animationprocedure = "empty";
            event.getController().forceAnimationReset();
         }
      } else if (this.animationprocedure.equals("empty")) {
         return PlayState.STOP;
      }

      return PlayState.CONTINUE;
   }

   protected void m_6153_() {
      this.f_20919_++;
      if (this.f_20919_ == 100) {
         this.m_142687_(RemovalReason.KILLED);
         this.m_21226_();
         TripodDeathTimeIsReachedProcedure.execute(this.m_9236_(), this.m_20185_(), this.m_20186_(), this.m_20189_(), this);
      }
   }

   public String getSyncedAnimation() {
      return (String)this.f_19804_.m_135370_(ANIMATION);
   }

   public void setAnimation(String animation) {
      this.f_19804_.m_135381_(ANIMATION, animation);
   }

   public void registerControllers(ControllerRegistrar data) {
      data.add(new AnimationController[]{new AnimationController(this, "movement", 1, this::movementPredicate)});
      data.add(new AnimationController[]{new AnimationController(this, "procedure", 1, this::procedurePredicate)});
   }

   public AnimatableInstanceCache getAnimatableInstanceCache() {
      return this.cache;
   }
}
