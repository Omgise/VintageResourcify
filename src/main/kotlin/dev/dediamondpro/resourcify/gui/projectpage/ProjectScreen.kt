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
import com.cleanroommc.modularui.api.widget.IWidget
import com.cleanroommc.modularui.drawable.Rectangle
import com.cleanroommc.modularui.screen.ModularPanel
import com.cleanroommc.modularui.screen.ModularScreen
import com.cleanroommc.modularui.widgets.ButtonWidget
import com.cleanroommc.modularui.widgets.ListWidget
import com.cleanroommc.modularui.widgets.TextWidget
import com.cleanroommc.modularui.widgets.layout.Flow
import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.config.Config
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.IProject
import dev.dediamondpro.resourcify.services.IVersion
import dev.dediamondpro.resourcify.services.ProjectType
import dev.dediamondpro.resourcify.util.AsyncIcon
import dev.dediamondpro.resourcify.util.formatCompact
import dev.dediamondpro.resourcify.util.DownloadManager
import dev.dediamondpro.resourcify.util.IrisHelper
import dev.dediamondpro.resourcify.util.MarkdownRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiScreenResourcePacks
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.util.EnumChatFormatting
import java.io.File

private class SimpleButton : ButtonWidget<SimpleButton>()
private class SimpleList : ListWidget<IWidget, SimpleList>()

// Layout constants live here so the column edges line up across the header,
// summary, body. Values are in GUI-scaled pixels.
private const val GUTTER = 12
private const val COL_GAP = 12
private const val DESC_REL_WIDTH = 0.62f
private const val VER_REL_WIDTH = 1f - DESC_REL_WIDTH
private const val DESC_PAD = 8

// See BrowseScreen's commit 8a9f9e5 for why all state lives in the lambda
// closure rather than in instance fields.
class ProjectScreen(
    project: IProject,
    type: ProjectType,
    packsFolder: File,
    sourceParent: GuiScreen?,
    platformId: String,
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
    val descTextW = descColW - DESC_PAD * 2 - 8 // 8 = scrollbar inset

    fun loadVersions() {
        project.getVersions().thenAccept { versions ->
            Minecraft.getMinecraft().func_152344_a {
                versionsList.removeAll()
                val mcVersion = Platform.getMcVersion()
                val matching = versions.filter { it.getMinecraftVersions().contains(mcVersion) }
                if (matching.isEmpty()) {
                    versionsList.child(TextWidget(IKey.str("No $mcVersion versions available")))
                    return@func_152344_a
                }
                // Carve out exact pixel widths so the name doesn't run into
                // the Install button. Truncate names that still don't fit so
                // each row stays a single line.
                val buttonW = 56
                val buttonRightInset = 6
                val nameLeft = 8
                // 12px breathing room between the name's right edge and the
                // Install button's left edge so it doesn't read as squished.
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
    val summaryWidth = descColW - iconSize - 8
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
        .top(bodyTop).left(GUTTER).width(descColW).bottom(GUTTER)
        .background(Rectangle().color(descBackground))
        .padding(DESC_PAD, DESC_PAD, DESC_PAD, DESC_PAD)

    val versionsHeader = TextWidget(IKey.str("Versions").style(EnumChatFormatting.BOLD))
        .top(bodyTop).left(verColLeft).width(verColW).height(14)
        .alignment(com.cleanroommc.modularui.utils.Alignment.CenterLeft)
    versionsList
        .top(bodyTop + 18).left(verColLeft).width(verColW).bottom(GUTTER)

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
        .child(versionsHeader)
        .child(versionsList)
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
