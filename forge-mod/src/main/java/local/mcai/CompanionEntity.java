package local.mcai;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
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
    private String pickupItemName = "";
    private int pickupMaxCount;
    private int pickupStored = -1;
    private String pickupOutcome = "";
    private boolean targetPickup;
    // Skill control lease: no world mutation after this nanoTime. Long.MAX_VALUE disables it (legacy pickup).
    private long controlDeadline = Long.MAX_VALUE;
    // Skill action deadline: the Daemon's per-action timeout, also enforced at the mutation point.
    private long actionDeadline = Long.MAX_VALUE;
    // Target-fixed Skill mine. minedStored survives stop() until the action receipt is sent.
    private int mineX, mineY, mineZ;
    private String mineBlockName = "";
    private int minedStored = -1;
    private String mineOutcome = "";
    private float mineDamage;
    private int mineSwingTicks;

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
        tasks.addTask(5, new MineTargetTask());
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
    public void pickupItem(String targetRef, int maxCount, String itemName) {
        stop();
        task = "pickup";
        targetPickup = true;
        pickupTargetId = targetRef.startsWith("item-") ? targetRef.substring("item-".length()) : targetRef;
        pickupItemName = itemName;
        pickupMaxCount = maxCount;
        pickupStored = -1;
        pickupOutcome = "";
        result("picking_up");
    }
    public boolean pickupResolved() { return pickupStored >= 0; }
    public int lastPickupStored() { return pickupStored; }
    public String pickupOutcome() { return pickupOutcome; }
    public void setControlDeadline(long value) { controlDeadline = value; }
    public void setActionDeadline(long value) { actionDeadline = value; }

    /**
     * Final authority immediately before a Skill world mutation. Returns a failure reason, or null
     * when the mutation is allowed. The Forge tick is only a safety net, so this must be checked at
     * the exact point of {@code setBlockToAir} / the pickup store.
     */
    private String mutationGuard(boolean targetValid) {
        EntityPlayer target = owner();
        return SkillMutationGuard.evaluate(isEntityAlive(), target != null && target.isEntityAlive(),
                target != null && worldObj == target.worldObj, targetValid,
                getHealth(), target == null ? Double.NaN : getDistanceSqToEntity(target),
                System.nanoTime(), controlDeadline, actionDeadline);
    }

    /** Skill mine: break one fixed block position with the best available tool. */
    public void mineBlock(String targetRef, String blockName) {
        stop();
        String body = targetRef.startsWith("block-") ? targetRef.substring("block-".length()) : targetRef;
        String[] parts = body.split("_");
        mineX = Integer.parseInt(parts[0]); mineY = Integer.parseInt(parts[1]); mineZ = Integer.parseInt(parts[2]);
        mineBlockName = blockName;
        minedStored = -1; mineOutcome = "";
        mineDamage = 0.0F; mineSwingTicks = 0;
        task = "mine";
        result("mining");
    }
    public boolean mineResolved() { return minedStored >= 0; }
    public int lastMined() { return minedStored; }
    public String mineOutcome() { return mineOutcome; }

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
            // Re-check the registry name even though the UUID matched: the same entity id may now hold
            // a different item, which must be treated as target_lost rather than collected.
            if (!pickupItemName.isEmpty()) {
                Object name = Item.itemRegistry.getNameForObject(stack.getItem());
                if (name == null || !name.toString().equals(pickupItemName)) { continue; }
            }
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
        // Final registry-name check immediately before storing: the uuid matched earlier, but the
        // stack may have been replaced. A mismatch is target_lost and no world mutation happens.
        ItemStack live = item.getEntityItem();
        Object liveName = live == null ? null : Item.itemRegistry.getNameForObject(live.getItem());
        if (live == null || live.stackSize <= 0
                || (!pickupItemName.isEmpty() && (liveName == null || !liveName.toString().equals(pickupItemName)))) {
            stop(); pickupStored = 0; pickupOutcome = "target_lost"; result("no_item_in_range"); return;
        }
        ItemStack remaining = live.copy();
        int stored = storeUpTo(remaining, Math.min(pickupMaxCount, remaining.stackSize));
        stop();
        pickupStored = stored;
        if (stored <= 0) { pickupOutcome = "inventory_full"; result("inventory_full"); return; }
        if (remaining.stackSize <= 0) { item.setDead(); } else { item.setEntityItemStack(remaining); }
        pickupOutcome = "completed";
        result("pickup_completed");
    }

    /** Deterministic tool choice: best harvesting tool in inventory, or null for bare hand. */
    private ItemStack bestTool(Block block, int meta) {
        ItemStack best = null;
        float bestSpeed = 1.0F;
        for (ItemStack stack : inventory) {
            if (stack == null || stack.stackSize <= 0 || !stack.canItemHarvestBlock(block)) { continue; }
            float speed = stack.getStrVsBlock(block);
            if (best == null || speed > bestSpeed) { best = stack; bestSpeed = speed; }
        }
        return best;
    }

    private boolean handCanHarvest(Block block, int meta) {
        return block.getMaterial().isToolNotRequired();
    }

    /** Clears the client-side breaking overlay for this companion's current mine target. */
    private void resetMineProgress() {
        worldObj.destroyBlockInWorldPartially(getEntityId(), mineX, mineY, mineZ, -1);
    }

    /** Breaks one block with the best tool, or fails tool_unavailable when a required tool is missing. */
    private void breakBlock(Block block, int x, int y, int z) {
        Block current = worldObj.getBlock(x, y, z);
        Object currentName = current == Blocks.air ? null : Block.blockRegistry.getNameForObject(current);
        boolean targetValid = currentName != null && currentName.toString().equals(mineBlockName);
        // Shared final guard immediately before the world mutation, so a health drop, owner leaving
        // range or an expired lease/deadline between the tick and this line cannot be reported as 0.
        String unsafe = mutationGuard(targetValid);
        if (unsafe != null) {
            resetMineProgress(); stop(); minedStored = 0; mineOutcome = unsafe; result("no_block_in_range"); return;
        }
        // Re-check direct access immediately before mutating the world.
        if (!MineObstruction.accessible(worldObj, posX, posY + getEyeHeight(), posZ, x, y, z)) {
            resetMineProgress(); stop(); minedStored = 0; mineOutcome = "blocked"; result("no_block_in_range"); return;
        }
        int meta = worldObj.getBlockMetadata(x, y, z);
        ItemStack tool = bestTool(block, meta);
        if (tool == null && !handCanHarvest(block, meta)) {
            resetMineProgress(); stop(); minedStored = 0; mineOutcome = "tool_unavailable"; result("tool_unavailable"); return;
        }
        block.dropBlockAsItemWithChance(worldObj, x, y, z, meta, 1.0F, 0);
        worldObj.setBlockToAir(x, y, z);
        resetMineProgress(); stop(); minedStored = 1; mineOutcome = "completed"; result("mine_completed");
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
                // Shared final guard: authority, owner/leash, health, lease and deadline.
                String unsafe = mutationGuard(true);
                if (unsafe != null) { stop(); pickupStored = 0; pickupOutcome = unsafe; result("no_item_in_range"); return; }
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

    private final class MineTargetTask extends EntityAIBase {
        private int retryTicks;
        MineTargetTask() { setMutexBits(3); }
        @Override public boolean shouldExecute() { return task.equals("mine"); }
        @Override public boolean continueExecuting() { return shouldExecute(); }
        @Override public void startExecuting() { retryTicks = 0; pathRetry.reset(); }
        @Override public void resetTask() { getNavigator().clearPathEntity(); if (!mineBlockName.isEmpty()) { resetMineProgress(); } }
        @Override public void updateTask() {
            Block block = worldObj.getBlock(mineX, mineY, mineZ);
            Object name = block == Blocks.air ? null : Block.blockRegistry.getNameForObject(block);
            if (name == null || !name.toString().equals(mineBlockName)) {
                resetMineProgress(); stop(); minedStored = 0; mineOutcome = "target_lost"; result("no_block_in_range"); return;
            }
            getLookHelper().setLookPosition(mineX + 0.5D, mineY + 0.5D, mineZ + 0.5D, 30.0F, 30.0F);
            double distance = getDistanceSq(mineX + 0.5D, mineY + 0.5D, mineZ + 0.5D);
            if (distance > 20.25D) {
                // Out of reach: cancel the partial break and walk closer.
                resetMineProgress(); mineDamage = 0.0F;
                if (--retryTicks <= 0) {
                    retryTicks = 20;
                    boolean found = getNavigator().tryMoveToXYZ(mineX, mineY, mineZ, 1.0D);
                    boolean exhausted = pathRetry.exhausted(found);
                    result(found ? "mining" : exhausted ? "path_not_found" : "path_retrying");
                }
                return;
            }
            getNavigator().clearPathEntity();
            if (System.nanoTime() > controlDeadline) {
                resetMineProgress(); stop(); minedStored = 0; mineOutcome = "disconnected"; result("no_block_in_range"); return;
            }
            // Direct access required before mining starts: never dig through a blocking block.
            if (!MineObstruction.accessible(worldObj, posX, posY + getEyeHeight(), posZ, mineX, mineY, mineZ)) {
                resetMineProgress(); stop(); minedStored = 0; mineOutcome = "blocked"; result("no_block_in_range"); return;
            }
            int meta = worldObj.getBlockMetadata(mineX, mineY, mineZ);
            ItemStack tool = bestTool(block, meta);
            if (tool == null && !handCanHarvest(block, meta)) {
                resetMineProgress(); stop(); minedStored = 0; mineOutcome = "tool_unavailable"; result("tool_unavailable"); return;
            }
            float hardness = block.getBlockHardness(worldObj, mineX, mineY, mineZ);
            if (hardness <= 0.0F) {
                if (hardness < 0.0F) { resetMineProgress(); stop(); minedStored = 0; mineOutcome = "tool_unavailable"; result("tool_unavailable"); return; }
                breakBlock(block, mineX, mineY, mineZ);
                return;
            }
            // Player-like timing: harvestable blocks use tool speed, otherwise hand speed is heavily reduced.
            boolean harvest = (tool != null && tool.canItemHarvestBlock(block)) || handCanHarvest(block, meta);
            float speed = tool != null ? tool.getStrVsBlock(block) : 1.0F;
            if (speed < 1.0F) { speed = 1.0F; }
            mineDamage += (harvest ? speed : 1.0F) / hardness / (harvest ? 30.0F : 100.0F);
            worldObj.destroyBlockInWorldPartially(getEntityId(), mineX, mineY, mineZ, (int) Math.min(9.0F, mineDamage * 10.0F));
            if (++mineSwingTicks >= 5) { mineSwingTicks = 0; swingItem(); }
            result("mining");
            if (mineDamage >= 1.0F) { breakBlock(block, mineX, mineY, mineZ); }
        }
    }
}
