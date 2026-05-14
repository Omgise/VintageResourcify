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
import com.cleanroommc.modularui.screen.ModularPanel
import com.cleanroommc.modularui.screen.ModularScreen
import com.cleanroommc.modularui.widgets.ButtonWidget
import com.cleanroommc.modularui.widgets.TextWidget
import com.cleanroommc.modularui.widgets.layout.Flow
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget
import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.IProject
import dev.dediamondpro.resourcify.services.ProjectType
import dev.dediamondpro.resourcify.services.ServiceRegistry
import dev.dediamondpro.resourcify.util.MultiThreading
import net.minecraft.client.Minecraft

// Concrete ButtonWidget so the F-bounded self-type W extends ButtonWidget<W>
// is satisfied. ButtonWidget<Nothing>() / ButtonWidget<*>() don't compile.
private class SimpleButton : ButtonWidget<SimpleButton>()

// Entire screen state lives in this lambda's closure. See commit message of
// 8a9f9e5 for why instance fields don't work with MUI2's super-init order.
class BrowseScreen(type: ProjectType) : ModularScreen(VintageResourcify.MODID, { _ ->
    val service = ServiceRegistry.getDefaultService(type)
    val defaultSortKey = service.getSortOptions().keys.firstOrNull() ?: ""

    val searchBox = TextFieldWidget().size(180, 14)
    val resultsColumn: Flow = Flow.column()

    fun runSearch() {
        resultsColumn.removeAll()
        resultsColumn.child(TextWidget(IKey.str("Searching...")))
        val query = searchBox.text ?: ""
        MultiThreading.supplyAsync {
            try {
                service.search(query, defaultSortKey, listOf(Platform.getMcVersion()), emptyList(), 0, type)
            } catch (e: Exception) {
                VintageResourcify.LOG.warn("Search failed", e)
                null
            }
        }.thenAccept { result ->
            Minecraft.getMinecraft().func_152344_a {
                resultsColumn.removeAll()
                val projects: List<IProject> = result?.projects ?: emptyList()
                if (projects.isEmpty()) {
                    resultsColumn.child(TextWidget(IKey.str("No results")))
                    return@func_152344_a
                }
                projects.take(20).forEach { project ->
                    resultsColumn.child(
                        SimpleButton()
                            .height(14)
                            .widthRel(1f)
                            .overlay(IKey.str("- ${project.getName()} by ${project.getAuthor()}"))
                            .onMousePressed { btn ->
                                if (btn == 0) {
                                    VintageResourcify.LOG.info(
                                        "Clicked project {} (ProjectScreen pending in next slice)",
                                        project.getId()
                                    )
                                    true
                                } else false
                            }
                    )
                }
            }
        }
    }

    val topRow = Flow.row().top(6).left(8).height(14)
        .child(searchBox)
        .child(
            SimpleButton()
                .size(40, 14)
                .overlay(IKey.str("Search"))
                .onMousePressed { btn -> if (btn == 0) { runSearch(); true } else false }
        )

    resultsColumn.top(28).left(8).right(8).bottom(8)
        .child(TextWidget(IKey.str("Loading...")))
    runSearch()

    ModularPanel.defaultPanel("vintage-resourcify-browse", 320, 220)
        .child(topRow)
        .child(resultsColumn)
})
