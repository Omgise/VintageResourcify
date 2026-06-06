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

package dev.dediamondpro.resourcify.gui.pack

import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.config.ConfiguredPlatforms
import dev.dediamondpro.resourcify.util.ClientGuiTasks
import dev.dediamondpro.resourcify.util.LocalIndex
import dev.dediamondpro.resourcify.util.ResourcifySounds
import dev.dediamondpro.resourcify.util.ShaderGuiHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws the small "source: <platform>" badge over a pack entry's icon area,
 * sourced from the local install index. Pack files we did not install (and
 * thus have no index entry for) get no badge.
 *
 * Built-in platform icons live under `assets/vintage-resourcify/platform/<id>.png`.
 * Configured platform icons are loaded from config/vintageresourcify/icons.
 */
object PackOverlayRenderer {

    private const val BADGE_BACKGROUND_ALPHA = 0.55f
    // cross.png is a 32x16 spritesheet; left 16x16 is unhovered, right is hovered.
    private val CROSS_TEXTURE = ResourceLocation(VintageResourcify.MODID, "cross.png")
    private val DELETE_SOUND = ResourceLocation(VintageResourcify.MODID, "delete")
    private val DELETE_WARNING_SOUND = ResourceLocation(VintageResourcify.MODID, "delete_warning")
    private val ENTRY_HOVER_SOUND = ResourceLocation(VintageResourcify.MODID, "tick")
    private val ENTRY_SELECT_SOUND = ResourceLocation(VintageResourcify.MODID, "select")
    private const val CROSS_TEX_WIDTH = 32
    private const val CROSS_TEX_HEIGHT = 16
    private const val CROSS_FRAME = 16

    private data class PlatformTexture(val location: ResourceLocation, val width: Int, val height: Int)

    private val textures = mutableMapOf<String, PlatformTexture>()

    private data class DeleteRegion(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val folder: File, val file: File, val displayName: String,
    )

    // Double-buffered to handle Iris's IrisGuiSlot.drawScreen, which pumps
    // Mouse.next() events recursively *during* drawScreen - before any row
    // has rendered for this frame. If we cleared and refilled the click-hit
    // list in one buffer, the mid-frame mouseClicked would see an empty
    // list every time. Instead, drawDeleteButton appends to [scratch] while
    // [activeRegions] keeps the previous frame's complete list for clicks
    // to hit-test against. We swap at end-of-frame.
    private val activeRegions = mutableListOf<DeleteRegion>()
    private val scratch = mutableListOf<DeleteRegion>()
    private var entryHoverSeenThisFrame = false
    private var lastEntryHoverKey: String? = null
    @Volatile private var deleteReloadInProgress = false

    /** Reset the scratch buffer at the start of a frame. [activeRegions] keeps last frame's data. */
    fun beginFrame() {
        scratch.clear()
        entryHoverSeenThisFrame = false
    }

    /** Swap [scratch] into [activeRegions]. Called at drawScreen TAIL so clicks during the next frame see this frame's data. */
    fun endFrame() {
        activeRegions.clear()
        activeRegions.addAll(scratch)
        if (!entryHoverSeenThisFrame) {
            lastEntryHoverKey = null
        }
    }

    private fun textureFor(platform: String): PlatformTexture? {
        val key = when (platform.lowercase()) {
            "curse", "curseforge" -> "curse"
            "modrinth" -> "modrinth"
            "67minecraft", "67" -> "67"
            "git", "github" -> "git"
            else -> platform.lowercase()
        }
        return textures.getOrPut(key) {
            if (key in setOf("curse", "modrinth", "67", "git")) {
                PlatformTexture(ResourceLocation(VintageResourcify.MODID, "platform/$key.png"), 16, 16)
            } else {
                loadConfiguredIcon(key) ?: return null
            }
        }
    }

    private fun loadConfiguredIcon(platformId: String): PlatformTexture? {
        val file = ConfiguredPlatforms.iconFile(platformId) ?: return null
        if (!file.isFile) return null
        return try {
            val image = ImageIO.read(file) ?: return null
            val texture = Minecraft.getMinecraft().textureManager.getDynamicTextureLocation(
                "vresourcify_platform_$platformId",
                DynamicTexture(image),
            )
            PlatformTexture(texture, image.width, image.height)
        } catch (t: Throwable) {
            VintageResourcify.LOG.warn("Could not load platform icon {}", file, t)
            null
        }
    }

    fun lookupPlatform(folder: File, file: File): String? =
        LocalIndex.forFolder(folder).lookupByFile(file)?.platform

    /**
     * Draw the platform icon at [x],[y] sized [size]x[size]. Caller picks the
     * anchor (bottom-right of resource pack icons, right-edge of shader
     * rows). A controlled semi-transparent dark backdrop improves readability against pack
     * thumbnails of varying contrast. Also captures a hover request - the
     * tooltip is drained by [drainPendingTooltip] at the end of the host
     * screen's drawScreen so it paints above the pack list.
     *
     * GL state is set defensively because vanilla pack rendering leaves
     * BLEND off and the FontRenderer in font texture; without these the
     * badge ends up tinted by whatever color the previous draw set.
     */
    fun drawBadge(platform: String, x: Int, y: Int, size: Int, mouseX: Int = Int.MIN_VALUE, mouseY: Int = Int.MIN_VALUE) {
        val tex = textureFor(platform) ?: return
        // Isolate GL state. Pack rows / Iris lists leave GL_BLEND on with
        // non-standard color/alpha; pushing/popping makes the badge render
        // identical regardless of caller. Draw the backdrop via a direct
        // untextured quad rather than Gui.drawRect so the blend func and alpha
        // are fully controlled here.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_CURRENT_BIT)
        try {
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
            GL11.glColor4f(0f, 0f, 0f, BADGE_BACKGROUND_ALPHA)
            val t = Tessellator.instance
            val x0 = x - 1
            val y0 = y - 1
            val x1 = x + size + 1
            val y1 = y + size + 1
            t.startDrawingQuads()
            t.setColorRGBA_F(0f, 0f, 0f, BADGE_BACKGROUND_ALPHA)
            addQuad(t, x0, y0, x1, y1)
            t.draw()
            // Now blit the icon with standard transparency.
            GL11.glEnable(GL11.GL_TEXTURE_2D)
            GL11.glEnable(GL11.GL_BLEND)
            OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
            GL11.glColor4f(1f, 1f, 1f, 1f)
            Minecraft.getMinecraft().textureManager.bindTexture(tex.location)
            Gui.func_152125_a(
                x,
                y,
                0f,
                0f,
                tex.width,
                tex.height,
                size,
                size,
                tex.width.toFloat(),
                tex.height.toFloat(),
            )
        } finally {
            GL11.glPopAttrib()
        }
        // Capture hover so the host drawScreen can paint the tooltip on top.
        if (mouseX in x..(x + size) && mouseY in y..(y + size)) {
            pendingTooltipText = "Source: ${displayName(platform)}"
            pendingTooltipX = mouseX
            pendingTooltipY = mouseY
        }
    }

    @Volatile private var pendingTooltipText: String? = null
    @Volatile private var pendingTooltipX: Int = 0
    @Volatile private var pendingTooltipY: Int = 0

    fun queueTooltip(text: String, mouseX: Int, mouseY: Int) {
        pendingTooltipText = text
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    fun markShaderEntryHovered(screen: Any, packName: String) {
        markEntryHovered("shader:${System.identityHashCode(screen)}:$packName")
    }

    fun markResourcePackEntryHovered(entry: Any) {
        markEntryHovered("resource:${System.identityHashCode(entry)}")
    }

    private fun markEntryHovered(key: String) {
        entryHoverSeenThisFrame = true
        if (lastEntryHoverKey == key) return
        lastEntryHoverKey = key
        ResourcifySounds.play(ENTRY_HOVER_SOUND)
    }

    fun playEntrySelectSound() {
        ResourcifySounds.play(ENTRY_SELECT_SOUND)
    }

    fun playEntryTickSound() {
        ResourcifySounds.play(ENTRY_HOVER_SOUND)
    }

    /**
     * Render and clear the most recently-captured hover tooltip. Called from
     * the pack/shader screen's drawScreen TAIL so the tooltip paints above
     * the list, not behind it.
     */
    fun drainPendingTooltip() {
        val text = pendingTooltipText ?: return
        pendingTooltipText = null
        val mc = Minecraft.getMinecraft()
        val fr = mc.fontRenderer ?: return
        val pad = 3
        val tw = fr.getStringWidth(text)
        val boxW = tw + pad * 2
        val boxH = fr.FONT_HEIGHT + pad * 2
        val screenW = mc.currentScreen?.width ?: Int.MAX_VALUE
        val screenH = mc.currentScreen?.height ?: Int.MAX_VALUE
        // Prefer to the right of the cursor; flip to the left if that
        // would clip past the screen's right edge.
        var bx = pendingTooltipX + 8
        if (bx + boxW > screenW - 2) bx = (pendingTooltipX - boxW - 8).coerceAtLeast(2)
        var by = pendingTooltipY - boxH - 2
        if (by < 2) by = (pendingTooltipY + 12).coerceAtMost(screenH - boxH - 2)
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        Gui.drawRect(bx, by, bx + boxW, by + boxH, 0xFF000000.toInt())
        fr.drawString(text, bx + pad, by + pad, 0xFFFFFFFF.toInt(), false)
    }

    /**
     * Paint the delete button from `cross.png` (32x16 spritesheet: left half
     * is the rest state, right half is the hover state) and register its
     * rect for the next click. Caller is expected to only invoke this for
     * deletable packs and only when the row itself is being hovered.
     */
    fun drawDeleteButton(
        folder: File, file: File, displayName: String,
        x: Int, y: Int, size: Int,
        mouseX: Int, mouseY: Int,
    ) {
        if (!canDeletePack(folder, file)) return
        val hovered = mouseX in x..(x + size) && mouseY in y..(y + size)
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_CURRENT_BIT)
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D)
            GL11.glEnable(GL11.GL_BLEND)
            OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
            GL11.glColor4f(1f, 1f, 1f, 1f)
            Minecraft.getMinecraft().textureManager.bindTexture(CROSS_TEXTURE)
            val uOffset = if (hovered) CROSS_FRAME.toFloat() else 0f
            Gui.func_152125_a(
                x, y,
                uOffset, 0f,
                CROSS_FRAME, CROSS_TEX_HEIGHT,
                size, size,
                CROSS_TEX_WIDTH.toFloat(), CROSS_TEX_HEIGHT.toFloat(),
            )
        } finally {
            GL11.glPopAttrib()
        }
        scratch.add(DeleteRegion(x, y, x + size, y + size, folder, file, displayName))
        if (hovered) {
            pendingTooltipText = "Delete $displayName"
            pendingTooltipX = mouseX
            pendingTooltipY = mouseY
        }
    }

    /**
     * Returns true if the click at [mouseX]/[mouseY] hit one of the rects
     * recorded by [drawDeleteButton] this frame. Caller cancels the host
     * screen's normal mouseClicked dispatch when this returns true. On a hit
     * we hand off to a vanilla [GuiYesNo] dialog; [refresh] is invoked by
     * the dialog's callback after the user picks Yes or No (with the file
     * already deleted if they confirmed).
     */
    fun handleDeleteClick(mouseX: Int, mouseY: Int, button: Int, refresh: () -> Unit): Boolean {
        if (deleteReloadInProgress) return true
        if (button != 0) return false
        val hit = deleteRegionAt(mouseX, mouseY) ?: return false
        val mc = Minecraft.getMinecraft()
        playDeleteWarningSound()
        val confirm = object : net.minecraft.client.gui.GuiYesNoCallback {
            override fun confirmClicked(confirmed: Boolean, id: Int) {
                if (confirmed) {
                    deleteReloadInProgress = true
                    var deleted = false
                    try {
                        deleted = performDelete(hit)
                        refresh()
                    } finally {
                        ClientGuiTasks.runNextClientTick {
                            try {
                                if (deleted) playDeleteSound()
                            } finally {
                                deleteReloadInProgress = false
                            }
                        }
                    }
                } else {
                    refresh()
                }
            }
        }
        mc.displayGuiScreen(
            net.minecraft.client.gui.GuiYesNo(
                confirm,
                "Delete '${hit.displayName}'?",
                "This will remove the file from disk.",
                0,
            )
        )
        return true
    }

    fun isDeleteButtonAt(mouseX: Int, mouseY: Int): Boolean =
        deleteRegionAt(mouseX, mouseY) != null

    private fun deleteRegionAt(mouseX: Int, mouseY: Int): DeleteRegion? {
        val hit = activeRegions.firstOrNull { mouseX in it.x1..it.x2 && mouseY in it.y1..it.y2 }
            ?: scratch.firstOrNull { mouseX in it.x1..it.x2 && mouseY in it.y1..it.y2 }
            ?: return null
        return hit.takeIf { canDeletePack(it.folder, it.file) }
    }

    private fun playDeleteWarningSound() {
        ResourcifySounds.play(DELETE_WARNING_SOUND)
    }

    private fun playDeleteSound() {
        ResourcifySounds.play(DELETE_SOUND)
    }

    private fun performDelete(hit: DeleteRegion): Boolean {
        try {
            if (!canDeletePack(hit.folder, hit.file)) {
                VintageResourcify.LOG.warn("Refusing to delete non-pack file {} from {}", hit.file, hit.folder)
                return false
            }
            // Drop Forge's open handle for resource packs before deleting.
            // For shader packs we don't hold an open handle, and the call
            // simply no-ops.
            try {
                dev.dediamondpro.resourcify.platform.Platform.closeResourcePack(hit.file)
            } catch (_: Throwable) {
            }
            // Also remove from the enabled resource-pack list in options.txt
            // so the refreshed pack screen doesn't try to render a stale
            // entry pointing at a now-missing file.
            try {
                val opts = Minecraft.getMinecraft().gameSettings
                val name = hit.file.name
                if (opts.resourcePacks.removeAll { it == name }) {
                    opts.saveOptions()
                }
            } catch (e: Throwable) {
                VintageResourcify.LOG.warn("Could not drop {} from active resource pack list", hit.file.name, e)
            }
            val deleted = deleteTarget(hit.file)
            if (!deleted) {
                VintageResourcify.LOG.warn("Could not delete pack {}", hit.file)
                return false
            }
            LocalIndex.forFolder(hit.folder).remove(hit.file.name)
            // Drive the same refresh path the host screen's "Done" button
            // would. Without this, the repository / Iris keeps the deleted
            // entry loaded and the host re-renders the row.
            try {
                val mc = Minecraft.getMinecraft()
                val shadersFolder = try { ShaderGuiHelper.getShaderpacksFolder() } catch (_: Throwable) { null }
                if (shadersFolder != null && hit.folder.canonicalPath == shadersFolder.canonicalPath) {
                    // Shader pack delete: ask the active shader GUI backend
                    // to rescan and drop the active pack if it was removed.
                    ShaderGuiHelper.reload()
                } else {
                    val repo = mc.resourcePackRepository
                    val current = ArrayList(repo.repositoryEntries)
                    val filtered = current.filter { e ->
                        (e as? dev.dediamondpro.resourcify.mixins.early.minecraft.ResourcePackRepositoryEntryAccessor)
                            ?.resourcePackFile != hit.file
                    }
                    val wasEnabled = filtered.size != current.size
                    if (wasEnabled) {
                        repo.func_148527_a(filtered)
                    }
                    repo.updateRepositoryEntriesAll()
                    if (wasEnabled) {
                        mc.refreshResources()
                    }
                }
            } catch (e: Throwable) {
                VintageResourcify.LOG.warn("Could not refresh resources after delete", e)
            }
            VintageResourcify.LOG.info("Deleted pack {} from {}", hit.file.name, hit.folder)
            return true
        } catch (e: Throwable) {
            VintageResourcify.LOG.warn("Failed to delete {}", hit.file, e)
            return false
        }
    }

    private fun deleteTarget(file: File): Boolean {
        if (!file.isDirectory) return file.delete()
        var deletedChildren = true
        file.listFiles()?.forEach { deletedChildren = deleteTarget(it) && deletedChildren }
        return file.delete() && deletedChildren
    }

    /**
     * Only delete packs that are actual files or directories directly inside
     * the active pack folder. Synthetic repository entries, like virtual
     * resource packs provided by other mods, often carry placeholder File
     * values and must not get a delete affordance.
     */
    fun canDeletePack(folder: File, file: File?): Boolean {
        if (file == null || !file.exists()) return false
        if (!file.isFile && !file.isDirectory) return false

        val canonicalFolder = safeCanonical(folder)
        val canonicalFile = safeCanonical(file)
        return canonicalFile != canonicalFolder && canonicalFile.parentFile == canonicalFolder
    }

    private fun safeCanonical(file: File): File {
        return try {
            file.canonicalFile
        } catch (_: Throwable) {
            file.absoluteFile
        }
    }

    private fun displayName(platform: String): String = when (platform.lowercase()) {
        "curse", "curseforge" -> "CurseForge"
        "modrinth" -> "Modrinth"
        "67minecraft", "67" -> "67Minecraft"
        "git", "github" -> "GitHub"
        else -> ConfiguredPlatforms.displayName(platform) ?: platform.replaceFirstChar { it.uppercase() }
    }

    private fun addQuad(t: Tessellator, left: Int, top: Int, right: Int, bottom: Int) {
        t.addVertex(left.toDouble(), bottom.toDouble(), 0.0)
        t.addVertex(right.toDouble(), bottom.toDouble(), 0.0)
        t.addVertex(right.toDouble(), top.toDouble(), 0.0)
        t.addVertex(left.toDouble(), top.toDouble(), 0.0)
    }

    /** Resolve the shaderpacks folder once per call; cheap and safe to call from a render path. */
    fun shaderpacksFolder(): File = ShaderGuiHelper.getShaderpacksFolder()

    fun resourcePacksFolder(): File = Minecraft.getMinecraft().resourcePackRepository.dirResourcepacks

    /** Convert a pack name (as Iris uses it - e.g. "MyShader") to the file we recorded under. */
    fun shaderPackFile(folder: File, packName: String): File? {
        val zip = File(folder, "$packName.zip")
        if (zip.exists()) return zip
        val raw = File(folder, packName)
        if (raw.exists()) return raw
        return null
    }
}
