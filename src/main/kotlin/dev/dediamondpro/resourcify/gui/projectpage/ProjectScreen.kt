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
import com.cleanroommc.modularui.screen.ModularPanel
import com.cleanroommc.modularui.screen.ModularScreen
import com.cleanroommc.modularui.widgets.ButtonWidget
import com.cleanroommc.modularui.widgets.ListWidget
import com.cleanroommc.modularui.widgets.TextWidget
import com.cleanroommc.modularui.widgets.layout.Flow
import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.IProject
import dev.dediamondpro.resourcify.services.IVersion
import dev.dediamondpro.resourcify.util.DownloadManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiScreenResourcePacks
import java.io.File

private class SimpleButton : ButtonWidget<SimpleButton>()
private class SimpleList : ListWidget<IWidget, SimpleList>()

// See BrowseScreen's commit 8a9f9e5 for why all state lives in the lambda
// closure rather than in instance fields.
class ProjectScreen(
    project: IProject,
    packsFolder: File,
    sourceParent: GuiScreen?,
) : ModularScreen(VintageResourcify.MODID, { _ ->
    val versionsList = SimpleList()

    fun loadVersions() {
        versionsList.removeAll()
        versionsList.child(TextWidget(IKey.str("Loading versions...")))
        project.getVersions().thenAccept { versions ->
            Minecraft.getMinecraft().func_152344_a {
                versionsList.removeAll()
                val mcVersion = Platform.getMcVersion()
                val matching = versions.filter { it.getMinecraftVersions().contains(mcVersion) }
                if (matching.isEmpty()) {
                    versionsList.child(TextWidget(IKey.str("No $mcVersion versions available")))
                    return@func_152344_a
                }
                matching.forEach { version: IVersion ->
                    val row = Flow.row()
                        .height(20).widthRel(1f).margin(0, 0, 0, 2)
                        .child(
                            TextWidget(IKey.str(version.getName()))
                                .heightRel(1f).left(4).widthRel(0.75f)
                        )
                        .child(
                            SimpleButton()
                                .size(48, 16).right(2).top(2)
                                .overlay(IKey.str("Install"))
                                .onMousePressed { btn ->
                                    if (btn == 0) {
                                        install(version, packsFolder, sourceParent)
                                        true
                                    } else false
                                }
                        )
                    versionsList.child(row)
                }
            }
        }
    }

    val header = TextWidget(IKey.str("${project.getName()} by ${project.getAuthor()}"))
        .top(6).left(8)
    val summary = TextWidget(IKey.str(project.getSummary()))
        .top(20).left(8).right(8)
    val descriptionList = SimpleList()
    descriptionList.top(36).left(8).right(8).height(60)
        .child(TextWidget(IKey.str("Loading description...")))

    // Markdown rendering was dropped with MineMark; we strip out formatting
    // and show the first chunk of the body in a scrollable list of lines.
    fun loadDescription() {
        project.getDescription().thenAccept { rawMd ->
            Minecraft.getMinecraft().func_152344_a {
                descriptionList.removeAll()
                val plain = rawMd
                    .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
                    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                    .replace(Regex("[`*_>#~]"), "")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim()
                if (plain.isEmpty()) {
                    descriptionList.child(TextWidget(IKey.str("(no description)")))
                    return@func_152344_a
                }
                plain.lineSequence().take(40).forEach { line ->
                    descriptionList.child(TextWidget(IKey.str(line)).widthRel(1f))
                }
            }
        }
    }

    versionsList.top(100).left(8).right(8).bottom(8)
    loadVersions()
    loadDescription()

    ModularPanel.defaultPanel("vintage-resourcify-project", 320, 220)
        .child(header)
        .child(summary)
        .child(descriptionList)
        .child(versionsList)
})

private fun install(version: IVersion, packsFolder: File, sourceParent: GuiScreen?) {
    val url = version.getDownloadUrl() ?: run {
        VintageResourcify.LOG.warn("No download URL for version {}", version.getName())
        return
    }
    if (!packsFolder.exists()) packsFolder.mkdirs()
    val target = File(packsFolder, version.getFileName())
    VintageResourcify.LOG.info("Installing {} -> {}", url, target)
    // Release any existing zip handle for this filename so the download can
    // overwrite (matters on Windows) and so MC drops the cached IResourcePack.
    if (target.exists()) Platform.closeResourcePack(target)
    DownloadManager.download(target, version.getSha1(), url, false) {
        VintageResourcify.LOG.info("Install complete: {}", target.name)
        // DownloadManager fires the callback off the main thread; bounce to
        // it before touching Minecraft's resource manager.
        Minecraft.getMinecraft().func_152344_a {
            Platform.reloadResourcePack(target)
            Platform.reloadResources()
            // Pop back to a freshly-initialized resource pack screen so the
            // user sees the new pack in the available list without manually
            // re-navigating Options -> Resource Packs.
            if (sourceParent != null) {
                Minecraft.getMinecraft().displayGuiScreen(GuiScreenResourcePacks(sourceParent))
            }
        }
    }
}
