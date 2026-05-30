package dev.dediamondpro.resourcify.util

import dev.dediamondpro.resourcify.config.Config
import net.minecraft.client.Minecraft
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.util.ResourceLocation

object ResourcifySounds {

    @JvmStatic
    fun play(sound: ResourceLocation, pitch: Float = 1.0f) {
        if (!Config.instance.enableSounds) return
        Minecraft.getMinecraft().soundHandler.playSound(
            PositionedSoundRecord.func_147674_a(sound, pitch)
        )
    }
}
