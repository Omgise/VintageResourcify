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
import com.cleanroommc.modularui.api.widget.IWidget
import com.cleanroommc.modularui.widgets.ButtonWidget
import com.cleanroommc.modularui.widgets.ListWidget
import com.cleanroommc.modularui.widgets.TextWidget
import com.cleanroommc.modularui.widgets.layout.Flow
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget
import com.cleanroommc.modularui.factory.ClientGUI
import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.gui.projectpage.ProjectScreen
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.IProject
import dev.dediamondpro.resourcify.services.ProjectType
import dev.dediamondpro.resourcify.services.ServiceRegistry
import dev.dediamondpro.resourcify.util.AsyncIcon
import dev.dediamondpro.resourcify.util.MultiThreading
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import java.io.File

// Concrete subclasses so the F-bounded self-types (W extends Self<W>) of
// MUI2's fluent builders are satisfied. Self<Nothing>() / Self<*>() don't
// compile in Kotlin.
private class SimpleButton : ButtonWidget<SimpleButton>()
private class SimpleList : ListWidget<IWidget, SimpleList>()

// Entire screen state lives in this lambda's closure. See commit message of
// 8a9f9e5 for why instance fields don't work with MUI2's super-init order.
class BrowseScreen(
    type: ProjectType,
    packsFolder: File,
    sourceParent: GuiScreen?,
) : ModularScreen(VintageResourcify.MODID, { _ ->
    val service = ServiceRegistry.getDefaultService(type)
    val defaultSortKey = service.getSortOptions().keys.firstOrNull() ?: ""

    // Lateinit so the EnterSubmitField below can call runSearch().
    var triggerSearch: (() -> Unit)? = null
    val searchBox = object : TextFieldWidget() {
        override fun onKeyPressed(character: Char, keyCode: Int): com.cleanroommc.modularui.api.widget.Interactable.Result {
            if (isFocused && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
                triggerSearch?.invoke()
                return com.cleanroommc.modularui.api.widget.Interactable.Result.SUCCESS
            }
            return super.onKeyPressed(character, keyCode)
        }
    }.size(180, 14)
    val resultsList = SimpleList()

    fun runSearch() {
        resultsList.removeAll()
        resultsList.child(TextWidget(IKey.str("Searching...")))
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
                resultsList.removeAll()
                val projects: List<IProject> = result?.projects ?: emptyList()
                if (projects.isEmpty()) {
                    resultsList.child(TextWidget(IKey.str("No results")))
                    return@func_152344_a
                }
                projects.take(50).forEach { project ->
                    val iconSize = 28
                    val row = Flow.row()
                        .widthRel(1f).height(32).margin(0, 0, 0, 4)
                        .child(
                            AsyncIcon(project.getIconUrl(), iconSize)
                                .left(4).top(2)
                        )
                        .child(
                            SimpleButton()
                                .left(iconSize + 10).top(2).right(4).height(28)
                                .overlay(IKey.str("${project.getName()}  -  by ${project.getAuthor()}"))
                                .onMousePressed { btn ->
                                    if (btn == 0) {
                                        ClientGUI.open(ProjectScreen(project, packsFolder, sourceParent))
                                        true
                                    } else false
                                }
                        )
                    resultsList.child(row)
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

    triggerSearch = ::runSearch
    resultsList.top(28).left(8).right(8).bottom(8)
        .child(TextWidget(IKey.str("Loading...")))
    runSearch()

    ModularPanel.defaultPanel("vintage-resourcify-browse")
        .full()
        .child(topRow)
        .child(resultsList)
})
