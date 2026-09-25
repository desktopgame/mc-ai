package local.mcai;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import org.apache.logging.log4j.LogManager;

@Mod(modid = CompanionMod.MOD_ID, name = "MC AI Companion", version = "0.0.1",
        acceptedMinecraftVersions = "[1.7.10]")
public final class CompanionMod {
    public static final String MOD_ID = "mcaicompanion";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LogManager.getLogger(MOD_ID).info("MC AI Companion initialized (Phase 0)");
    }
}
