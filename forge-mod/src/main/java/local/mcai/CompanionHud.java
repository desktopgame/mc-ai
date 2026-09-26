package local.mcai;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

/** Client-only status dot replacing debug chat spam: thinking / acting / idle. */
public final class CompanionHud {
    private final ActionBridge actions;
    private final PingBridge social;

    public CompanionHud(ActionBridge actions, PingBridge social) { this.actions = actions; this.social = social; }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) { return; }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null) { return; }
        String state = social.thinking() ? "thinking" : actions.displayState();
        String label; int color;
        if (state.equals("thinking")) { label = "考え中"; color = 0xFFF1C40F; }
        else if (state.equals("running")) { label = "行動中"; color = 0xFF2ECC71; }
        else { label = "待機中"; color = 0xFF95A5A6; }
        int x = event.resolution.getScaledWidth() - 74, y = 6;
        Gui.drawRect(x, y, x + 8, y + 8, color);
        mc.fontRendererObj.drawStringWithShadow(label, x + 12, y - 1, 0xFFFFFF);
    }
}
