/*
 * This file is part of Resourcify
 * Copyright (C) 2023-2024 DeDiamondPro
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

package dev.dediamondpro.resourcify.gui.projectpage

import com.cleanroommc.modularui.api.drawable.IKey
import com.cleanroommc.modularui.api.drawable.IDrawable
import com.cleanroommc.modularui.api.widget.Interactable
import com.cleanroommc.modularui.api.widget.IWidget
import com.cleanroommc.modularui.drawable.GuiDraw
import com.cleanroommc.modularui.drawable.Rectangle
import com.cleanroommc.modularui.screen.ModularPanel
import com.cleanroommc.modularui.screen.ModularScreen
import com.cleanroommc.modularui.screen.viewport.GuiContext
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext
import com.cleanroommc.modularui.theme.WidgetTheme
import com.cleanroommc.modularui.theme.WidgetThemeEntry
import com.cleanroommc.modularui.widgets.ButtonWidget
import com.cleanroommc.modularui.widgets.ListWidget
import com.cleanroommc.modularui.widgets.TextWidget
import com.cleanroommc.modularui.widget.ParentWidget
import com.cleanroommc.modularui.widget.Widget
import com.cleanroommc.modularui.widgets.layout.Flow
import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.config.Config
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.IGalleryImage
import dev.dediamondpro.resourcify.services.IProject
import dev.dediamondpro.resourcify.services.IVersion
import dev.dediamondpro.resourcify.services.ProjectType
import dev.dediamondpro.resourcify.util.AsyncIcon
import dev.dediamondpro.resourcify.util.formatCompact
import dev.dediamondpro.resourcify.util.DownloadManager
import dev.dediamondpro.resourcify.util.IrisHelper
import dev.dediamondpro.resourcify.util.MarkdownRenderer
import dev.dediamondpro.resourcify.util.getImageAsync
import dev.dediamondpro.resourcify.util.toURL
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiScreenResourcePacks
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.init.Items
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraft.util.EnumChatFormatting
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

private fun compareVersionDesc(a: String, b: String): Int {
    val aParts = a.split(".", "-").mapNotNull { it.toIntOrNull() }
    val bParts = b.split(".", "-").mapNotNull { it.toIntOrNull() }
    val len = maxOf(aParts.size, bParts.size)
    for (i in 0 until len) {
        val av = aParts.getOrNull(i) ?: 0
        val bv = bParts.getOrNull(i) ?: 0
        if (av != bv) return bv - av
    }
    return b.compareTo(a)
}

private class SimpleButton : ButtonWidget<SimpleButton>()
private class SimpleList : ListWidget<IWidget, SimpleList>()
private class Container : ParentWidget<Container>()

private class CenteredItemDrawable(
    private val stack: ItemStack,
    private val itemSize: Int,
    private val xOffset: Float = 0f,
    private val yOffset: Float = 0f,
) : IDrawable {
    override fun draw(context: GuiContext, x: Int, y: Int, width: Int, height: Int, widgetTheme: WidgetTheme) {
        applyColor(widgetTheme.color)
        GL11.glPushMatrix()
        try {
            GL11.glTranslatef(xOffset, yOffset, 0f)
            GuiDraw.drawItem(
                stack,
                x + (width - itemSize) / 2,
                y + (height - itemSize) / 2,
                itemSize.toFloat(),
                itemSize.toFloat(),
                context.currentDrawingZ,
            )
        } finally {
            GL11.glPopMatrix()
        }
    }
}

private class ResourcePackArrowDrawable(private val direction: Direction, private val hovered: Boolean) : IDrawable {
    enum class Direction(val u: Float) {
        RIGHT(0f),
        LEFT(32f),
    }

    override fun draw(context: GuiContext, x: Int, y: Int, width: Int, height: Int, widgetTheme: WidgetTheme) {
        applyColor(widgetTheme.color)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        Minecraft.getMinecraft().textureManager.bindTexture(TEXTURE)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        Gui.func_146110_a(
            x + (width - GALLERY_ARROW_SIZE) / 2,
            y + (height - GALLERY_ARROW_SIZE) / 2,
            direction.u,
            if (hovered) 32f else 0f,
            GALLERY_ARROW_SIZE,
            GALLERY_ARROW_SIZE,
            256f,
            256f,
        )
    }

    companion object {
        private val TEXTURE = ResourceLocation("textures/gui/resource_packs.png")
    }
}

private class GalleryImageWidget(
    private val setStatus: (String?) -> Unit,
) : Widget<GalleryImageWidget>(), Interactable {
    private var requestedUrl: URL? = null
    private var loadingUrl: URL? = null
    private var failedUrl: URL? = null
    private var texture: ResourceLocation? = null
    private var imgW = 0
    private var imgH = 0

    fun show(url: URL?) {
        requestedUrl = url
        loadingUrl = null
        failedUrl = null
        texture = null
        imgW = 0
        imgH = 0
        if (url == null) {
            setStatus("Invalid gallery image")
            return
        }
        setStatus("Loading image...")
        ensureRequested()
    }

    override fun onMousePressed(mouseButton: Int): Interactable.Result {
        return Interactable.Result.SUCCESS
    }

    override fun draw(context: ModularGuiContext, widgetTheme: WidgetThemeEntry<*>) {
        ensureRequested()
        val rl = texture ?: return
        val w = imgW
        val h = imgH
        if (w <= 0 || h <= 0) return

        val areaW = getArea().width.coerceAtLeast(1)
        val areaH = getArea().height.coerceAtLeast(1)
        val scale = minOf(areaW.toFloat() / w, areaH.toFloat() / h)
        val drawW = (w * scale).toInt().coerceAtLeast(1)
        val drawH = (h * scale).toInt().coerceAtLeast(1)
        val drawX = (areaW - drawW) / 2
        val drawY = (areaH - drawH) / 2

        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        Minecraft.getMinecraft().textureManager.bindTexture(rl)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        Gui.func_152125_a(drawX, drawY, 0f, 0f, w, h, drawW, drawH, w.toFloat(), h.toFloat())
    }

    private fun ensureRequested() {
        val url = requestedUrl ?: return
        if (loadingUrl == url || failedUrl == url || texture != null) return
        loadingUrl = url
        try {
            url.getImageAsync().whenComplete { img, error ->
                Minecraft.getMinecraft().func_152344_a {
                    if (!isValid() || requestedUrl != url) return@func_152344_a
                    loadingUrl = null
                    if (error != null || img == null) {
                        VintageResourcify.LOG.warn("Failed to load gallery image {}", url, error)
                        failedUrl = url
                        setStatus("Failed to load image")
                        return@func_152344_a
                    }
                    adoptImage(url, img)
                }
            }
        } catch (e: Exception) {
            loadingUrl = null
            failedUrl = url
            VintageResourcify.LOG.warn("Failed to request gallery image {}", url, e)
            setStatus("Failed to load image")
        }
    }

    private fun adoptImage(url: URL, img: BufferedImage) {
        try {
            val dt = DynamicTexture(img)
            val name = "vresourcify_gallery_${idCounter.incrementAndGet()}"
            texture = Minecraft.getMinecraft().textureManager.getDynamicTextureLocation(name, dt)
            imgW = img.width
            imgH = img.height
            failedUrl = null
            setStatus(null)
        } catch (e: Exception) {
            VintageResourcify.LOG.warn("Failed to register gallery image texture {}", url, e)
            setStatus("Failed to load image")
        }
    }

    companion object {
        private val idCounter = AtomicInteger()
    }
}

// Layout constants live here so the column edges line up across the header,
// summary, body. Values are in GUI-scaled pixels.
private const val GUTTER = 12
private const val COL_GAP = 12
private const val DESC_REL_WIDTH = 0.62f
private const val VER_REL_WIDTH = 1f - DESC_REL_WIDTH
private const val DESC_PAD = 8
private const val GALLERY_BUTTON_SIZE = 22
private const val GALLERY_BUTTON_GAP = 4
private const val GALLERY_BUTTON_RIGHT_NUDGE = 3
private const val GALLERY_OVERLAY_MARGIN = 24
private const val GALLERY_ARROW_SIZE = 32
private const val GALLERY_ARROW_GAP = 8

// See BrowseScreen's commit 8a9f9e5 for why all state lives in the lambda
// closure rather than in instance fields.
class ProjectScreen(
    project: IProject,
    type: ProjectType,
    packsFolder: File,
    sourceParent: GuiScreen?,
    platformId: String,
    initialMcVersion: String = Platform.getMcVersion(),
) : ModularScreen(VintageResourcify.MODID, { _ ->
    VintageResourcify.LOG.info("ProjectScreen lambda entry: project={}", project.getId())
    val themeName = Config.instance.markdownTheme.lowercase()
    val isLight = themeName == "light"
    val descBackground = if (isLight) 0xFFF6F8FA.toInt() else 0xFF0D1117.toInt()
    val rowBackground = if (isLight) 0x10000000 else 0x20FFFFFF
    val accent = if (isLight) 0xFF1F2328.toInt() else 0xFFF0F6FC.toInt()

    val descriptionList = SimpleList()
        .child(TextWidget(IKey.str("Loading description...")).color(accent))
    val versionsList = SimpleList()
        .child(TextWidget(IKey.str("Loading versions...")))
    val versionFilterHolder = Container()
    val selectedMcVersion = arrayOf<String?>(initialMcVersion)
    val allVersions = arrayOf<List<IVersion>>(emptyList())

    // Compute exact pixel column widths up front so both loadVersions and
    // loadDescription can reference them. Mixing widthRel for two siblings
    // against fixed left/right gutters means the columns overlap by 2*GUTTER
    // pixels, so we use absolute pixel widths derived from ScaledResolution.
    val mc0 = Minecraft.getMinecraft()
    val sr0 = ScaledResolution(mc0, mc0.displayWidth, mc0.displayHeight)
    val contentW = sr0.scaledWidth - 2 * GUTTER - COL_GAP
    val descColW = (contentW * DESC_REL_WIDTH).toInt()
    val verColW = contentW - descColW
    val verColLeft = GUTTER + descColW + COL_GAP
    val descListW = descColW - GALLERY_BUTTON_SIZE - GALLERY_BUTTON_GAP
    val descTextW = descListW - DESC_PAD * 2 - 8 // 8 = scrollbar inset

    fun renderVersions() {
        versionsList.removeAll()
        val selected = selectedMcVersion[0]
        val matching = if (selected == null) allVersions[0]
        else allVersions[0].filter { it.getMinecraftVersions().contains(selected) }
        if (matching.isEmpty()) {
            val label = if (selected == null) "No versions available"
            else "No $selected versions available"
            versionsList.child(TextWidget(IKey.str(label)))
            return
        }
        val buttonW = 56
        val buttonRightInset = 6
        val nameLeft = 8
        val nameButtonGap = 12
        val nameRight = nameLeft + buttonW + buttonRightInset + nameButtonGap
        val nameW = (verColW - nameRight).coerceAtLeast(40)
        val fr = Minecraft.getMinecraft().fontRenderer
        matching.forEach { version: IVersion ->
            val rawName = version.getName()
            val truncated = fr != null && fr.getStringWidth(rawName) > nameW
            val displayName = if (truncated)
                fr.trimStringToWidth(rawName, nameW - fr.getStringWidth("...")) + "..."
            else rawName
            val nameWidget = TextWidget(IKey.str(displayName))
                .left(nameLeft).width(nameW).heightRel(1f)
                .color(accent)
                .alignment(com.cleanroommc.modularui.utils.Alignment.CenterLeft)
            if (truncated) {
                nameWidget.tooltip().addLine(rawName)
            }
            val row = Flow.row()
                .widthRel(1f).height(22).margin(0, 0, 0, 3)
                .background(Rectangle().color(rowBackground))
                .child(nameWidget)
                .child(
                    SimpleButton()
                        .size(buttonW, 16).right(buttonRightInset).top(3)
                        .overlay(IKey.str("Install"))
                        .onMousePressed { btn ->
                            if (btn == 0) {
                                install(version, project, type, packsFolder, sourceParent, platformId)
                                true
                            } else false
                        }
                )
            versionsList.child(row)
        }
    }

    fun loadVersions() {
        project.getVersions().thenAccept { versions ->
            Minecraft.getMinecraft().func_152344_a {
                allVersions[0] = versions
                // Build the MC-version dropdown from every version this
                // project actually ships. Order: newest-first (lexicographic
                // by dotted segments is wrong for "1.10" vs "1.9", so we
                // compare as version tuples). "Any" sits at the top so users
                // can grab e.g. a shader that wasn't marked compatible with
                // their MC version but works anyway.
                val mcVersions = versions.flatMap { it.getMinecraftVersions() }.toSet().toList()
                    .sortedWith(Comparator { a, b -> compareVersionDesc(a, b) })

                // If the browse-screen MC version isn't among the project's
                // supported versions, default to "Any" so the user can still
                // see the version list instead of an empty pane.
                if (selectedMcVersion[0] != null && selectedMcVersion[0] !in mcVersions) {
                    selectedMcVersion[0] = null
                }

                versionFilterHolder.removeAll()
                // Cap the popup so it never extends past the bottom of the
                // window. holder.top is bodyTop+18, popup sits at +28 inside
                // it, leave a GUTTER gutter at the bottom.
                val popupTopAbsolute = (GUTTER + 64) + 18 + 28
                val maxPopupH = (sr0.scaledHeight - popupTopAbsolute - GUTTER).coerceAtLeast(40)
                val desiredH = (mcVersions.size + 1) * 12 + 4
                val popup = SimpleList()
                    .top(28).left(0).widthRel(1f).height(desiredH.coerceAtMost(maxPopupH))
                    .background(Rectangle().color(0xFF1F2328.toInt()))
                popup.setEnabled(false)
                val button = SimpleButton().widthRel(1f).height(14).top(12).left(0)
                button.overlay(IKey.dynamic {
                    val label = selectedMcVersion[0] ?: "Any version"
                    "$label  v"
                })
                button.onMousePressed { b ->
                    if (b == 0) {
                        popup.setEnabled(!popup.isEnabled)
                        val panel = versionsList.panel
                        if (panel != null && panel.isValid) {
                            com.cleanroommc.modularui.widget.WidgetTree.resizeInternal(panel.resizer(), false)
                        }
                        true
                    } else false
                }
                val options = mutableListOf<Pair<String?, String>>()
                options.add(null to "Any version")
                mcVersions.forEach { options.add(it to it) }
                for ((id, name) in options) {
                    val opt = SimpleButton().widthRel(1f).height(12)
                    opt.overlay(IKey.dynamic {
                        if (selectedMcVersion[0] == id) "§f§l$name§r" else "§7$name§r"
                    })
                    opt.onMousePressed { b ->
                        if (b == 0) {
                            selectedMcVersion[0] = id
                            popup.setEnabled(false)
                            val panel = versionsList.panel
                            if (panel != null && panel.isValid) {
                                com.cleanroommc.modularui.widget.WidgetTree.resizeInternal(panel.resizer(), false)
                            }
                            renderVersions()
                            true
                        } else false
                    }
                    popup.child(opt)
                }
                versionFilterHolder.child(
                    TextWidget(IKey.str("§7Minecraft version§r")).color(accent)
                        .top(0).left(0).widthRel(1f).height(10)
                )
                versionFilterHolder.child(button)
                versionFilterHolder.child(popup)
                renderVersions()
            }
        }
    }

    fun loadDescription() {
        project.getDescription().thenAccept { rawMd ->
            Minecraft.getMinecraft().func_152344_a {
                descriptionList.removeAll()
                if (rawMd.isBlank()) {
                    descriptionList.child(TextWidget(IKey.str("(no description)")).color(accent))
                    return@func_152344_a
                }
                MarkdownRenderer.render(rawMd, descTextW).forEach { descriptionList.child(it) }
            }
        }
    }

    loadVersions()
    loadDescription()

    val iconSize = 40
    val projectIcon = AsyncIcon(project.getIconUrl(), iconSize).top(GUTTER).left(GUTTER)
    val textLeft = GUTTER + iconSize + 8
    val header = TextWidget(IKey.str(project.getName()).style(EnumChatFormatting.BOLD).scale(1.5f))
        .top(GUTTER).left(textLeft)
    val authorLine = TextWidget(
        IKey.str("by ${project.getAuthor()}  §8•§r  ${project.getDownloads().formatCompact()} downloads")
            .style(EnumChatFormatting.GRAY)
    ).top(GUTTER + 16).left(textLeft)
    // Long summaries should scroll rather than overflow the header strip.
    // Wrap in a SimpleList so vertical scroll kicks in when content > 28px.
    val summaryWidth = (descListW - iconSize - 8).coerceAtLeast(20)
    val summaryList = SimpleList()
        .top(GUTTER + 28).left(textLeft).width(summaryWidth).height(28)
        .child(
            TextWidget(IKey.str(project.getSummary()))
                .widthRel(1f)
                .color(accent)
                .alignment(com.cleanroommc.modularui.utils.Alignment.TopLeft)
        )

    val bodyTop = GUTTER + 64
    descriptionList
        .top(bodyTop).left(GUTTER).width(descListW).bottom(GUTTER)
        .background(Rectangle().color(descBackground))
        .padding(DESC_PAD, DESC_PAD, DESC_PAD, DESC_PAD)

    val galleryMessage = arrayOf<String?>(null)
    val galleryStatusHolder = arrayOf<IWidget?>(null)
    fun setGalleryMessage(message: String?) {
        galleryMessage[0] = message
        galleryStatusHolder[0]?.setEnabled(message != null)
    }

    lateinit var galleryOverlay: Container
    lateinit var galleryImage: GalleryImageWidget
    var galleryLoading = false
    val galleryImages = arrayOf<List<IGalleryImage>?>(null)
    val galleryIndex = intArrayOf(0)

    fun showGalleryImage(index: Int) {
        val images = galleryImages[0].orEmpty()
        if (images.isEmpty()) {
            galleryImage.show(null)
            setGalleryMessage("No gallery images")
            return
        }
        galleryIndex[0] = (index + images.size) % images.size
        galleryImage.show(images[galleryIndex[0]].url.toURL())
    }

    fun loadGalleryImages() {
        if (galleryLoading) return
        galleryLoading = true
        setGalleryMessage("Loading gallery...")
        project.getGalleryImages().whenComplete { images, error ->
            Minecraft.getMinecraft().func_152344_a {
                galleryLoading = false
                if (!galleryOverlay.isValid() || !galleryOverlay.isEnabled) return@func_152344_a
                if (error != null || images == null) {
                    VintageResourcify.LOG.warn("Failed to load gallery for {}", project.getId(), error)
                    galleryImages[0] = emptyList()
                    setGalleryMessage("Failed to load gallery")
                    return@func_152344_a
                }
                galleryImages[0] = images
                showGalleryImage(0)
            }
        }
    }

    fun openGallery() {
        galleryOverlay.setEnabled(true)
        val images = galleryImages[0]
        if (images != null) {
            showGalleryImage(galleryIndex[0])
        } else if (project.hasGallery()) {
            loadGalleryImages()
        } else {
            galleryImages[0] = emptyList()
            showGalleryImage(0)
        }
    }

    fun moveGallery(delta: Int): Boolean {
        val images = galleryImages[0].orEmpty()
        if (images.isEmpty()) return true
        showGalleryImage(galleryIndex[0] + delta)
        return true
    }

    val galleryImageLeft = (GALLERY_OVERLAY_MARGIN + GALLERY_ARROW_SIZE + GALLERY_ARROW_GAP)
        .coerceAtMost(sr0.scaledWidth / 4)
    val galleryImageTop = GALLERY_OVERLAY_MARGIN.coerceAtMost(sr0.scaledHeight / 4)
    val galleryImageW = (sr0.scaledWidth - 2 * galleryImageLeft).coerceAtLeast(32)
    val galleryImageH = (sr0.scaledHeight - 2 * galleryImageTop).coerceAtLeast(32)

    galleryOverlay = Container()
        .top(0).left(0).width(sr0.scaledWidth).height(sr0.scaledHeight)
    val galleryBackdrop = SimpleButton()
        .top(0).left(0).width(sr0.scaledWidth).height(sr0.scaledHeight)
        .background(Rectangle().color(0xAA000000.toInt()))
        .disableHoverThemeBackground(true)
        .playClickSound(false)
        .onKeyPressed { _, keyCode ->
            if (keyCode == Keyboard.KEY_ESCAPE) {
                galleryOverlay.setEnabled(false)
                true
            } else false
        }
        .onMousePressed { btn ->
            if (btn == 0 || btn == 1) {
                galleryOverlay.setEnabled(false)
                true
            } else false
        }
    galleryImage = GalleryImageWidget(::setGalleryMessage)
        .top(galleryImageTop).left(galleryImageLeft)
        .width(galleryImageW).height(galleryImageH)
    val galleryStatus = TextWidget(IKey.dynamic { galleryMessage[0] ?: "" })
        .top(sr0.scaledHeight / 2 - 5).left(0)
        .width(sr0.scaledWidth).height(10)
        .color(0xFFFFFFFF.toInt())
        .alignment(com.cleanroommc.modularui.utils.Alignment.Center)
    galleryStatus.setEnabled(false)
    galleryStatusHolder[0] = galleryStatus
    val galleryLeft = SimpleButton()
        .top(sr0.scaledHeight / 2 - GALLERY_ARROW_SIZE / 2)
        .left(GALLERY_OVERLAY_MARGIN)
        .size(GALLERY_ARROW_SIZE, GALLERY_ARROW_SIZE)
        .disableThemeBackground(true)
        .disableHoverThemeBackground(true)
        .overlay(ResourcePackArrowDrawable(ResourcePackArrowDrawable.Direction.LEFT, false))
        .hoverOverlay(ResourcePackArrowDrawable(ResourcePackArrowDrawable.Direction.LEFT, true))
        .onMousePressed { btn -> if (btn == 0) moveGallery(-1) else false }
    galleryLeft.tooltip().addLine("Previous image")
    val galleryRight = SimpleButton()
        .top(sr0.scaledHeight / 2 - GALLERY_ARROW_SIZE / 2)
        .left(sr0.scaledWidth - GALLERY_OVERLAY_MARGIN - GALLERY_ARROW_SIZE)
        .size(GALLERY_ARROW_SIZE, GALLERY_ARROW_SIZE)
        .disableThemeBackground(true)
        .disableHoverThemeBackground(true)
        .overlay(ResourcePackArrowDrawable(ResourcePackArrowDrawable.Direction.RIGHT, false))
        .hoverOverlay(ResourcePackArrowDrawable(ResourcePackArrowDrawable.Direction.RIGHT, true))
        .onMousePressed { btn -> if (btn == 0) moveGallery(1) else false }
    galleryRight.tooltip().addLine("Next image")
    galleryOverlay
        .child(galleryBackdrop)
        .child(galleryImage)
        .child(galleryStatus)
        .child(galleryLeft)
        .child(galleryRight)
    galleryOverlay.setEnabled(false)

    val galleryButton = SimpleButton()
        .top(GUTTER + 16)
        .left(GUTTER + descListW + GALLERY_BUTTON_GAP + GALLERY_BUTTON_RIGHT_NUDGE)
        .size(GALLERY_BUTTON_SIZE, GALLERY_BUTTON_SIZE)
        .overlay(CenteredItemDrawable(ItemStack(Items.painting), 20, xOffset = -0.5f))
        .onMousePressed { btn ->
            if (btn == 0) {
                openGallery()
                true
            } else false
        }
    galleryButton.tooltip().addLine("View Gallery")

    val versionsHeader = TextWidget(IKey.str("Versions").style(EnumChatFormatting.BOLD))
        .top(bodyTop).left(verColLeft).width(verColW).height(14)
        .alignment(com.cleanroommc.modularui.utils.Alignment.CenterLeft)
    // The dropdown's open popup paints from inside the holder; reserve a
    // 32px-tall slot (label + button) before the version list, then push the
    // list down. Popup floats over the list via z-order (last child below).
    versionFilterHolder
        .top(bodyTop + 18).left(verColLeft).width(verColW).height(28)
    versionsList
        .top(bodyTop + 50).left(verColLeft).width(verColW).bottom(GUTTER)

    VintageResourcify.LOG.info(
        "ProjectScreen lambda exit: building panel projectIcon={}",
        Integer.toHexString(System.identityHashCode(projectIcon)),
    )
    ModularPanel.defaultPanel("vintage-resourcify-project")
        .full()
        .child(projectIcon)
        .child(header)
        .child(authorLine)
        .child(summaryList)
        .child(descriptionList)
        .child(galleryButton)
        .child(versionsHeader)
        .child(versionsList)
        // Last child so the dropdown popup paints over versionsList rather
        // than being clipped behind it.
        .child(versionFilterHolder)
        .child(galleryOverlay)
})

private fun install(
    version: IVersion,
    project: IProject,
    type: ProjectType,
    packsFolder: File,
    sourceParent: GuiScreen?,
    platformId: String,
) {
    val url = version.getDownloadUrl() ?: run {
        VintageResourcify.LOG.warn("No download URL for version {}", version.getName())
        return
    }
    if (!packsFolder.exists()) packsFolder.mkdirs()
    val target = File(packsFolder, version.getFileName())
    VintageResourcify.LOG.info("Installing {} -> {}", url, target)
    val isShader = type == ProjectType.IRIS_SHADER || type == ProjectType.OPTIFINE_SHADER
    if (!isShader && target.exists()) Platform.closeResourcePack(target)
    DownloadManager.download(target, version.getSha1(), url, false) {
        VintageResourcify.LOG.info("Install complete: {}", target.name)
        // Record into the per-folder install index so update + source-icon
        // overlays know which platform/project this file came from.
        try {
            dev.dediamondpro.resourcify.util.LocalIndex
                .forFolder(packsFolder)
                .record(target, platformId, project.getId())
        } catch (e: Throwable) {
            VintageResourcify.LOG.warn("Failed to record install index entry", e)
        }
        Minecraft.getMinecraft().func_152344_a {
            if (isShader) {
                // Shaders are picked up on next ShaderPackScreen open; no
                // ResourcePackRepository handle to refresh. Just return the
                // user to Iris's screen so they can apply the new pack.
                if (!IrisHelper.openShaderPackScreen(sourceParent) && sourceParent != null) {
                    Minecraft.getMinecraft().displayGuiScreen(sourceParent)
                }
            } else {
                Platform.reloadResourcePack(target)
                Platform.reloadResources()
                if (sourceParent != null) {
                    Minecraft.getMinecraft().displayGuiScreen(GuiScreenResourcePacks(sourceParent))
                }
            }
        }
    }
}
