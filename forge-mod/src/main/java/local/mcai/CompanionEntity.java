package local.mcai;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CompanionEntity extends EntityCreature {
    /** Items within this radius of the companion are observable and reachable for pickup. */
    public static final double ITEM_RANGE_SQUARED = 256.0D;
    private static final int SLOTS = 9;
    private final ItemStack[] inventory = new ItemStack[SLOTS];
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
        tasks.addTask(2, new PickupTask());
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
    public void pickup() { stop(); task = "pickup"; result("picking_up"); }

    /** Registry name to count, for observation only. NBT and damage values are not reported. */
    public Map<String, Integer> inventoryCounts() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (ItemStack stack : inventory) {
            if (stack == null || stack.stackSize <= 0) { continue; }
            Object name = Item.itemRegistry.getNameForObject(stack.getItem());
            if (name == null) { continue; }
            String key = name.toString();
            counts.put(key, stack.stackSize + (counts.containsKey(key) ? counts.get(key) : 0));
        }
        return counts;
    }

    public boolean hasItemInRange() { return nearestItem() != null; }

    private EntityItem nearestItem() {
        EntityItem best = null;
        double bestDistance = 0;
        for (Object value : worldObj.getEntitiesWithinAABB(EntityItem.class, boundingBox.expand(16, 16, 16))) {
            EntityItem item = (EntityItem) value;
            ItemStack stack = item.getEntityItem();
            if (item.isDead || item.delayBeforeCanPickup > 0 || stack == null || stack.stackSize <= 0) { continue; }
            double distance = item.getDistanceSqToEntity(this);
            if (distance > ITEM_RANGE_SQUARED) { continue; }
            if (best == null || distance < bestDistance) { best = item; bestDistance = distance; }
        }
        return best;
    }

    /** Merges into existing stacks first, then empty slots. Returns how many items were stored. */
    private int store(ItemStack stack) {
        int stored = 0;
        for (int i = 0; i < SLOTS && stack.stackSize > 0; i++) {
            ItemStack slot = inventory[i];
            if (slot == null || !slot.isItemEqual(stack) || !ItemStack.areItemStackTagsEqual(slot, stack)) { continue; }
            int move = Math.min(stack.stackSize, slot.getMaxStackSize() - slot.stackSize);
            if (move <= 0) { continue; }
            slot.stackSize += move; stack.stackSize -= move; stored += move;
        }
        for (int i = 0; i < SLOTS && stack.stackSize > 0; i++) {
            if (inventory[i] != null) { continue; }
            int move = Math.min(stack.stackSize, stack.getMaxStackSize());
            ItemStack slot = stack.copy(); slot.stackSize = move;
            inventory[i] = slot; stack.stackSize -= move; stored += move;
        }
        return stored;
    }

    private void collect(EntityItem item) {
        // Work on a copy: the live stack is datawatcher-backed and must be replaced, not mutated.
        ItemStack remaining = item.getEntityItem().copy();
        int stored = store(remaining);
        stop();
        if (stored <= 0) { result("inventory_full"); return; }
        if (remaining.stackSize <= 0) { item.setDead(); } else { item.setEntityItemStack(remaining); }
        result("pickup_completed");
    }

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
        NBTTagList items = new NBTTagList();
        for (int i = 0; i < SLOTS; i++) {
            if (inventory[i] == null) { continue; }
            NBTTagCompound slot = new NBTTagCompound();
            slot.setByte("Slot", (byte) i);
            inventory[i].writeToNBT(slot);
            items.appendTag(slot);
        }
        nbt.setTag("McAiInventory", items);
    }
    @Override public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);
        ownerId = nbt.getString("McAiOwner");
        NBTTagList items = nbt.getTagList("McAiInventory", 10);
        for (int i = 0; i < SLOTS; i++) { inventory[i] = null; }
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound slot = items.getCompoundTagAt(i);
            int index = slot.getByte("Slot") & 255;
            if (index < SLOTS) { inventory[index] = ItemStack.loadItemStackFromNBT(slot); }
        }
        // Resume safely at idle after a reload, requiring an explicit follow command.
        task = "idle";
        lookTicks = 0;
    }
    @Override public void onDeath(DamageSource source) {
        if (!worldObj.isRemote) {
            CompanionRegistry.get().remove(ownerId, getUniqueID().toString());
            // Drop instead of silently voiding what the companion was carrying.
            for (int i = 0; i < SLOTS; i++) {
                if (inventory[i] != null && inventory[i].stackSize > 0) { entityDropItem(inventory[i], 0.0F); }
                inventory[i] = null;
            }
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

    private final class PickupTask extends EntityAIBase {
        private int retryTicks;
        PickupTask() { setMutexBits(3); }
        @Override public boolean shouldExecute() { return task.equals("pickup"); }
        @Override public boolean continueExecuting() { return shouldExecute(); }
        @Override public void startExecuting() { retryTicks = 0; pathRetry.reset(); }
        @Override public void resetTask() { getNavigator().clearPathEntity(); }
        @Override public void updateTask() {
            EntityItem item = nearestItem();
            // The target may be collected by the player, despawn or drift out of range mid-task.
            if (item == null) { stop(); result("no_item_in_range"); return; }
            getLookHelper().setLookPositionWithEntity(item, 30.0F, 30.0F);
            if (getDistanceSqToEntity(item) <= 2.25D) { getNavigator().clearPathEntity(); collect(item); return; }
            if (--retryTicks <= 0) {
                retryTicks = 20;
                boolean found = getNavigator().tryMoveToEntityLiving(item, 1.0D);
                boolean exhausted = pathRetry.exhausted(found);
                result(found ? "picking_up" : exhausted ? "path_not_found" : "path_retrying");
            }
        }
    }
}
