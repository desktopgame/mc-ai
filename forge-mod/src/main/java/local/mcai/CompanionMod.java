package local.mcai;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;

@Mod(modid = CompanionMod.MOD_ID, name = "MC AI Companion", version = "0.0.27",
        acceptedMinecraftVersions = "[1.7.10]")
public final class CompanionMod {
    public static final String MOD_ID = "mcaicompanion";
    @SidedProxy(clientSide = "local.mcai.ClientProxy", serverSide = "local.mcai.CommonProxy")
    public static CommonProxy proxy;
    private String daemonUrl;
    private boolean verboseMessages;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        daemonUrl = config.getString("daemonUrl", "network", "http://127.0.0.1:8766",
                "Agent Daemon base URL. LAN use must be configured explicitly.");
        verboseMessages = config.getBoolean("verboseChatMessages", "debug", false,
                "Show internal action/goal lifecycle chat messages (queued, thinking, stopped, failed, ...). "
                        + "Off by default; the on-screen status icon shows thinking/acting/idle instead.");
        config.save();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        EntityRegistry.registerModEntity(CompanionEntity.class, "Companion", 0, this, 80, 3, true);
        proxy.registerRenderers();
        final IoExecutors io = new IoExecutors();
        // Process lifetime, not per-world: a slow old HTTP call must not create another pool on rejoin.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { io.close(); }
        }, "mc-ai-io-shutdown"));
        ObservationBridge observations = new ObservationBridge(daemonUrl, io);
        ActionBridge actions = new ActionBridge(daemonUrl, observations, io, verboseMessages);
        MinecraftForge.EVENT_BUS.register(new CompanionCommands(actions));
        MinecraftForge.EVENT_BUS.register(actions);
        PingBridge bridge = new PingBridge(daemonUrl, actions, io, verboseMessages);
        actions.setPingBridge(bridge);
        MinecraftForge.EVENT_BUS.register(bridge);
        FMLCommonHandler.instance().bus().register(bridge);
        FMLCommonHandler.instance().bus().register(observations);
        FMLCommonHandler.instance().bus().register(actions);
        proxy.registerHud(actions, bridge);
        LogManager.getLogger(MOD_ID).info("MC AI Companion initialized (Action lifecycle)");
    }
}
