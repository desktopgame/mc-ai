package local.mcai;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/** World-persistent ownership prevents duplicates even when a companion's chunk is unloaded. */
public final class CompanionRegistry extends WorldSavedData {
    private static final String KEY = "mcai_companions";
    private NBTTagCompound owners = new NBTTagCompound();
    public CompanionRegistry(String name) { super(name); }

    public static CompanionRegistry get() {
        World world = MinecraftServer.getServer().worldServerForDimension(0);
        CompanionRegistry data = (CompanionRegistry) world.loadItemData(CompanionRegistry.class, KEY);
        if (data == null) {
            data = new CompanionRegistry(KEY);
            world.setItemData(KEY, data);
        }
        return data;
    }

    public String find(String owner) { return owners.getString(owner); }
    public void register(String owner, String entity) { owners.setString(owner, entity); markDirty(); }
    public void remove(String owner, String entity) {
        if (find(owner).equals(entity)) { owners.removeTag(owner); markDirty(); }
    }
    @Override public void readFromNBT(NBTTagCompound nbt) { owners = nbt.getCompoundTag("owners"); }
    @Override public void writeToNBT(NBTTagCompound nbt) { nbt.setTag("owners", owners); }
}
