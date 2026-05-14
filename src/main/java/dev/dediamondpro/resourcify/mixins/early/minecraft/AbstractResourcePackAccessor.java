package dev.dediamondpro.resourcify.mixins.early.minecraft;

import java.io.File;

import net.minecraft.client.resources.AbstractResourcePack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractResourcePack.class)
public interface AbstractResourcePackAccessor {

    @Accessor("resourcePackFile")
    File getResourcePackFile();
}
