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
import com.cleanroommc.modularui.api.widget.IWidget
import com.cleanroommc.modularui.drawable.Rectangle
import com.cleanroommc.modularui.factory.ClientGUI
import com.cleanroommc.modularui.screen.ModularPanel
import com.cleanroommc.modularui.screen.ModularScreen
import com.cleanroommc.modularui.widgets.ButtonWidget
import com.cleanroommc.modularui.widgets.ListWidget
import com.cleanroommc.modularui.widgets.TextWidget
import com.cleanroommc.modularui.widgets.layout.Flow
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget
import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.config.Config
import dev.dediamondpro.resourcify.gui.projectpage.ProjectScreen
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.IProject
import dev.dediamondpro.resourcify.services.ProjectType
import dev.dediamondpro.resourcify.services.ServiceRegistry
import dev.dediamondpro.resourcify.util.AsyncIcon
import dev.dediamondpro.resourcify.util.MultiThreading
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraft.util.EnumChatFormatting
import org.lwjgl.input.Keyboard
import java.io.File

// F-bounded self-types in Kotlin need a concrete subclass.
private class SimpleButton : ButtonWidget<SimpleButton>()
private class SimpleList : ListWidget<IWidget, SimpleList>()

// Card layout constants. Sized so a 1080p window shows 6-7 cards above the
// fold and small windows still render readable text.
private const val CARD_HEIGHT = 52
private const val CARD_GAP = 4
private const val ICON_SIZE = 44
private const val INNER_PAD = 6

class BrowseScreen(
    type: ProjectType,
    packsFolder: File,
    sourceParent: GuiScreen?,
) : ModularScreen(VintageResourcify.MODID, { _ ->
    val service = ServiceRegistry.getDefaultService(type)
    val defaultSortKey = service.getSortOptions().keys.firstOrNull() ?: ""

    val isLight = Config.instance.markdownTheme.equals("light", ignoreCase = true)
    val cardBg = if (isLight) 0x14000000 else 0x28FFFFFF
    val textPrimary = if (isLight) 0xFF1F2328.toInt() else 0xFFF0F6FC.toInt()
    val textSecondary = if (isLight) 0xFF59636E.toInt() else 0xFF9198A1.toInt()

    var triggerSearch: (() -> Unit)? = null
    val searchBox = object : TextFieldWidget() {
        override fun onKeyPressed(character: Char, keyCode: Int): com.cleanroommc.modularui.api.widget.Interactable.Result {
            if (isFocused && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
                triggerSearch?.invoke()
                return com.cleanroommc.modularui.api.widget.Interactable.Result.SUCCESS
            }
            return super.onKeyPressed(character, keyCode)
        }
    }
    val resultsList = SimpleList()

    fun runSearch() {
        resultsList.removeAll()
        resultsList.child(TextWidget(IKey.str("Searching...")).color(textSecondary))
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
                    resultsList.child(TextWidget(IKey.str("No results")).color(textSecondary))
                    return@func_152344_a
                }
                projects.take(50).forEach { project ->
                    resultsList.child(buildCard(project, packsFolder, sourceParent, cardBg, textPrimary, textSecondary))
                }
            }
        }
    }

    val topRow = Flow.row().top(10).left(10).right(10).height(16)
        .child(searchBox.heightRel(1f).widthRel(1f).margin(0, 60, 0, 0))
        .child(
            SimpleButton()
                .size(54, 16).right(0)
                .overlay(IKey.str("Search"))
                .onMousePressed { btn -> if (btn == 0) { runSearch(); true } else false }
        )

    triggerSearch = ::runSearch
    resultsList.top(36).left(10).right(10).bottom(10)
        .child(TextWidget(IKey.str("Loading...")).color(textSecondary))
    runSearch()

    ModularPanel.defaultPanel("vintage-resourcify-browse")
        .full()
        .child(topRow)
        .child(resultsList)
})

/** Project result card: thumbnail + title + author + summary, clickable. */
private fun buildCard(
    project: IProject,
    packsFolder: File,
    sourceParent: GuiScreen?,
    cardBg: Int,
    textPrimary: Int,
    textSecondary: Int,
): IWidget {
    val click = { btn: Int ->
        if (btn == 0) {
            ClientGUI.open(ProjectScreen(project, packsFolder, sourceParent))
            true
        } else false
    }
    val summary = project.getSummary().take(160)
    // SimpleButton is a SingleChildWidget; multiple .child() calls replace
    // each other. Wrap card content in a Flow.row so we can hold an icon
    // alongside a vertically-stacked text column.
    val textColumn = Flow.column()
        .left(ICON_SIZE + INNER_PAD).top(0).right(0).heightRel(1f)
        .child(
            TextWidget(IKey.str(project.getName()).style(EnumChatFormatting.BOLD))
                .widthRel(1f).height(10)
                .color(textPrimary)
                .alignment(com.cleanroommc.modularui.utils.Alignment.CenterLeft)
        )
        .child(
            TextWidget(IKey.str("by ${project.getAuthor()}"))
                .widthRel(1f).height(10)
                .color(textSecondary)
                .alignment(com.cleanroommc.modularui.utils.Alignment.CenterLeft)
        )
        .child(
            TextWidget(IKey.str(summary))
                .widthRel(1f).heightRel(1f)
                .color(textPrimary)
                .alignment(com.cleanroommc.modularui.utils.Alignment.TopLeft)
        )

    val content = Flow.row()
        .widthRel(1f).heightRel(1f).padding(INNER_PAD, INNER_PAD, INNER_PAD, INNER_PAD)
        .child(
            AsyncIcon(project.getIconUrl(), ICON_SIZE)
                .top(0).left(0)
        )
        .child(textColumn)

    return SimpleButton()
        .widthRel(1f).height(CARD_HEIGHT).margin(0, 0, 0, CARD_GAP)
        .background(Rectangle().color(cardBg))
        .overlay()
        .child(content)
        .onMousePressed(click)
}
