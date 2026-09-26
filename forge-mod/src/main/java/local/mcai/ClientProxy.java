package local.mcai;

import cpw.mods.fml.client.registry.RenderingRegistry;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;

public final class ClientProxy extends CommonProxy {
    @Override public void registerRenderers() {
        RenderingRegistry.registerEntityRenderingHandler(CompanionEntity.class,
                new RenderLiving(new ModelBiped(), 0.5F) {
                    private final ResourceLocation texture = new ResourceLocation("textures/entity/steve.png");
                    @Override protected ResourceLocation getEntityTexture(Entity entity) { return texture; }
                });
    }
    @Override public void registerHud(ActionBridge actions, PingBridge social) {
        MinecraftForge.EVENT_BUS.register(new CompanionHud(actions, social));
    }
}
