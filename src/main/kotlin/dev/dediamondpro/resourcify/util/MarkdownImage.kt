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

import com.cleanroommc.modularui.api.widget.Interactable
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext
import com.cleanroommc.modularui.theme.WidgetThemeEntry
import com.cleanroommc.modularui.widget.Widget
import com.cleanroommc.modularui.widget.WidgetTree
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
 * Block-level markdown image. Async-fetches the URL through [NetworkUtil] on
 * the first call to [draw], registers the result as a vanilla DynamicTexture,
 * and renders it scaled to the widget's width.
 *
 * Sized lazily: when the image arrives, we resize ourselves to a height that
 * preserves aspect ratio against the column width. Until then we hold a 32px
 * placeholder so the surrounding ListWidget reserves space.
 */
class MarkdownImage(
    private val url: URL,
    private val maxWidth: Int,
    private val linkUrl: String? = null,
) : Widget<MarkdownImage>(), Interactable {

    private var requested = false
    private var texture: ResourceLocation? = null
    private var imgW = 0
    private var imgH = 0
    private var failed = false

    init {
        widthRel(1f)
        if (linkUrl != null) {
            tooltip().addLine(linkUrl)
        }
    }

    override fun onMousePressed(mouseButton: Int): Interactable.Result {
        // Only ACCEPT so onMouseTapped fires on release - opening the
        // confirm dialog inside onMousePressed leaves MUI2's mouse state
        // half-pressed and ends up replaying the click on the next
        // released-anywhere event (which fires when the user dismisses the
        // confirm and then presses Escape, etc.).
        return if (linkUrl != null && mouseButton == 0) Interactable.Result.ACCEPT else Interactable.Result.IGNORE
    }

    override fun onMouseTapped(mouseButton: Int): Interactable.Result {
        if (mouseButton != 0 || linkUrl == null) return Interactable.Result.IGNORE
        val current = Minecraft.getMinecraft().currentScreen
        UrlOpener.openLinkPrompted(linkUrl, current)
        return Interactable.Result.SUCCESS
    }

    override fun getDefaultHeight(): Int {
        if (failed) return 12
        val w = if (imgW > 0) imgW else maxWidth
        val h = if (imgH > 0) imgH else 32
        // Scale to fit within column.
        val scale = if (w > maxWidth) maxWidth.toFloat() / w else 1f
        return (h * scale).toInt().coerceAtLeast(8)
    }

    override fun draw(context: ModularGuiContext, widgetTheme: WidgetThemeEntry<*>) {
        ensureRequested()
        val rl = texture ?: return
        val w = imgW
        val h = imgH
        if (w <= 0 || h <= 0) return
        val drawW: Int
        val drawH: Int
        if (w > maxWidth) {
            drawW = maxWidth
            drawH = (h.toFloat() * maxWidth / w).toInt()
        } else {
            drawW = w
            drawH = h
        }
        // See AsyncIcon: Gui.func_152125_a doesn't set up textures/blend
        // itself, so set them up defensively or first-in-frame images render
        // as solid white quads.
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        Minecraft.getMinecraft().textureManager.bindTexture(rl)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        Gui.func_152125_a(0, 0, 0f, 0f, w, h, drawW, drawH, w.toFloat(), h.toFloat())
    }

    private fun ensureRequested() {
        if (requested) return
        requested = true
        try {
            // Skip the wsrv.nl resize proxy. Same issue as AsyncIcon: certain
            // PNGs (e.g. some imgur uploads) come back from the resize proxy
            // with broken pixel data and render blank. Fetching the original
            // is more reliable; we downscale at GL draw time.
            url.getImageAsync().thenAccept { img ->
                if (img == null) {
                    failed = true
                    return@thenAccept
                }
                Minecraft.getMinecraft()
                    .func_152344_a { adoptImage(img) }
            }
        } catch (e: Exception) {
            VintageResourcify.LOG.warn("Failed to load markdown image {}", url, e)
            failed = true
        }
    }

    private fun adoptImage(img: BufferedImage) {
        try {
            val dt = DynamicTexture(img)
            val name = "vresourcify_md_${idCounter.incrementAndGet()}"
            texture = Minecraft.getMinecraft().textureManager.getDynamicTextureLocation(name, dt)
            imgW = img.width
            imgH = img.height
            // Trigger an immediate relayout. Without this, MUI2 keeps using
            // the placeholder 32px height for this widget's area, which causes
            // scroll culling to drop the image as soon as its actual painted
            // pixels extend beyond the cached area (i.e. shortly after scroll
            // starts moving it out of view).
            val root = getPanel()
            if (root != null) {
                WidgetTree.resizeInternal(root.resizer(), false)
            }
        } catch (e: Exception) {
            VintageResourcify.LOG.warn("Failed to register texture for {}", url, e)
            failed = true
        }
    }

    companion object {
        private val idCounter = AtomicInteger()
    }
}
