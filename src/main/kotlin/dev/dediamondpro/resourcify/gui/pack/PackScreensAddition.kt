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
import dev.dediamondpro.resourcify.config.Config
import dev.dediamondpro.resourcify.gui.browsepage.BrowseScreen
import dev.dediamondpro.resourcify.mixins.early.minecraft.PackScreenAccessor
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.IVersion
import dev.dediamondpro.resourcify.services.ProjectType
import dev.dediamondpro.resourcify.services.ServiceRegistry
import dev.dediamondpro.resourcify.util.DownloadManager
import dev.dediamondpro.resourcify.util.DownloadResult
import dev.dediamondpro.resourcify.util.IrisHelper
import dev.dediamondpro.resourcify.util.LocalIndex
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiScreenResourcePacks
import net.minecraft.util.ResourceLocation
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import org.fentanylsolutions.fentlib.util.FileUtil
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture

object PackScreensAddition {

    private const val BUTTON_SIZE = 20
    private const val ICON_SIZE = 16
    private const val BUTTON_GAP = 4
    private const val PANEL_WIDTH = 360
    private const val PANEL_HEIGHT = 260
    private const val PANEL_PADDING = 10
    private const val PANEL_MARGIN = 12
    private const val ROW_HEIGHT = 38
    private const val TEXT_BUTTON_HEIGHT = 22
    private const val BADGE_HEIGHT = 14
    private const val PROGRESS_HEIGHT = 8

    private val PLUS_TEXTURE = ResourceLocation(VintageResourcify.MODID, "plus.png")
    private val UPDATE_TEXTURE = ResourceLocation(VintageResourcify.MODID, "update.png")
    private val PICK_FILE_TEXTURE = ResourceLocation(VintageResourcify.MODID, "pick_file.png")

    @Volatile private var toastText: String? = null
    @Volatile private var toastUntil: Long = 0L
    @Volatile private var checkInProgress = false
    @Volatile private var updatePanelOpen = false
    @Volatile private var updateInProgress = false
    @Volatile private var updateCancelRequested = false
    @Volatile private var panelType: ProjectType? = null
    @Volatile private var panelFolder: File? = null
    @Volatile private var updateStatusText: String = ""
    @Volatile private var updateScroll = 0
    @Volatile private var updateTotal = 0
    @Volatile private var updateCompleted = 0
    @Volatile private var currentDownloadUrl: URL? = null
    @Volatile private var activeDownload: CompletableFuture<DownloadResult>? = null

    private val prunedScreens = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val autoCheckedScreens = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val updateLock = Any()
    private val updateEntries = mutableListOf<UpdateEntry>()

    fun onRender(type: ProjectType, folder: File, screen: GuiScreen, mouseX: Int, mouseY: Int) {
        pruneIfFirstSeen(screen, folder)
        if (type.hasUpdateButton && Config.instance.autoUpdateChecks) {
            autoCheckIfFirstSeen(screen, type, folder)
        }

        val (plusX, plusY) = plusOrigin() ?: return
        drawIconButton(plusX, plusY, PLUS_TEXTURE, mouseX, mouseY, enabled = true)

        if (supportsPackFilePicking(type)) {
            val (pickX, pickY) = pickOrigin() ?: return
            drawIconButton(pickX, pickY, PICK_FILE_TEXTURE, mouseX, mouseY, enabled = true)
        }

        if (type.hasUpdateButton) {
            val (upX, upY) = updateOrigin() ?: return
            drawIconButton(upX, upY, UPDATE_TEXTURE, mouseX, mouseY, enabled = true)
            val count = badgeCount(type, folder)
            if (count > 0) {
                drawUpdateBadge(upX, upY, count)
            }
        }

        if (updatePanelOpen && matchesPanel(type, folder)) {
            drawUpdatePanel(type, folder, screen, mouseX, mouseY)
        } else {
            drawToast(plusY + BUTTON_SIZE + 4)
        }

        PackOverlayRenderer.drainPendingTooltip()
    }

    fun onMouseClick(mouseX: Int, mouseY: Int, button: Int, type: ProjectType, folder: File, screen: GuiScreen): Boolean {
        if (button != 0) return false
        if (updatePanelOpen && matchesPanel(type, folder)) {
            return handleUpdatePanelClick(mouseX, mouseY, type, folder)
        }

        val plus = plusOrigin() ?: return false
        if (isInside(mouseX, mouseY, plus.first, plus.second, BUTTON_SIZE, BUTTON_SIZE)) {
            openBrowser(type, folder, screen)
            return true
        }

        if (supportsPackFilePicking(type)) {
            val pick = pickOrigin() ?: return false
            if (isInside(mouseX, mouseY, pick.first, pick.second, BUTTON_SIZE, BUTTON_SIZE)) {
                pickAndCopyPackFile(type, folder)
                return true
            }
        }

        if (!type.hasUpdateButton) return false
        val up = updateOrigin() ?: return false
        if (isInside(mouseX, mouseY, up.first, up.second, BUTTON_SIZE, BUTTON_SIZE) ||
            isInsideUpdateBadge(mouseX, mouseY, up.first, up.second, badgeCount(type, folder))) {
            openUpdatePanel(type, folder)
            if (!checkInProgress && updatableEntries().isEmpty()) {
                startUpdateCheck(type, folder, openPanel = true)
            }
            return true
        }
        return false
    }

    fun onKeyTyped(keyCode: Int): Boolean {
        if (!updatePanelOpen) return false
        if (!isPanelVisibleOnCurrentScreen()) {
            updatePanelOpen = false
            return false
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (!updateInProgress) {
                updatePanelOpen = false
            }
            return true
        }
        return false
    }

    fun onMouseInput(): Boolean {
        if (!updatePanelOpen) return false
        if (!isPanelVisibleOnCurrentScreen()) {
            updatePanelOpen = false
            return false
        }
        val wheel = Mouse.getEventDWheel()
        if (wheel == 0) return false

        val mc = Minecraft.getMinecraft()
        val screen = mc.currentScreen ?: return false
        val mouseX = Mouse.getEventX() * screen.width / mc.displayWidth
        val mouseY = screen.height - Mouse.getEventY() * screen.height / mc.displayHeight - 1
        val panelW = PANEL_WIDTH.coerceAtMost(screen.width - PANEL_MARGIN * 2).coerceAtLeast(240)
        val panelH = PANEL_HEIGHT.coerceAtMost(screen.height - PANEL_MARGIN * 2).coerceAtLeast(170)
        val panelX = (screen.width - panelW) / 2
        val panelY = (screen.height - panelH) / 2
        val listX = panelX + PANEL_PADDING
        val listY = panelY + 36
        val listW = panelW - PANEL_PADDING * 2
        val listH = panelH - 78
        val maxScroll = maxScroll(entriesSnapshot().size, listH)
        if (maxScroll > 0 && isInside(mouseX, mouseY, listX, listY, listW, listH)) {
            updateScroll = (updateScroll - wheel / 120).coerceIn(0, maxScroll)
        }
        return true
    }

    private fun pruneIfFirstSeen(screen: GuiScreen, folder: File) {
        synchronized(prunedScreens) {
            if (!prunedScreens.add(screen)) return
        }
        try {
            val removed = LocalIndex.forFolder(folder).prune()
            if (removed > 0) VintageResourcify.LOG.info("Pruned {} stale install index entries in {}", removed, folder)
        } catch (e: Throwable) {
            VintageResourcify.LOG.warn("Index prune failed for {}", folder, e)
        }
    }

    private fun autoCheckIfFirstSeen(screen: GuiScreen, type: ProjectType, folder: File) {
        synchronized(autoCheckedScreens) {
            if (!autoCheckedScreens.add(screen)) return
        }
        startUpdateCheck(type, folder, openPanel = false)
    }

    private fun openBrowser(type: ProjectType, folder: File, screen: GuiScreen) {
        updatePanelOpen = false
        val grandparent: GuiScreen? = when {
            screen is PackScreenAccessor -> (screen as PackScreenAccessor).parentScreen
            else -> IrisHelper.getShaderScreenParent(screen)
        }
        ClientGUI.open(BrowseScreen(type, folder, grandparent))
    }

    private fun pickAndCopyPackFile(type: ProjectType, folder: File) {
        showToast("Select a zip file...", durationMs = 4_000)
        Thread({
            val result = try {
                FileUtil.pickFile("Select pack zip", FileUtil.getDefaultFileSelectionDirectory(), "zip")
            } catch (e: Throwable) {
                VintageResourcify.LOG.warn("File picker failed", e)
                showToast("File picker failed", durationMs = 5_000)
                return@Thread
            }
            when (result.status) {
                FileUtil.FilePickerResult.Status.SELECTED -> {
                    val source = result.file
                    if (source == null) {
                        showToast("No file selected", durationMs = 4_000)
                        return@Thread
                    }
                    copyPickedPackFile(type, resolveImportFolder(type, folder), source)
                }
                FileUtil.FilePickerResult.Status.CANCELLED -> showToast("Import cancelled", durationMs = 3_000)
                FileUtil.FilePickerResult.Status.UNAVAILABLE,
                FileUtil.FilePickerResult.Status.ERROR ->
                    showToast(result.message ?: "File picker failed", durationMs = 6_000)
            }
        }, "Resourcify-PackFilePicker").apply { isDaemon = true }.start()
    }

    private fun copyPickedPackFile(type: ProjectType, folder: File, source: File) {
        try {
            if (!folder.exists()) folder.mkdirs()
            val target = File(folder, targetFileName(source.name))
            if (sameFile(source, target)) {
                showToast("File is already in this folder", durationMs = 4_000)
                runClientSync { refreshHostPackScreen(type) }
                return
            }
            if ((type == ProjectType.RESOURCE_PACK || type == ProjectType.AYCY_RESOURCE_PACK) && target.exists()) {
                runClientSync {
                    try {
                        Platform.closeResourcePack(target)
                    } catch (_: Throwable) {
                    }
                }
            }
            VintageResourcify.LOG.info("Importing pack file {} to {}", source, target)
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            showToast("Imported ${target.name}", durationMs = 5_000)
            runClientSync { refreshHostPackScreen(type) }
        } catch (e: Throwable) {
            VintageResourcify.LOG.warn("Could not import pack file {}", source, e)
            showToast("Import failed", durationMs = 5_000)
        }
    }

    private fun resolveImportFolder(type: ProjectType, folder: File): File {
        return when (type) {
            ProjectType.RESOURCE_PACK,
            ProjectType.AYCY_RESOURCE_PACK -> Minecraft.getMinecraft().resourcePackRepository.dirResourcepacks
            ProjectType.IRIS_SHADER,
            ProjectType.OPTIFINE_SHADER -> IrisHelper.getShaderpacksFolder()
            else -> folder
        }
    }

    private fun targetFileName(sourceName: String): String {
        return if (sourceName.endsWith(".zip", ignoreCase = true) && !sourceName.endsWith(".zip")) {
            sourceName.dropLast(4) + ".zip"
        } else {
            sourceName
        }
    }

    private fun startUpdateCheck(type: ProjectType, folder: File, openPanel: Boolean) {
        if (checkInProgress || updateInProgress) return
        checkInProgress = true
        setPanelContext(type, folder)
        synchronized(updateLock) {
            updateEntries.clear()
            updateScroll = 0
        }
        if (openPanel) {
            updateStatusText = "Checking for updates..."
        }
        Thread({
            try {
                val found = scanUpdates(type, folder)
                synchronized(updateLock) {
                    updateEntries.clear()
                    updateEntries.addAll(found)
                    updateScroll = 0
                }
                updateStatusText = if (found.isEmpty()) {
                    "All tracked packs are up to date"
                } else {
                    "${found.size} update${if (found.size == 1) "" else "s"} available"
                }
            } catch (e: Throwable) {
                VintageResourcify.LOG.warn("Update check failed for {}", folder, e)
                updateStatusText = "Update check failed"
                if (openPanel) showToast("Update check failed", durationMs = 5_000)
            } finally {
                checkInProgress = false
            }
        }, "Resourcify-UpdateCheck").apply { isDaemon = true }.start()
    }

    private fun scanUpdates(type: ProjectType, folder: File): List<UpdateEntry> {
        val index = LocalIndex.forFolder(folder)
        val tracked = index.listEntries()
            .mapNotNull { entry -> File(folder, entry.fileName).takeIf { it.exists() }?.let { it to entry } }
        if (tracked.isEmpty()) return emptyList()

        val byPlatform = tracked.groupBy({ it.second.platform }) { it.first }
        val merged = mutableMapOf<File, IVersion>()
        for ((platform, files) in byPlatform) {
            val service = ServiceRegistry.getAllServices()
                .firstOrNull { it.getPlatformId() == platform && it.isProjectTypeSupported(type) }
            if (service == null) {
                VintageResourcify.LOG.warn("No service registered for platform {}", platform)
                continue
            }
            val map = try {
                service.getUpdates(files, type).join()
            } catch (e: Throwable) {
                VintageResourcify.LOG.warn("getUpdates failed for {}", service.getName(), e)
                continue
            }
            for ((file, version) in map) {
                if (version != null) merged[file] = version
            }
        }
        return merged.entries
            .map { UpdateEntry(it.key, it.value) }
            .sortedBy { it.oldFile.name.lowercase(Locale.ROOT) }
    }

    private fun drawUpdatePanel(type: ProjectType, folder: File, screen: GuiScreen, mouseX: Int, mouseY: Int) {
        val mc = Minecraft.getMinecraft()
        val fr = mc.fontRenderer ?: return
        val panelW = PANEL_WIDTH.coerceAtMost(screen.width - PANEL_MARGIN * 2).coerceAtLeast(240)
        val panelH = PANEL_HEIGHT.coerceAtMost(screen.height - PANEL_MARGIN * 2).coerceAtLeast(170)
        val panelX = (screen.width - panelW) / 2
        val panelY = (screen.height - panelH) / 2

        Gui.drawRect(0, 0, screen.width, screen.height, 0x77000000)
        Gui.drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF202020.toInt())
        Gui.drawRect(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + panelH - 1, 0xFFF0F0F0.toInt())

        fr.drawString("Available updates", panelX + PANEL_PADDING, panelY + 8, 0x202020, false)
        val status = when {
            updateInProgress -> updateStatusText
            checkInProgress -> "Checking for updates..."
            else -> updateStatusText
        }
        if (status.isNotBlank()) {
            fr.drawString(trimToWidth(fr, status, panelW - PANEL_PADDING * 2 - 120), panelX + PANEL_PADDING, panelY + 20, 0x606060, false)
        }

        val listX = panelX + PANEL_PADDING
        val listY = panelY + 36
        val listW = panelW - PANEL_PADDING * 2
        val listH = panelH - 78
        val entries = entriesSnapshot()
        val maxScroll = maxScroll(entries.size, listH)
        updateScroll = updateScroll.coerceIn(0, maxScroll)

        Gui.drawRect(listX, listY, listX + listW, listY + listH, 0xFFE7E7E7.toInt())
        beginScissor(listX, listY, listW, listH, screen)
        try {
            if (entries.isEmpty()) {
                val text = if (checkInProgress) "Checking..." else "No updates available"
                fr.drawString(text, listX + 8, listY + 8, 0x505050, false)
            } else {
                val first = updateScroll
                val last = (first + listH / ROW_HEIGHT + 2).coerceAtMost(entries.size)
                for (index in first until last) {
                    drawUpdateEntry(entries[index], index, listX, listY + (index - first) * ROW_HEIGHT, listW, mouseX, mouseY)
                }
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
        }
        drawScrollBar(listX, listY, listW, listH, entries.size, maxScroll)

        if (updateInProgress) {
            val cancelW = 74
            val buttonY = panelY + panelH - TEXT_BUTTON_HEIGHT - PANEL_PADDING
            val barX = panelX + PANEL_PADDING
            val barY = buttonY + (TEXT_BUTTON_HEIGHT - PROGRESS_HEIGHT) / 2
            val barW = panelW - PANEL_PADDING * 3 - cancelW - 8
            drawProgressBar(barX, barY, barW, currentProgress())
            drawTextButton(
                panelX + panelW - PANEL_PADDING - cancelW,
                buttonY,
                cancelW,
                TEXT_BUTTON_HEIGHT,
                "Cancel",
                enabled = true,
                hover = isInside(mouseX, mouseY, panelX + panelW - PANEL_PADDING - cancelW, buttonY, cancelW, TEXT_BUTTON_HEIGHT),
            )
        } else {
            val buttons = bottomButtons(panelX, panelY, panelW, panelH)
            val canUpdate = updatableEntries().isNotEmpty()
            drawTextButton(
                buttons.updateAllX,
                buttons.y,
                buttons.updateAllW,
                TEXT_BUTTON_HEIGHT,
                "Update All",
                enabled = canUpdate,
                hover = canUpdate && isInside(mouseX, mouseY, buttons.updateAllX, buttons.y, buttons.updateAllW, TEXT_BUTTON_HEIGHT),
            )
            drawTextButton(
                buttons.checkX,
                buttons.y,
                buttons.checkW,
                TEXT_BUTTON_HEIGHT,
                buttons.checkLabel,
                enabled = !checkInProgress,
                hover = !checkInProgress && isInside(mouseX, mouseY, buttons.checkX, buttons.y, buttons.checkW, TEXT_BUTTON_HEIGHT),
            )
            drawTextButton(
                buttons.closeX,
                buttons.y,
                buttons.closeW,
                TEXT_BUTTON_HEIGHT,
                "Close",
                enabled = true,
                hover = isInside(mouseX, mouseY, buttons.closeX, buttons.y, buttons.closeW, TEXT_BUTTON_HEIGHT),
            )
        }
    }

    private fun drawUpdateEntry(entry: UpdateEntry, index: Int, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val fr = Minecraft.getMinecraft().fontRenderer ?: return
        val rowColor = if (index % 2 == 0) 0xFFF7F7F7.toInt() else 0xFFFFFFFF.toInt()
        Gui.drawRect(x, y, x + width, y + ROW_HEIGHT - 1, rowColor)
        val buttonEnabled = !updateInProgress && entry.status != UpdateStatus.UPDATED && entry.status != UpdateStatus.UPDATING
        val buttonX = x + width - BUTTON_SIZE - 6
        val buttonY = y + (ROW_HEIGHT - BUTTON_SIZE) / 2
        val textW = buttonX - x - 12

        fr.drawString(trimToWidth(fr, entry.oldFile.name, textW), x + 6, y + 5, 0x202020, false)
        val version = "to ${versionLabel(entry.version)}"
        fr.drawString(trimToWidth(fr, version, textW), x + 6, y + 17, 0x555555, false)

        val status = statusLabel(entry)
        if (status.isNotEmpty()) {
            val statusColor = when (entry.status) {
                UpdateStatus.UPDATED -> 0x207020
                UpdateStatus.FAILED, UpdateStatus.CANCELLED -> 0xA03030
                else -> 0x606060
            }
            fr.drawString(status, x + 6, y + 28, statusColor, false)
        }
        drawIconButton(
            buttonX,
            buttonY,
            UPDATE_TEXTURE,
            mouseX,
            mouseY,
            enabled = buttonEnabled,
        )
    }

    private fun handleUpdatePanelClick(mouseX: Int, mouseY: Int, type: ProjectType, folder: File): Boolean {
        val screen = Minecraft.getMinecraft().currentScreen ?: return true
        val panelW = PANEL_WIDTH.coerceAtMost(screen.width - PANEL_MARGIN * 2).coerceAtLeast(240)
        val panelH = PANEL_HEIGHT.coerceAtMost(screen.height - PANEL_MARGIN * 2).coerceAtLeast(170)
        val panelX = (screen.width - panelW) / 2
        val panelY = (screen.height - panelH) / 2
        if (!isInside(mouseX, mouseY, panelX, panelY, panelW, panelH)) {
            if (!updateInProgress) updatePanelOpen = false
            return true
        }

        if (updateInProgress) {
            val cancelW = 74
            val cancelX = panelX + panelW - PANEL_PADDING - cancelW
            val cancelY = panelY + panelH - TEXT_BUTTON_HEIGHT - PANEL_PADDING
            if (isInside(mouseX, mouseY, cancelX, cancelY, cancelW, TEXT_BUTTON_HEIGHT)) {
                updateCancelRequested = true
                updateStatusText = "Cancelling..."
                currentDownloadUrl?.let { DownloadManager.cancelDownload(it) }
                activeDownload?.cancel(false)
            }
            return true
        }

        val listX = panelX + PANEL_PADDING
        val listY = panelY + 36
        val listW = panelW - PANEL_PADDING * 2
        val listH = panelH - 78
        val entries = entriesSnapshot()
        val first = updateScroll.coerceIn(0, maxScroll(entries.size, listH))
        val last = (first + listH / ROW_HEIGHT + 2).coerceAtMost(entries.size)
        for (index in first until last) {
            val entry = entries[index]
            val rowY = listY + (index - first) * ROW_HEIGHT
            val buttonX = listX + listW - BUTTON_SIZE - 6
            val buttonY = rowY + (ROW_HEIGHT - BUTTON_SIZE) / 2
            if (entry.status != UpdateStatus.UPDATED &&
                isInside(mouseX, mouseY, buttonX, buttonY, BUTTON_SIZE, BUTTON_SIZE)) {
                startUpdate(listOf(entry), type, folder)
                return true
            }
        }

        val buttons = bottomButtons(panelX, panelY, panelW, panelH)
        if (isInside(mouseX, mouseY, buttons.updateAllX, buttons.y, buttons.updateAllW, TEXT_BUTTON_HEIGHT)) {
            val targets = updatableEntries()
            if (targets.isNotEmpty()) {
                startUpdate(targets, type, folder)
            }
            return true
        }

        if (isInside(mouseX, mouseY, buttons.checkX, buttons.y, buttons.checkW, TEXT_BUTTON_HEIGHT)) {
            if (!checkInProgress) {
                startUpdateCheck(type, folder, openPanel = true)
            }
            return true
        }

        if (isInside(mouseX, mouseY, buttons.closeX, buttons.y, buttons.closeW, TEXT_BUTTON_HEIGHT)) {
            updatePanelOpen = false
            return true
        }

        return true
    }

    private fun startUpdate(entries: List<UpdateEntry>, type: ProjectType, folder: File) {
        if (updateInProgress || entries.isEmpty()) return
        setPanelContext(type, folder)
        updatePanelOpen = true
        updateInProgress = true
        updateCancelRequested = false
        updateTotal = entries.size
        updateCompleted = 0
        updateStatusText = "Updating 0/${entries.size}..."

        val prepared = entries.map { entry ->
            val wasEnabled = when (type) {
                ProjectType.RESOURCE_PACK, ProjectType.AYCY_RESOURCE_PACK -> Platform.isResourcePackEnabled(entry.oldFile)
                ProjectType.IRIS_SHADER, ProjectType.OPTIFINE_SHADER -> IrisHelper.isShaderPackEnabled(entry.oldFile)
                else -> false
            }
            PreparedUpdate(entry, wasEnabled)
        }

        Thread({
            var updated = 0
            var failed = 0
            var cancelled = 0
            for (item in prepared) {
                if (updateCancelRequested) break
                item.entry.status = UpdateStatus.UPDATING
                updateStatusText = "Updating ${updateCompleted}/${updateTotal}: ${item.entry.oldFile.name}"
                val result = performUpdate(item, type, folder)
                item.entry.status = result
                when (result) {
                    UpdateStatus.UPDATED -> updated++
                    UpdateStatus.CANCELLED -> cancelled++
                    UpdateStatus.FAILED -> failed++
                    else -> {}
                }
                updateCompleted++
                if (result == UpdateStatus.CANCELLED) break
            }
            currentDownloadUrl = null
            activeDownload = null
            updateInProgress = false
            updateStatusText = when {
                cancelled > 0 -> "Cancelled after $updateCompleted/${updateTotal}"
                failed > 0 -> "Updated $updated, failed $failed"
                else -> "Updated $updated pack${if (updated == 1) "" else "s"}"
            }
        }, "Resourcify-PackUpdate").apply { isDaemon = true }.start()
    }

    private fun performUpdate(item: PreparedUpdate, type: ProjectType, folder: File): UpdateStatus {
        val entry = item.entry
        val version = entry.version
        val url = version.getDownloadUrl() ?: return UpdateStatus.FAILED
        val newFile = File(folder, version.getFileName())
        val index = LocalIndex.forFolder(folder)
        val oldEntry = index.lookupByFile(entry.oldFile)

        if (type == ProjectType.RESOURCE_PACK || type == ProjectType.AYCY_RESOURCE_PACK) {
            runClientSync {
                if (entry.oldFile.exists()) {
                    try {
                        Platform.closeResourcePack(entry.oldFile)
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        currentDownloadUrl = url
        val future = DownloadManager.download(newFile, version.getSha1(), url, false)
        activeDownload = future
        val result = try {
            future.get()
        } catch (e: Throwable) {
            if (updateCancelRequested) DownloadResult.CANCELLED else {
                VintageResourcify.LOG.warn("Download failed for {}", newFile.name, e)
                DownloadResult.FAILED
            }
        } finally {
            activeDownload = null
            currentDownloadUrl = null
        }
        if (result == DownloadResult.CANCELLED) return UpdateStatus.CANCELLED
        if (result == DownloadResult.FAILED) return UpdateStatus.FAILED

        return try {
            if (entry.oldFile != newFile && entry.oldFile.exists()) {
                if (type == ProjectType.RESOURCE_PACK || type == ProjectType.AYCY_RESOURCE_PACK) {
                    runClientSync {
                        try {
                            Platform.closeResourcePack(entry.oldFile)
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (!entry.oldFile.delete()) {
                    VintageResourcify.LOG.warn("Could not delete old pack {}", entry.oldFile)
                }
                index.remove(entry.oldFile.name)
            }
            if (oldEntry != null) {
                index.record(newFile, oldEntry.platform, oldEntry.projectId)
            }
            runClientSync {
                when (type) {
                    ProjectType.RESOURCE_PACK, ProjectType.AYCY_RESOURCE_PACK -> {
                        if (item.wasEnabled) {
                            Platform.replaceEnabledResourcePack(entry.oldFile, newFile)
                        } else {
                            Platform.reloadResourcePack(newFile)
                        }
                        refreshHostPackScreen(type)
                    }
                    ProjectType.IRIS_SHADER, ProjectType.OPTIFINE_SHADER -> {
                        if (item.wasEnabled) {
                            IrisHelper.enableShaderPack(newFile)
                        }
                        refreshHostPackScreen(type)
                    }
                    else -> {}
                }
            }
            UpdateStatus.UPDATED
        } catch (e: Throwable) {
            VintageResourcify.LOG.warn("Could not finalize update for {}", entry.oldFile.name, e)
            UpdateStatus.FAILED
        }
    }

    private fun drawToast(yTop: Int) {
        val text = toastText ?: return
        if (Minecraft.getSystemTime() > toastUntil) {
            toastText = null
            return
        }
        val mc = Minecraft.getMinecraft()
        val fr = mc.fontRenderer ?: return
        val tw = fr.getStringWidth(text)
        val pad = 4
        val boxW = tw + pad * 2
        val boxH = fr.FONT_HEIGHT + pad * 2
        val (plusX, _) = plusOrigin() ?: return
        val x = plusX + BUTTON_SIZE - boxW
        Gui.drawRect(x, yTop, x + boxW, yTop + boxH, 0xCC000000.toInt())
        fr.drawString(text, x + pad, yTop + pad, 0xFFFFFF, false)
    }

    private fun drawIconButton(
        x: Int,
        y: Int,
        texture: ResourceLocation,
        mouseX: Int,
        mouseY: Int,
        enabled: Boolean,
    ) {
        val hover = enabled && isInside(mouseX, mouseY, x, y, BUTTON_SIZE, BUTTON_SIZE)
        val background = when {
            !enabled -> 0x33000000
            hover -> 0x99000000.toInt()
            else -> 0x66000000
        }
        Gui.drawRect(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE, background)
        Minecraft.getMinecraft().textureManager.bindTexture(texture)
        val alpha = if (enabled) 1f else 0.35f
        GL11.glColor4f(1f, 1f, 1f, alpha)
        val iconX = x + (BUTTON_SIZE - ICON_SIZE) / 2
        val iconY = y + (BUTTON_SIZE - ICON_SIZE) / 2
        Gui.func_152125_a(
            iconX, iconY, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE.toFloat(), ICON_SIZE.toFloat()
        )
        GL11.glColor4f(1f, 1f, 1f, 1f)
    }

    private fun drawTextButton(x: Int, y: Int, width: Int, height: Int, text: String, enabled: Boolean, hover: Boolean) {
        val fr = Minecraft.getMinecraft().fontRenderer ?: return
        val border = if (enabled) 0xFF1A1A1A.toInt() else 0xFF777777.toInt()
        val fill = when {
            !enabled -> 0xFFBDBDBD.toInt()
            hover -> 0xFFE0E0E0.toInt()
            else -> 0xFFD2D2D2.toInt()
        }
        Gui.drawRect(x, y, x + width, y + height, border)
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, fill)
        val color = if (enabled) 0x202020 else 0x777777
        fr.drawString(text, x + (width - fr.getStringWidth(text)) / 2, y + (height - fr.FONT_HEIGHT) / 2, color, false)
    }

    private fun drawUpdateBadge(buttonX: Int, buttonY: Int, count: Int) {
        val fr = Minecraft.getMinecraft().fontRenderer ?: return
        val text = if (count > 99) "99+" else count.toString()
        val textWidth = fr.getStringWidth(text)
        val width = (textWidth + 6).coerceAtLeast(BADGE_HEIGHT)
        val x = buttonX + BUTTON_SIZE - width + 4
        val y = buttonY - 1
        Gui.drawRect(x, y, x + width, y + BADGE_HEIGHT, 0xFF000000.toInt())
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + BADGE_HEIGHT - 1, 0xFFFFD33D.toInt())
        fr.drawString(text, x + (width - textWidth + 1) / 2, y + (BADGE_HEIGHT - fr.FONT_HEIGHT + 1) / 2, 0x202020, false)
    }

    private fun drawProgressBar(x: Int, y: Int, width: Int, progress: Float) {
        Gui.drawRect(x, y, x + width, y + PROGRESS_HEIGHT, 0xFF8B8B8B.toInt())
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + PROGRESS_HEIGHT - 1, 0xFFC8C8C8.toInt())
        val fillWidth = ((width - 2) * progress.coerceIn(0f, 1f)).toInt()
        if (fillWidth > 0) {
            Gui.drawRect(x + 1, y + 1, x + 1 + fillWidth, y + PROGRESS_HEIGHT - 1, 0xFF4F8DFF.toInt())
        }
    }

    private fun bottomButtons(panelX: Int, panelY: Int, panelW: Int, panelH: Int): BottomButtons {
        val innerW = panelW - PANEL_PADDING * 2
        val compact = innerW < 270
        val updateAllW = if (compact) 78 else 88
        val checkW = if (compact) 58 else 112
        val closeW = if (compact) 50 else 58
        val gap = if (compact) 4 else 8
        val y = panelY + panelH - TEXT_BUTTON_HEIGHT - PANEL_PADDING
        val closeX = panelX + panelW - PANEL_PADDING - closeW
        val checkX = closeX - gap - checkW
        return BottomButtons(
            updateAllX = panelX + PANEL_PADDING,
            updateAllW = updateAllW,
            checkX = checkX,
            checkW = checkW,
            checkLabel = if (compact) "Check" else "Check Updates",
            closeX = closeX,
            closeW = closeW,
            y = y,
        )
    }

    private fun drawScrollBar(x: Int, y: Int, width: Int, height: Int, rowCount: Int, maxScroll: Int) {
        if (maxScroll <= 0 || rowCount <= 0) return
        val barX = x + width - 5
        val thumbH = (height * (height / ROW_HEIGHT).toFloat() / rowCount.toFloat()).toInt().coerceIn(18, height)
        val thumbY = y + ((height - thumbH) * (updateScroll.toFloat() / maxScroll.toFloat())).toInt()
        Gui.drawRect(barX, y, barX + 4, y + height, 0x33000000)
        Gui.drawRect(barX, thumbY, barX + 4, thumbY + thumbH, 0xAA505050.toInt())
    }

    private fun beginScissor(x: Int, y: Int, width: Int, height: Int, screen: GuiScreen) {
        val mc = Minecraft.getMinecraft()
        val scaleX = mc.displayWidth.toDouble() / screen.width.toDouble()
        val scaleY = mc.displayHeight.toDouble() / screen.height.toDouble()
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(
            (x * scaleX).toInt(),
            (mc.displayHeight - (y + height) * scaleY).toInt(),
            (width * scaleX).toInt(),
            (height * scaleY).toInt(),
        )
    }

    private fun currentProgress(): Float {
        if (updateTotal <= 0) return 0f
        val current = currentDownloadUrl?.let { DownloadManager.getProgress(it) } ?: 0f
        return ((updateCompleted.toFloat() + current) / updateTotal.toFloat()).coerceIn(0f, 1f)
    }

    private fun entriesSnapshot(): List<UpdateEntry> = synchronized(updateLock) { updateEntries.toList() }

    private fun updatableEntries(): List<UpdateEntry> {
        return synchronized(updateLock) {
            updateEntries.filter { it.status != UpdateStatus.UPDATED && it.status != UpdateStatus.UPDATING }
        }
    }

    private fun badgeCount(type: ProjectType, folder: File): Int {
        if (!matchesPanel(type, folder)) return 0
        return synchronized(updateLock) {
            updateEntries.count { it.status != UpdateStatus.UPDATED }
        }
    }

    private fun statusLabel(entry: UpdateEntry): String {
        return when (entry.status) {
            UpdateStatus.PENDING -> fileSizeLabel(entry.version.getFileSize())
            UpdateStatus.UPDATING -> "Updating"
            UpdateStatus.UPDATED -> "Updated"
            UpdateStatus.FAILED -> "Failed"
            UpdateStatus.CANCELLED -> "Cancelled"
        }
    }

    private fun versionLabel(version: IVersion): String {
        val name = version.getName()
        val number = version.getVersionNumber()
        return when {
            number.isNullOrBlank() -> name
            name.isBlank() -> number
            name == number -> name
            else -> "$name ($number)"
        }
    }

    private fun fileSizeLabel(size: Long?): String {
        if (size == null || size < 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = size.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${value.toLong()} ${units[unit]}" else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }

    private fun trimToWidth(fr: net.minecraft.client.gui.FontRenderer, text: String, maxWidth: Int): String {
        if (fr.getStringWidth(text) <= maxWidth) return text
        val suffix = "..."
        val trimmed = fr.trimStringToWidth(text, (maxWidth - fr.getStringWidth(suffix)).coerceAtLeast(0))
        return trimmed + suffix
    }

    private fun maxScroll(rowCount: Int, listH: Int): Int {
        val visibleRows = (listH / ROW_HEIGHT).coerceAtLeast(1)
        return (rowCount - visibleRows).coerceAtLeast(0)
    }

    private fun showToast(text: String, durationMs: Long) {
        toastText = text
        toastUntil = Minecraft.getSystemTime() + durationMs
    }

    private fun setPanelContext(type: ProjectType, folder: File) {
        val canonicalFolder = safeCanonical(folder)
        val changed = panelType != type || panelFolder?.let { !sameFile(it, canonicalFolder) } ?: true
        panelType = type
        panelFolder = canonicalFolder
        if (changed && !updateInProgress) {
            synchronized(updateLock) {
                updateEntries.clear()
                updateScroll = 0
            }
            updateStatusText = ""
        }
    }

    private fun openUpdatePanel(type: ProjectType, folder: File) {
        setPanelContext(type, folder)
        updatePanelOpen = true
        if (updateStatusText.isBlank()) {
            updateStatusText = "Checking for updates..."
        }
    }

    private fun matchesPanel(type: ProjectType, folder: File): Boolean {
        val currentType = panelType ?: return false
        val currentFolder = panelFolder ?: return false
        return currentType == type && sameFile(currentFolder, folder)
    }

    private fun isPanelVisibleOnCurrentScreen(): Boolean {
        val screen = Minecraft.getMinecraft().currentScreen ?: return false
        val type = panelType ?: return false
        val folder = panelFolder ?: return false
        return when (type) {
            ProjectType.RESOURCE_PACK, ProjectType.AYCY_RESOURCE_PACK ->
                screen is GuiScreenResourcePacks &&
                    sameFile(folder, Minecraft.getMinecraft().resourcePackRepository.dirResourcepacks)
            ProjectType.IRIS_SHADER, ProjectType.OPTIFINE_SHADER ->
                IrisHelper.isShaderPackScreen(screen) && sameFile(folder, IrisHelper.getShaderpacksFolder())
            else -> false
        }
    }

    private fun isInsideUpdateBadge(mouseX: Int, mouseY: Int, buttonX: Int, buttonY: Int, count: Int): Boolean {
        if (count <= 0) return false
        val fr = Minecraft.getMinecraft().fontRenderer ?: return false
        val text = if (count > 99) "99+" else count.toString()
        val width = (fr.getStringWidth(text) + 6).coerceAtLeast(BADGE_HEIGHT)
        val x = buttonX + BUTTON_SIZE - width + 4
        val y = buttonY - 1
        return isInside(mouseX, mouseY, x, y, width, BADGE_HEIGHT)
    }

    private fun isInside(mouseX: Int, mouseY: Int, x: Int, y: Int, width: Int, height: Int): Boolean {
        return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height
    }

    private fun plusOrigin(): Pair<Int, Int>? {
        val screen = Minecraft.getMinecraft().currentScreen ?: return null
        return (screen.width - BUTTON_SIZE - 4) to 4
    }

    private fun pickOrigin(): Pair<Int, Int>? {
        val (px, py) = plusOrigin() ?: return null
        return (px - BUTTON_SIZE - BUTTON_GAP) to py
    }

    private fun updateOrigin(): Pair<Int, Int>? {
        val (pickX, pickY) = pickOrigin() ?: return null
        return (pickX - BUTTON_SIZE - BUTTON_GAP) to pickY
    }

    private fun runClientSync(action: () -> Unit) {
        val mc = Minecraft.getMinecraft()
        if (mc.func_152345_ab()) {
            action()
            return
        }
        mc.func_152344_a(Runnable { action() }).get()
    }

    private fun refreshHostPackScreen(type: ProjectType) {
        val mc = Minecraft.getMinecraft()
        val screen = mc.currentScreen
        when (type) {
            ProjectType.RESOURCE_PACK, ProjectType.AYCY_RESOURCE_PACK -> {
                val parent = (screen as? PackScreenAccessor)?.parentScreen
                if (parent != null) {
                    mc.displayGuiScreen(GuiScreenResourcePacks(parent))
                }
            }
            ProjectType.IRIS_SHADER, ProjectType.OPTIFINE_SHADER -> {
                val parent = screen?.let { IrisHelper.getShaderScreenParent(it) }
                IrisHelper.openShaderPackScreen(parent)
            }
            else -> {}
        }
    }

    private fun safeCanonical(file: File): File {
        return try {
            file.canonicalFile
        } catch (_: Throwable) {
            file.absoluteFile
        }
    }

    private fun sameFile(a: File, b: File): Boolean = safeCanonical(a) == safeCanonical(b)

    private fun supportsPackFilePicking(type: ProjectType): Boolean {
        return when (type) {
            ProjectType.RESOURCE_PACK,
            ProjectType.AYCY_RESOURCE_PACK,
            ProjectType.IRIS_SHADER,
            ProjectType.OPTIFINE_SHADER -> true
            else -> false
        }
    }

    private data class BottomButtons(
        val updateAllX: Int,
        val updateAllW: Int,
        val checkX: Int,
        val checkW: Int,
        val checkLabel: String,
        val closeX: Int,
        val closeW: Int,
        val y: Int,
    )

    private data class PreparedUpdate(val entry: UpdateEntry, val wasEnabled: Boolean)

    private data class UpdateEntry(
        val oldFile: File,
        val version: IVersion,
        @Volatile var status: UpdateStatus = UpdateStatus.PENDING,
    )

    private enum class UpdateStatus {
        PENDING,
        UPDATING,
        UPDATED,
        FAILED,
        CANCELLED,
    }
}
