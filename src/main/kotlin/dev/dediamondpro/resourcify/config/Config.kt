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

package dev.dediamondpro.resourcify.config

import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.services.modrinth.ModrinthService
import dev.dediamondpro.resourcify.util.fromJson
import dev.dediamondpro.resourcify.util.toJson
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class Config {
    var debugMode: Boolean = false
    var defaultService: String = ModrinthService.getName()
    var fullResThumbnail: Boolean = false
    var openLinkInResourcify: Boolean = true
    var adsEnabled: Boolean = true
    var resourcePacksEnabled: Boolean = true
    var dataPacksEnabled: Boolean = true
    var shaderPacksEnabled: Boolean = true
    var worldsEnabled: Boolean = true
    var autoUpdateChecks: Boolean = true
    var enableSounds: Boolean = true
    var curseUserAgent: String = ""
    var curseApiToken: String = ""

    // "light" or "dark". Used by MarkdownRenderer to pick GitHub-style palette.
    var markdownTheme: String = "dark"

    companion object {
        private val configDir = File("./config/vintageresourcify")
        private val configFile = File(configDir, "resourcify.json")
        private val loadCallbacks = CopyOnWriteArrayList<Runnable>()

        @JvmStatic
        @Volatile
        var instance: Config = load()
            private set

        init {
            // Load the file and immediately save it so all new values get saved
            save(instance)
            fireLoadCallbacks()
        }

        private fun load(): Config {
            ensureConfigDirectory()
            return try {
                configFile.readText().fromJson()
            } catch (e: Exception) {
                return Config()
            }
        }

        @JvmStatic
        fun addLoadCallback(callback: Runnable) {
            addLoadCallback(callback, false)
        }

        @JvmStatic
        fun addLoadCallback(callback: Runnable, fireImmediately: Boolean) {
            loadCallbacks.add(callback)
            if (fireImmediately) runLoadCallback(callback)
        }

        @JvmStatic
        fun removeLoadCallback(callback: Runnable): Boolean {
            return loadCallbacks.remove(callback)
        }

        @JvmStatic
        fun reload(): Config {
            instance = load()
            save(instance)
            fireLoadCallbacks()
            return instance
        }

        @JvmStatic
        fun getConfigDirectory(): File {
            ensureConfigDirectory()
            return configDir
        }

        fun save(config: Config = instance) {
            ensureConfigDirectory()
            configFile.outputStream().bufferedWriter().use {
                it.write(config.toJson())
            }
        }

        private fun ensureConfigDirectory() {
            if (!configDir.exists()) configDir.mkdirs()
        }

        private fun fireLoadCallbacks() {
            for (callback in loadCallbacks) {
                runLoadCallback(callback)
            }
        }

        private fun runLoadCallback(callback: Runnable) {
            try {
                callback.run()
            } catch (t: Throwable) {
                VintageResourcify.LOG.warn("Config load callback failed", t)
            }
        }
    }
}
