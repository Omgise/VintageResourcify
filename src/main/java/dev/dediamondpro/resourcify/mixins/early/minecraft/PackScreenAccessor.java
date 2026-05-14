package dev.dediamondpro.resourcify.mixins.early.minecraft;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenResourcePacks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiScreenResourcePacks.class)
public interface PackScreenAccessor {

    @Accessor("field_146965_f")
    GuiScreen getParentScreen();
    // 1.7.10 GuiScreenResourcePacks has no MCP name for parentScreen; the SRG
    // name field_146965_f is also what the source uses.

}
