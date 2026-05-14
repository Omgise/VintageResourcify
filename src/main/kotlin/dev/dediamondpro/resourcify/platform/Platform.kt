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

package dev.dediamondpro.resourcify.platform

import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.mixins.early.minecraft.AbstractResourcePackAccessor
import dev.dediamondpro.resourcify.mixins.early.minecraft.ResourcePackEntryAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.AbstractResourcePack
import java.io.File

object Platform {

    // The mod targets exactly one MC version. 1.7.10's ForgeVersion has no
    // mcVersion field, and looking it up at runtime would just yield the same
    // string anyway.
    fun getMcVersion(): String = "1.7.10"

    fun reloadResources() {
        Minecraft.getMinecraft().refreshResources()
    }

    // Find the repository entry whose backing pack file matches `file` and
    // close its IResourcePack so its open ZipFile handle is released. Used
    // before overwriting a pack on disk so Windows can replace the file and
    // so MC's in-memory cache of the old pack is dropped.
    fun closeResourcePack(file: File) {
        val repo = Minecraft.getMinecraft().resourcePackRepository
        repo.updateRepositoryEntriesAll()
        for (entry in repo.repositoryEntriesAll) {
            val pack = entry.resourcePack as? AbstractResourcePack ?: continue
            if ((pack as AbstractResourcePackAccessor).resourcePackFile == file) {
                entry.closeResourcePack()
            }
        }
    }

    // After downloading a replacement zip, force the matching entry to reload
    // its IResourcePack from disk. Without this, MC keeps using the cached
    // FileResourcePack instance, which has its own pre-existing ZipFile view.
    fun reloadResourcePack(file: File) {
        val repo = Minecraft.getMinecraft().resourcePackRepository
        repo.updateRepositoryEntriesAll()
        for (entry in repo.repositoryEntriesAll) {
            val pack = entry.resourcePack as? AbstractResourcePack ?: continue
            if ((pack as AbstractResourcePackAccessor).resourcePackFile == file) {
                try {
                    entry.updateResourcePack()
                    // Null the cached ResourceLocation so bindTexturePackIcon
                    // rebuilds a fresh DynamicTexture from the new pack image
                    // (the BufferedImage in `texturePackIcon` was already
                    // refreshed by updateResourcePack).
                    (entry as ResourcePackEntryAccessor).setLocationTexturePackIcon(null)
                } catch (e: Exception) {
                    VintageResourcify.LOG.warn("Failed to reload pack {}", file.name, e)
                }
            }
        }
    }
}
