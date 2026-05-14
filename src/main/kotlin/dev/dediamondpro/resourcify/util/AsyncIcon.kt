/*
 * This file is part of Resourcify
 * Copyright (C) 2024 DeDiamondPro
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License Version 3 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.dediamondpro.resourcify.util

import com.cleanroommc.modularui.screen.viewport.ModularGuiContext
import com.cleanroommc.modularui.theme.WidgetThemeEntry
import com.cleanroommc.modularui.widget.Widget
import dev.dediamondpro.resourcify.VintageResourcify
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-size square icon for project thumbnails in the browse list and
 * project header. Async-fetches the URL and binds a DynamicTexture on the
 * first draw; until ready, draws nothing (the surrounding row reserves its
 * space via the explicit .size() on this widget).
 */
class AsyncIcon(private val url: URL?, private val sizePx: Int) : Widget<AsyncIcon>() {

    private var requested = false
    private var texture: ResourceLocation? = null
    private var imgW = 0
    private var imgH = 0
    private var failed = false

    init {
        size(sizePx, sizePx)
    }

    override fun draw(context: ModularGuiContext, widgetTheme: WidgetThemeEntry<*>) {
        if (url == null || failed) return
        ensureRequested()
        val rl = texture ?: return
        Minecraft.getMinecraft().textureManager.bindTexture(rl)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        Gui.func_152125_a(
            0, 0, 0f, 0f, imgW, imgH, sizePx, sizePx, imgW.toFloat(), imgH.toFloat(),
        )
    }

    private fun ensureRequested() {
        if (requested || url == null) return
        requested = true
        try {
            url.getImageAsync(width = sizePx.toFloat()).thenAccept { img ->
                if (img == null) {
                    failed = true
                    return@thenAccept
                }
                Minecraft.getMinecraft()
                    .func_152344_a { adoptImage(img) }
            }
        } catch (e: Exception) {
            VintageResourcify.LOG.warn("Failed to load icon {}", url, e)
            failed = true
        }
    }

    private fun adoptImage(img: BufferedImage) {
        try {
            val dt = DynamicTexture(img)
            val name = "vresourcify_icon_${idCounter.incrementAndGet()}"
            texture = Minecraft.getMinecraft().textureManager.getDynamicTextureLocation(name, dt)
            imgW = img.width
            imgH = img.height
        } catch (e: Exception) {
            VintageResourcify.LOG.warn("Failed to register icon texture for {}", url, e)
            failed = true
        }
    }

    companion object {
        private val idCounter = AtomicInteger()
    }
}
