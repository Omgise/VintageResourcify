package dev.dediamondpro.resourcify.mixins.early.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreenResourcePacks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.dediamondpro.resourcify.gui.pack.PackScreensAddition;
import dev.dediamondpro.resourcify.services.ProjectType;

@Mixin(GuiScreenResourcePacks.class)
public class MixinGuiScreenResourcePacks {

    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void resourcify$drawAddButton(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        PackScreensAddition.INSTANCE.onRender(ProjectType.RESOURCE_PACK);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void resourcify$clickAddButton(int mouseX, int mouseY, int mouseButton, CallbackInfo ci) {
        PackScreensAddition.INSTANCE.onMouseClick(
            mouseX,
            mouseY,
            mouseButton,
            ProjectType.RESOURCE_PACK,
            Minecraft.getMinecraft()
                .getResourcePackRepository()
                .getDirResourcepacks());
    }
}
