package local.mcai;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;

public final class CompanionEntity extends EntityCreature {
    private String ownerId = "";
    private String task = "idle";
    private int lookTicks;
    private String lastResult = "none";
    private final FollowRetry pathRetry = new FollowRetry();

    public CompanionEntity(World world) {
        super(world);
        setSize(0.6F, 1.8F);
        setCustomNameTag("Companion");
        setAlwaysRenderNameTag(true);
        getNavigator().setAvoidsWater(true);
        tasks.addTask(0, new EntityAISwimming(this));
        tasks.addTask(1, new FollowTask());
    }

    @Override protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20.0D);
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.3D);
        getEntityAttribute(SharedMonsterAttributes.followRange).setBaseValue(32.0D);
    }
    @Override protected boolean isAIEnabled() { return true; }
    @Override protected boolean canDespawn() { return false; }
    public void setOwner(EntityPlayer player) { ownerId = player.getUniqueID().toString(); }
    public String ownerId() { return ownerId; }
    public String task() { return task; }
    public String lastResult() { return lastResult; }
    public void follow() { stop(); task = "follow"; result("following"); }
    public void stop() {
        pathRetry.reset();
        task = "idle";
        lookTicks = 0;
        getNavigator().clearPathEntity();
        getMoveHelper().setMoveTo(posX, posY, posZ, 0.0D);
        moveForward = 0;
        moveStrafing = 0;
        result("stopped");
    }
    public void look() { stop(); task = "look"; lookTicks = 60; result("looking"); }

    private EntityPlayer owner() {
        for (Object value : worldObj.playerEntities) {
            EntityPlayer player = (EntityPlayer) value;
            if (player.getUniqueID().toString().equals(ownerId) && player.isEntityAlive()) { return player; }
        }
        return null;
    }

    private void result(String value) {
        if (!value.equals(lastResult)) {
            lastResult = value;
            LogManager.getLogger(CompanionMod.MOD_ID).info("Companion task={} result={}", task, value);
        }
    }

    @Override public void onLivingUpdate() {
        super.onLivingUpdate();
        if (worldObj.isRemote) { return; }
        EntityPlayer target = owner();
        if (!task.equals("idle") && target == null) { stop(); result("owner_unavailable"); }
        if (task.equals("look") && target != null) {
            getLookHelper().setLookPositionWithEntity(target, 30.0F, 30.0F);
            if (--lookTicks <= 0) { stop(); result("look_completed"); }
        }
    }

    @Override public void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);
        nbt.setString("McAiOwner", ownerId);
    }
    @Override public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);
        ownerId = nbt.getString("McAiOwner");
        // Resume safely at idle after a reload, requiring an explicit follow command.
        task = "idle";
        lookTicks = 0;
    }
    @Override public void onDeath(DamageSource source) {
        if (!worldObj.isRemote) {
            CompanionRegistry.get().remove(ownerId, getUniqueID().toString());
            result("companion_died");
        }
        super.onDeath(source);
    }

    private final class FollowTask extends EntityAIBase {
        private int retryTicks;
        FollowTask() { setMutexBits(3); }
        @Override public boolean shouldExecute() { return task.equals("follow") && owner() != null; }
        @Override public boolean continueExecuting() { return shouldExecute(); }
        @Override public void startExecuting() { retryTicks = 0; pathRetry.reset(); }
        @Override public void resetTask() { getNavigator().clearPathEntity(); }
        @Override public void updateTask() {
            EntityPlayer target = owner();
            if (target == null) { return; }
            getLookHelper().setLookPositionWithEntity(target, 30.0F, 30.0F);
            double distance = getDistanceSqToEntity(target);
            if (distance <= 4.0D) { getNavigator().clearPathEntity(); pathRetry.reset(); result("near_owner"); return; }
            if (distance > 1024.0D) {
                getNavigator().clearPathEntity(); result("owner_out_of_range"); return;
            }
            if (--retryTicks <= 0) {
                retryTicks = 20;
                boolean found = getNavigator().tryMoveToEntityLiving(target, 1.0D);
                boolean exhausted = pathRetry.exhausted(found);
                result(found ? "following" : exhausted ? "path_not_found" : "path_retrying");
            }
        }
    }
}
