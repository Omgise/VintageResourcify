package dev.dediamondpro.resourcify.mixins.early.minecraft;

import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ResourcePackRepository.Entry.class)
public interface ResourcePackEntryAccessor {

    @Accessor("locationTexturePackIcon")
    void setLocationTexturePackIcon(ResourceLocation location);
}
