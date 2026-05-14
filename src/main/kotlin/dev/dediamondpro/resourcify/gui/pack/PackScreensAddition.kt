/*
 * This file is part of Resourcify
 * Copyright (C) 2023 DeDiamondPro
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

import com.cleanroommc.modularui.factory.ClientGUI
import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.gui.browsepage.BrowseScreen
import dev.dediamondpro.resourcify.services.ProjectType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import java.io.File

object PackScreensAddition {

    private const val BUTTON_SIZE = 20
    private const val ICON_SIZE = 16
    private val PLUS_TEXTURE = ResourceLocation(VintageResourcify.MODID, "plus.png")

    fun onRender(type: ProjectType) {
        val (x, y) = buttonOrigin() ?: return
        // Subtle filled background so the button shows up against the gradient.
        Gui.drawRect(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE, 0x66000000.toInt())
        val mc = Minecraft.getMinecraft()
        mc.textureManager.bindTexture(PLUS_TEXTURE)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        val iconX = x + (BUTTON_SIZE - ICON_SIZE) / 2
        val iconY = y + (BUTTON_SIZE - ICON_SIZE) / 2
        // drawScaledCustomSizeModalRect: x, y, u, v, uWidth, vHeight, drawWidth, drawHeight, texW, texH
        Gui.func_152125_a(
            iconX, iconY, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE.toFloat(), ICON_SIZE.toFloat()
        )
    }

    fun onMouseClick(mouseX: Int, mouseY: Int, button: Int, type: ProjectType, folder: File) {
        if (button != 0) return
        val (x, y) = buttonOrigin() ?: return
        if (mouseX !in x..(x + BUTTON_SIZE)) return
        if (mouseY !in y..(y + BUTTON_SIZE)) return
        ClientGUI.open(BrowseScreen(type, folder))
    }

    private fun buttonOrigin(): Pair<Int, Int>? {
        val screen = Minecraft.getMinecraft().currentScreen ?: return null
        return (screen.width - BUTTON_SIZE - 4) to 4
    }
}
