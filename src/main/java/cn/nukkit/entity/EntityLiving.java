package cn.nukkit.entity;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.api.PowerNukkitDifference;
import cn.nukkit.api.PowerNukkitOnly;
import cn.nukkit.api.Since;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockMagma;
import cn.nukkit.entity.data.ShortEntityData;
import cn.nukkit.entity.passive.EntityWaterAnimal;
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.entity.weather.EntityWeather;
import cn.nukkit.event.entity.*;
import cn.nukkit.event.entity.EntityDamageEvent.DamageCause;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemTurtleShell;
import cn.nukkit.level.GameRule;
import cn.nukkit.level.Sound;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.FloatTag;
import cn.nukkit.network.protocol.AnimatePacket;
import cn.nukkit.network.protocol.EntityEventPacket;
import cn.nukkit.potion.Effect;
import cn.nukkit.utils.BlockIterator;
import cn.nukkit.utils.Utils;
import co.aikar.timings.Timings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @author MagicDroidX (Nukkit Project)
 */
public abstract class EntityLiving extends Entity implements EntityDamageable {

    public EntityLiving(FullChunk chunk, CompoundTag nbt) {
        super(chunk, nbt);
    }

    @Override
    protected float getGravity() {
        return 0.08f;
    }

    @Override
    protected float getDrag() {
        return 0.02f;
    }

    protected int attackTime = 0;
    private boolean attackTimeByShieldKb;
    private int attackTimeBefore;

    protected boolean invisible = false;

    protected float movementSpeed = 0.1f;

    protected int turtleTicks = 200;

    @Override
    protected void initEntity() {
        super.initEntity();

        if (this.namedTag.contains("HealF")) {
            this.namedTag.putFloat("Health", this.namedTag.getShort("HealF"));
            this.namedTag.remove("HealF");
        }

        if (!this.namedTag.contains("Health") || !(this.namedTag.get("Health") instanceof FloatTag)) {
            this.namedTag.putFloat("Health", this.getMaxHealth());
        }

        this.health = this.namedTag.getFloat("Health");
    }

    @Override
    public void setHealth(float health) {
        boolean wasAlive = this.isAlive();
        super.setHealth(health);
        if (this.isAlive() && !wasAlive) {
            EntityEventPacket pk = new EntityEventPacket();
            pk.eid = this.getId();
            pk.event = EntityEventPacket.RESPAWN;
            Server.broadcastPacket(this.hasSpawned.values(), pk);
        }
    }

    @Override
    public void saveNBT() {
        super.saveNBT();
        this.namedTag.putFloat("Health", this.getHealth());
    }

    public boolean hasLineOfSight(Entity entity) {
        //todo
        return true;
    }

    public void collidingWith(Entity ent) { // can override (IronGolem|Bats)
        ent.applyEntityCollision(this);
    }

    @PowerNukkitDifference(info = "Using new method to play sounds", since = "1.4.0.0-PN")
    @Override
    public boolean attack(EntityDamageEvent source) {
        if (this.noDamageTicks > 0) {
            return false;
        } else if (this.attackTime > 0 && !attackTimeByShieldKb) {
            EntityDamageEvent lastCause = this.getLastDamageCause();
            if (lastCause != null && lastCause.getDamage() >= source.getDamage()) {
                return false;
            }
        }

        if (isBlocking() && this.blockedByShield(source)) {
            return false;
        }

        if (super.attack(source)) {
            if (source instanceof EntityDamageByEntityEvent) {
                Entity damager = ((EntityDamageByEntityEvent) source).getDamager();
                if (source instanceof EntityDamageByChildEntityEvent) {
                    damager = ((EntityDamageByChildEntityEvent) source).getChild();
                }

                //Critical hit
                if (damager instanceof Player && !damager.onGround) {
                    AnimatePacket animate = new AnimatePacket();
                    animate.action = AnimatePacket.Action.CRITICAL_HIT;
                    animate.eid = getId();

                    this.getLevel().addChunkPacket(damager.getChunkX(), damager.getChunkZ(), animate);
                    this.getLevel().addSound(this, Sound.GAME_PLAYER_ATTACK_STRONG);

                    source.setDamage(source.getDamage() * 1.5f);
                }

                if (damager.isOnFire() && !(damager instanceof Player)) {
                    this.setOnFire(2 * this.server.getDifficulty());
                }

                double deltaX = this.x - damager.x;
                double deltaZ = this.z - damager.z;
                this.knockBack(damager, source.getDamage(), deltaX, deltaZ, ((EntityDamageByEntityEvent) source).getKnockBack());
            }

            EntityEventPacket pk = new EntityEventPacket();
            pk.eid = this.getId();
            pk.event = this.getHealth() <= 0 ? EntityEventPacket.DEATH_ANIMATION : EntityEventPacket.HURT_ANIMATION;
            Server.broadcastPacket(this.hasSpawned.values(), pk);

            this.attackTime = source.getAttackCooldown();
            this.attackTimeByShieldKb = false;
            this.scheduleUpdate();

            return true;
        } else {
            return false;
        }
    }

    public void knockBack(Entity attacker, double damage, double x, double z) {
        this.knockBack(attacker, damage, x, z, 0.4);
    }

    public void knockBack(Entity attacker, double damage, double x, double z, double base) {
        double f = Math.sqrt(x * x + z * z);
        if (f <= 0) {
            return;
        }

        f = 1 / f;

        Vector3 motion = new Vector3(this.motionX, this.motionY, this.motionZ);

        motion.x /= 2d;
        motion.y /= 2d;
        motion.z /= 2d;
        motion.x += x * f * base;
        motion.y += base;
        motion.z += z * f * base;

        if (motion.y > base) {
            motion.y = base;
        }

        this.setMotion(motion);
    }

    @Override
    public void kill() {
        if (!this.isAlive()) {
            return;
        }
        super.kill();
        EntityDeathEvent ev = new EntityDeathEvent(this, this.getDrops());
        this.server.getPluginManager().callEvent(ev);

        if (this.level.getGameRules().getBoolean(GameRule.DO_ENTITY_DROPS)) {
            for (cn.nukkit.item.Item item : ev.getDrops()) {
                this.getLevel().dropItem(this, item);
            }
        }
    }

    @Override
    public boolean entityBaseTick() {
        return this.entityBaseTick(1);
    }

    @Override
    public boolean entityBaseTick(int tickDiff) {
        Timings.livingEntityBaseTickTimer.startTiming();
        boolean isBreathing = !this.isInsideOfWater();
        if (this instanceof Player && (((Player) this).isCreative() || ((Player) this).isSpectator())) {
            isBreathing = true;
        }

        if (this instanceof Player) {
            if (!isBreathing && ((Player) this).getInventory().getHelmet() instanceof ItemTurtleShell) {
                if (turtleTicks > 0) {
                    isBreathing = true;
                    turtleTicks--;
                }
            } else {
                turtleTicks = 200;
            }
        }
        
        this.setDataFlag(DATA_FLAGS, DATA_FLAG_BREATHING, isBreathing);

        boolean hasUpdate = super.entityBaseTick(tickDiff);

        if (this.isAlive()) {

            if (this.isInsideOfSolid()) {
                hasUpdate = true;
                this.attack(new EntityDamageEvent(this, DamageCause.SUFFOCATION, 1));
            }

            if (this.isOnLadder() || this.hasEffect(Effect.LEVITATION)) {
                this.resetFallDistance();
            }

            if (!this.hasEffect(Effect.WATER_BREATHING) && !this.hasEffect(Effect.CONDUIT_POWER) && this.isInsideOfWater()) {
                if (this instanceof EntityWaterAnimal || (this instanceof Player && (((Player) this).isCreative() || ((Player) this).isSpectator()))) {
                    this.setAirTicks(400);
                } else {
                    if (turtleTicks == 0 || turtleTicks == 200) {
                        hasUpdate = true;
                        int airTicks = this.getAirTicks() - tickDiff;

                        if (airTicks <= -20) {
                            airTicks = 0;
                            this.attack(new EntityDamageEvent(this, DamageCause.DROWNING, 2));
                        }

                        setAirTicks(airTicks);
                    }
                }
            } else {
                if (this instanceof EntityWaterAnimal) {
                    hasUpdate = true;
                    int airTicks = getAirTicks() - tickDiff;

                    if (airTicks <= -20) {
                        airTicks = 0;
                        this.attack(new EntityDamageEvent(this, DamageCause.SUFFOCATION, 2));
                    }

                    setAirTicks(airTicks);
                } else {
                    int airTicks = getAirTicks();

                    if (airTicks < 400) {
                        setAirTicks(Math.min(400, airTicks + tickDiff * 5));
                    }
                }
            }
        }

        if (this.attackTime > 0) {
            this.attackTime -= tickDiff;
            if (this.attackTime <= 0) {
                attackTimeByShieldKb = false;
            }
            hasUpdate = true;
        }

        if (this.riding == null) {
            for (Entity entity : level.getNearbyEntities(this.boundingBox.grow(0.20000000298023224, 0.0D, 0.20000000298023224), this)) {
                if (entity instanceof EntityRideable) {
                    this.collidingWith(entity);
                }
            }
        }

        // Used to check collisions with magma blocks
        Block block = this.level.getBlock((int) x, (int) y - 1, (int) z);
        if (block instanceof BlockMagma) block.onEntityCollide(this);

        Timings.livingEntityBaseTickTimer.stopTiming();

        return hasUpdate;
    }

    public Item[] getDrops() {
        return Item.EMPTY_ARRAY;
    }

    public Block[] getLineOfSight(int maxDistance) {
        return this.getLineOfSight(maxDistance, 0);
    }

    public Block[] getLineOfSight(int maxDistance, int maxLength) {
        return this.getLineOfSight(maxDistance, maxLength, new Integer[]{});
    }

    @Deprecated
    public Block[] getLineOfSight(int maxDistance, int maxLength, Map<Integer, Object> transparent) {
        return this.getLineOfSight(maxDistance, maxLength, transparent.keySet().toArray(Utils.EMPTY_INTEGERS));
    }

    public Block[] getLineOfSight(int maxDistance, int maxLength, Integer[] transparent) {
        if (maxDistance > 120) {
            maxDistance = 120;
        }

        if (transparent != null && transparent.length == 0) {
            transparent = null;
        }

        List<Block> blocks = new ArrayList<>();

        BlockIterator itr = new BlockIterator(this.level, this.getPosition(), this.getDirectionVector(), this.getEyeHeight(), maxDistance);

        while (itr.hasNext()) {
            Block block = itr.next();
            blocks.add(block);

            if (maxLength != 0 && blocks.size() > maxLength) {
                blocks.remove(0);
            }

            int id = block.getId();

            if (transparent == null) {
                if (id != 0) {
                    break;
                }
            } else {
                if (Arrays.binarySearch(transparent, id) < 0) {
                    break;
                }
            }
        }

        return blocks.toArray(Block.EMPTY_ARRAY);
    }

    public Block getTargetBlock(int maxDistance) {
        return getTargetBlock(maxDistance, new Integer[]{});
    }

    @Deprecated
    public Block getTargetBlock(int maxDistance, Map<Integer, Object> transparent) {
        return getTargetBlock(maxDistance, transparent.keySet().toArray(Utils.EMPTY_INTEGERS));
    }

    public Block getTargetBlock(int maxDistance, Integer[] transparent) {
        try {
            Block[] blocks = this.getLineOfSight(maxDistance, 1, transparent);
            Block block = blocks[0];
            if (block != null) {
                if (transparent != null && transparent.length != 0) {
                    if (Arrays.binarySearch(transparent, block.getId()) < 0) {
                        return block;
                    }
                } else {
                    return block;
                }
            }
        } catch (Exception ignored) {

        }

        return null;
    }

    public void setMovementSpeed(float speed) {
        this.movementSpeed = speed;
    }

    public float getMovementSpeed() {
        return this.movementSpeed;
    }

    public int getAirTicks() {
        return this.getDataPropertyShort(DATA_AIR);
    }

    public void setAirTicks(int ticks) {
        this.setDataProperty(new ShortEntityData(DATA_AIR, ticks));
    }

    protected boolean blockedByShield(EntityDamageEvent source) {
        Entity damager = null;
        if (source instanceof EntityDamageByChildEntityEvent) {
            damager = ((EntityDamageByChildEntityEvent) source).getChild();
        } else if (source instanceof EntityDamageByEntityEvent) {
            damager = ((EntityDamageByEntityEvent) source).getDamager();
        }
        if (damager == null || damager instanceof EntityWeather || !this.isBlocking()) {
            return false;
        }

        Vector3 entityPos = damager.getPosition();
        Vector3 direction = this.getDirectionVector();
        Vector3 normalizedVector = this.getPosition().subtract(entityPos).normalize();
        boolean blocked = (normalizedVector.x * direction.x) + (normalizedVector.z * direction.z) < 0.0;
        boolean knockBack = !(damager instanceof EntityProjectile);
        EntityDamageBlockedEvent event = new EntityDamageBlockedEvent(this, source, knockBack, true);
        if (!blocked || !source.canBeReducedByArmor()) {
            event.setCancelled();
        }

        getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }

        if (event.getKnockBackAttacker() && damager instanceof EntityLiving) {
            EntityLiving attacker = (EntityLiving) damager;
            double deltaX = attacker.getX() - this.getX();
            double deltaZ = attacker.getZ() - this.getZ();
            attacker.knockBack(this, 0, deltaX, deltaZ);
            attacker.attackTime = 10;
            attacker.attackTimeByShieldKb = true;
        }

        onBlock(damager, event.getAnimation());
        return true;
    }

    protected void onBlock(Entity entity, boolean animate) {
        if (animate) {
            getLevel().addSound(this, Sound.ITEM_SHIELD_BLOCK);
        }
    }

    public boolean isBlocking() {
        return this.getDataFlag(DATA_FLAGS_EXTENDED, DATA_FLAG_BLOCKING);
    }

    public void setBlocking(boolean value) {
        this.setDataFlag(DATA_FLAGS_EXTENDED, DATA_FLAG_BLOCKING, value);
    }

    @PowerNukkitOnly
    @Since("1.4.0.0-PN")
    public void preAttack(Player player) {
        if (attackTimeByShieldKb) {
            attackTimeBefore = attackTime;
            attackTime = 0;
        }
    }

    @PowerNukkitOnly
    @Since("1.4.0.0-PN")
    public void postAttack(Player player) {
        if (attackTimeByShieldKb && attackTime == 0) {
            attackTime = attackTimeBefore;
        }
    }
}
