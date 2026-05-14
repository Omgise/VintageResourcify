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

package dev.dediamondpro.resourcify.gui.browsepage

import com.cleanroommc.modularui.api.drawable.IKey
import com.cleanroommc.modularui.screen.CustomModularScreen
import com.cleanroommc.modularui.screen.ModularPanel
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext
import com.cleanroommc.modularui.widgets.TextWidget
import com.cleanroommc.modularui.widgets.layout.Flow
import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.IProject
import dev.dediamondpro.resourcify.services.IService
import dev.dediamondpro.resourcify.services.ProjectType
import dev.dediamondpro.resourcify.services.ServiceRegistry
import dev.dediamondpro.resourcify.util.MultiThreading
import net.minecraft.client.Minecraft

class BrowseScreen(private val type: ProjectType) : CustomModularScreen(VintageResourcify.MODID) {

    // MUI2's super constructor invokes buildUI() before our property
    // initializers run, so service + resultsColumn have to be lateinit and
    // set inside buildUI itself.
    private lateinit var service: IService
    private lateinit var resultsColumn: Flow

    override fun buildUI(context: ModularGuiContext): ModularPanel {
        service = ServiceRegistry.getDefaultService(type)
        resultsColumn = Flow.column().top(20).left(8).right(8).bottom(8)
        val panel = ModularPanel.defaultPanel("vintage-resourcify-browse", 320, 220)
            .child(TextWidget(IKey.str("Resourcify $type browser")).top(6).left(8))
            .child(resultsColumn.child(TextWidget(IKey.str("Loading..."))))
        runSearch()
        return panel
    }

    private fun runSearch() {
        val defaultSortKey = service.getSortOptions().keys.firstOrNull() ?: ""
        MultiThreading.supplyAsync {
            try {
                service.search("", defaultSortKey, listOf(Platform.getMcVersion()), emptyList(), 0, type)
            } catch (e: Exception) {
                VintageResourcify.LOG.warn("Search failed", e)
                null
            }
        }.thenAccept { result ->
            Minecraft.getMinecraft().func_152344_a {
                applyResults(result?.projects ?: emptyList())
            }
        }
    }

    private fun applyResults(projects: List<IProject>) {
        resultsColumn.removeAll()
        if (projects.isEmpty()) {
            resultsColumn.child(TextWidget(IKey.str("No results")))
            return
        }
        projects.take(20).forEach { project ->
            resultsColumn.child(
                TextWidget(IKey.str("- ${project.getName()} by ${project.getAuthor()}"))
            )
        }
    }
}
