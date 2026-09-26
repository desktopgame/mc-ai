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
import java.util.UUID;

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
    // Target-fixed Skill pickup. The stored count survives stop() until the action receipt is sent.
    private String pickupTargetId = "";
    private int pickupMaxCount;
    private int pickupStored = -1;
    private String pickupOutcome = "";
    private boolean targetPickup;

    public CompanionEntity(World world) {
        super(world);
        setSize(0.6F, 1.8F);
        setCustomNameTag("Companion");
        setAlwaysRenderNameTag(true);
        getNavigator().setAvoidsWater(true);
        tasks.addTask(0, new EntityAISwimming(this));
        tasks.addTask(1, new FollowTask());
        tasks.addTask(2, new PickupTask());
        tasks.addTask(3, new DepositTask());
        tasks.addTask(4, new PickupTargetTask());
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
    public void pickup() { stop(); task = "pickup"; targetPickup = false; pickupStored = -1; pickupOutcome = ""; result("picking_up"); }
    public void deposit() { stop(); task = "deposit"; result("depositing"); }

    /** Skill pickup: follow one fixed dropped-item UUID and store at most maxCount. */
    public void pickupItem(String targetRef, int maxCount) {
        stop();
        task = "pickup";
        targetPickup = true;
        pickupTargetId = targetRef.startsWith("item-") ? targetRef.substring("item-".length()) : targetRef;
        pickupMaxCount = maxCount;
        pickupStored = -1;
        pickupOutcome = "";
        result("picking_up");
    }
    public boolean pickupResolved() { return pickupStored >= 0; }
    public int lastPickupStored() { return pickupStored; }
    public String pickupOutcome() { return pickupOutcome; }

    public int carriedCount() {
        int total = 0;
        for (ItemStack stack : inventory) {
            if (stack != null && stack.stackSize > 0) { total += stack.stackSize; }
        }
        return total;
    }

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

    /** Finds the fixed Skill target by UUID within range, regardless of what is nearer. */
    private EntityItem targetItem() {
        if (pickupTargetId.isEmpty()) { return null; }
        for (Object value : worldObj.getEntitiesWithinAABB(EntityItem.class, boundingBox.expand(16, 16, 16))) {
            EntityItem item = (EntityItem) value;
            if (item.isDead || !item.getUniqueID().toString().equals(pickupTargetId)) { continue; }
            ItemStack stack = item.getEntityItem();
            if (stack == null || stack.stackSize <= 0) { continue; }
            if (item.getDistanceSqToEntity(this) > ITEM_RANGE_SQUARED) { continue; }
            return item;
        }
        return null;
    }

    /** Merges into existing stacks first, then empty slots. Returns how many items were stored. */
    private int store(ItemStack stack) { return storeUpTo(stack, stack.stackSize); }

    /** Stores at most limit items, consuming the passed stack as it merges. */
    private int storeUpTo(ItemStack stack, int limit) {
        int stored = 0;
        for (int i = 0; i < SLOTS && stored < limit && stack.stackSize > 0; i++) {
            ItemStack slot = inventory[i];
            if (slot == null || !slot.isItemEqual(stack) || !ItemStack.areItemStackTagsEqual(slot, stack)) { continue; }
            int move = Math.min(Math.min(stack.stackSize, limit - stored), slot.getMaxStackSize() - slot.stackSize);
            if (move <= 0) { continue; }
            slot.stackSize += move; stack.stackSize -= move; stored += move;
        }
        for (int i = 0; i < SLOTS && stored < limit && stack.stackSize > 0; i++) {
            if (inventory[i] != null) { continue; }
            int move = Math.min(Math.min(stack.stackSize, limit - stored), stack.getMaxStackSize());
            ItemStack slot = stack.copy(); slot.stackSize = move;
            inventory[i] = slot; stack.stackSize -= move; stored += move;
        }
        return stored;
    }

    /** Vanilla insertion copies every stack it stores, so passing our own instances is safe. */
    private void handOver(EntityPlayer target) {
        int moved = 0;
        boolean remaining = false;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack slot = inventory[i];
            if (slot == null || slot.stackSize <= 0) { inventory[i] = null; continue; }
            int before = slot.stackSize;
            target.inventory.addItemStackToInventory(slot);
            moved += before - slot.stackSize;
            if (slot.stackSize <= 0) { inventory[i] = null; } else { remaining = true; }
        }
        stop();
        // Anything left over means the owner ran out of space, even when part of it was handed over.
        result(remaining || moved <= 0 ? "owner_inventory_full" : "deposit_completed");
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

    /** Collects up to pickupMaxCount from the fixed target; the outcome survives stop(). */
    private void collectTarget(EntityItem item) {
        ItemStack remaining = item.getEntityItem().copy();
        int stored = storeUpTo(remaining, Math.min(pickupMaxCount, remaining.stackSize));
        stop();
        pickupStored = stored;
        if (stored <= 0) { pickupOutcome = "inventory_full"; result("inventory_full"); return; }
        if (remaining.stackSize <= 0) { item.setDead(); } else { item.setEntityItemStack(remaining); }
        pickupOutcome = "completed";
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

    private final class DepositTask extends EntityAIBase {
        private int retryTicks;
        DepositTask() { setMutexBits(3); }
        @Override public boolean shouldExecute() { return task.equals("deposit") && owner() != null; }
        @Override public boolean continueExecuting() { return shouldExecute(); }
        @Override public void startExecuting() { retryTicks = 0; pathRetry.reset(); }
        @Override public void resetTask() { getNavigator().clearPathEntity(); }
        @Override public void updateTask() {
            EntityPlayer target = owner();
            if (target == null) { return; }
            if (carriedCount() <= 0) { stop(); result("inventory_empty"); return; }
            getLookHelper().setLookPositionWithEntity(target, 30.0F, 30.0F);
            if (getDistanceSqToEntity(target) <= 4.0D) { getNavigator().clearPathEntity(); handOver(target); return; }
            if (--retryTicks <= 0) {
                retryTicks = 20;
                boolean found = getNavigator().tryMoveToEntityLiving(target, 1.0D);
                boolean exhausted = pathRetry.exhausted(found);
                result(found ? "depositing" : exhausted ? "path_not_found" : "path_retrying");
            }
        }
    }

    private final class PickupTask extends EntityAIBase {
        private int retryTicks;
        PickupTask() { setMutexBits(3); }
        @Override public boolean shouldExecute() { return task.equals("pickup") && !targetPickup; }
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

    private final class PickupTargetTask extends EntityAIBase {
        private int retryTicks;
        PickupTargetTask() { setMutexBits(3); }
        @Override public boolean shouldExecute() { return task.equals("pickup") && targetPickup; }
        @Override public boolean continueExecuting() { return shouldExecute(); }
        @Override public void startExecuting() { retryTicks = 0; pathRetry.reset(); }
        @Override public void resetTask() { getNavigator().clearPathEntity(); }
        @Override public void updateTask() {
            EntityItem item = targetItem();
            if (item == null) { stop(); pickupStored = 0; pickupOutcome = "target_lost"; result("no_item_in_range"); return; }
            getLookHelper().setLookPositionWithEntity(item, 30.0F, 30.0F);
            if (getDistanceSqToEntity(item) <= 2.25D) {
                getNavigator().clearPathEntity();
                if (item.delayBeforeCanPickup > 0) { result("picking_up"); return; }
                collectTarget(item);
                return;
            }
            if (--retryTicks <= 0) {
                retryTicks = 20;
                boolean found = getNavigator().tryMoveToEntityLiving(item, 1.0D);
                boolean exhausted = pathRetry.exhausted(found);
                result(found ? "picking_up" : exhausted ? "path_not_found" : "path_retrying");
            }
        }
    }
}
