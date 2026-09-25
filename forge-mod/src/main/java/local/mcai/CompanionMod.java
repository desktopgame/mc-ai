package local.mcai;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;

@Mod(modid = CompanionMod.MOD_ID, name = "MC AI Companion", version = "0.0.2",
        acceptedMinecraftVersions = "[1.7.10]")
public final class CompanionMod {
    public static final String MOD_ID = "mcaicompanion";
    private String daemonUrl;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        daemonUrl = config.getString("daemonUrl", "network", "http://127.0.0.1:8766",
                "Agent Daemon base URL. LAN use must be configured explicitly.");
        config.save();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        PingBridge bridge = new PingBridge(daemonUrl);
        MinecraftForge.EVENT_BUS.register(bridge);
        FMLCommonHandler.instance().bus().register(bridge);
        LogManager.getLogger(MOD_ID).info("MC AI Companion initialized (Phase 1)");
    }
}
