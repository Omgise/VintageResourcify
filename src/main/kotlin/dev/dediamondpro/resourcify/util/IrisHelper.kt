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

import dev.dediamondpro.resourcify.VintageResourcify
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import java.io.File
import java.nio.file.Path

/**
 * Soft-dependency reflection bridge to Angelica's Iris shader support. Angelica
 * is a runtime-only dep so we can't reference its classes directly; instead we
 * resolve them lazily once and cache the lookups.
 */
object IrisHelper {

    private val irisClass: Class<*>? by lazy {
        try {
            Class.forName("net.coderbot.iris.Iris")
        } catch (_: Throwable) {
            null
        }
    }

    private val shaderScreenClass: Class<out GuiScreen>? by lazy {
        try {
            @Suppress("UNCHECKED_CAST")
            Class.forName("net.coderbot.iris.gui.screen.ShaderPackScreen") as Class<out GuiScreen>
        } catch (_: Throwable) {
            null
        }
    }

    fun isPresent(): Boolean = irisClass != null

    /**
     * Call `Iris.reload()` reflectively. Returns false on failure so the
     * caller can fall back to just re-displaying the screen.
     */
    fun reload(): Boolean {
        val cls = irisClass ?: return false
        return try {
            cls.getDeclaredMethod("reload").invoke(null)
            true
        } catch (e: Throwable) {
            VintageResourcify.LOG.warn("Iris.reload reflective call failed", e)
            false
        }
    }

    fun enableShaderPack(file: File): Boolean {
        val cls = irisClass ?: return false
        return try {
            val config = cls.getDeclaredMethod("getIrisConfig").invoke(null)
            config.javaClass.getDeclaredMethod("setShaderPackName", String::class.java).invoke(config, file.name)
            config.javaClass.getDeclaredMethod("setShadersEnabled", Boolean::class.javaPrimitiveType)
                .invoke(config, true)
            config.javaClass.getDeclaredMethod("save").invoke(config)
            cls.getDeclaredMethod("reload").invoke(null)
            true
        } catch (e: Throwable) {
            VintageResourcify.LOG.warn("Failed to enable shader pack {}", file.name, e)
            false
        }
    }

    /** Reflect Iris ShaderPackScreen's private `parent` field so we can return there after install. */
    fun getShaderScreenParent(screen: GuiScreen): GuiScreen? {
        val cls = shaderScreenClass ?: return null
        if (!cls.isInstance(screen)) return null
        return try {
            val f = cls.getDeclaredField("parent")
            f.isAccessible = true
            f.get(screen) as? GuiScreen
        } catch (e: Throwable) {
            VintageResourcify.LOG.warn("Could not read ShaderPackScreen.parent reflectively", e)
            null
        }
    }

    /** Resolve the shaderpacks directory via Iris if loaded, else fall back to <mcDataDir>/shaderpacks. */
    fun getShaderpacksFolder(): File {
        irisClass?.let { cls ->
            try {
                val m = cls.getDeclaredMethod("getShaderpacksDirectory")
                val result = m.invoke(null)
                if (result is Path) return result.toFile()
            } catch (e: Throwable) {
                VintageResourcify.LOG.warn("Iris.getShaderpacksDirectory reflection failed", e)
            }
        }
        return File(Minecraft.getMinecraft().mcDataDir, "shaderpacks")
    }

    /** Open Iris's ShaderPackScreen, returning true on success. Falls back if Iris isn't loaded. */
    fun openShaderPackScreen(parent: GuiScreen?): Boolean {
        val cls = shaderScreenClass ?: return false
        return try {
            val ctor = cls.getDeclaredConstructor(GuiScreen::class.java)
            val screen = ctor.newInstance(parent) as GuiScreen
            Minecraft.getMinecraft().displayGuiScreen(screen)
            true
        } catch (e: Throwable) {
            VintageResourcify.LOG.warn("Failed to open ShaderPackScreen reflectively", e)
            false
        }
    }
}
