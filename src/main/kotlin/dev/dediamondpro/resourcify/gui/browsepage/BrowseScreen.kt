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
import com.cleanroommc.modularui.widget.ParentWidget
import com.cleanroommc.modularui.widgets.layout.Flow
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget
import dev.dediamondpro.resourcify.VintageResourcify
import dev.dediamondpro.resourcify.config.Config
import dev.dediamondpro.resourcify.gui.CloseButtonDrawable
import dev.dediamondpro.resourcify.gui.CLOSE_BUTTON_RIGHT
import dev.dediamondpro.resourcify.gui.CLOSE_BUTTON_SIZE
import dev.dediamondpro.resourcify.gui.CLOSE_BUTTON_TOP
import dev.dediamondpro.resourcify.gui.closeLikeEscape
import dev.dediamondpro.resourcify.gui.projectpage.ProjectScreen
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.IProject
import dev.dediamondpro.resourcify.services.ProjectType
import dev.dediamondpro.resourcify.services.ServiceRegistry
import dev.dediamondpro.resourcify.util.AsyncIcon
import dev.dediamondpro.resourcify.util.ClientGuiTasks
import dev.dediamondpro.resourcify.util.formatCompact
import dev.dediamondpro.resourcify.util.MultiThreading
import dev.dediamondpro.resourcify.util.ShaderGuiHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.util.EnumChatFormatting
import org.lwjgl.input.Keyboard
import java.io.File

// F-bounded self-types in Kotlin need a concrete subclass.
private class SimpleButton : ButtonWidget<SimpleButton>()
private class SimpleList : ListWidget<IWidget, SimpleList>()
private class Container : ParentWidget<Container>()

// Card layout constants. Sized so a 1080p window shows 6-7 cards above the
// fold and small windows still render readable text.
private const val CARD_HEIGHT = 52
private const val CARD_GAP = 4
private const val ICON_SIZE = 44
private const val INNER_PAD = 6

class BrowseScreen(
    initialType: ProjectType,
    initialFolder: File,
    sourceParent: GuiScreen?,
) : ModularScreen(VintageResourcify.MODID, { _ ->
    // Type, service, sort and folder are all "var" because the user can flip
    // between resource packs and shaders via the top tab row without leaving
    // the screen. Cards capture the current type/folder at construction time;
    // switching tabs clears the results and rebuilds cards with new values.
    var currentType = initialType
    var service = ServiceRegistry.getDefaultService(currentType)
    var defaultSortKey = service.getSortOptions().keys.firstOrNull() ?: ""
    var packsFolder = initialFolder

    val isLight = Config.instance.markdownTheme.equals("light", ignoreCase = true)
    val cardBg = if (isLight) 0x14000000 else 0x28FFFFFF
    val textPrimary = if (isLight) 0xFF1F2328.toInt() else 0xFFF0F6FC.toInt()
    val textSecondary = if (isLight) 0xFF59636E.toInt() else 0xFF9198A1.toInt()
    val textSecondaryHover = if (isLight) 0xFF0B1F33.toInt() else 0xFFEAF2FF.toInt()

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
    val filtersList = SimpleList()
    // Holds the "Filters" + "Minecraft version" labels and the actual
    // DropDownMenu, OUTSIDE of filtersList so the dropdown's open menu
    // can render over the scroll list without ViewPort clipping issues.
    val versionDropdownHolder = Container()
    // Pagination state. Modrinth and CurseForge both return one page per
    // search call (Modrinth: 20 per page); we append additional pages on
    // demand instead of fetching them all up front so an empty/broad query
    // doesn't hammer the API.
    var loadedCount = 0
    var totalCount = 0
    var loadingPage = false
    var currentQuery = ""
    var loadMoreBtn: IWidget? = null
    // Search-as-you-type debounce. The tick-driven onUpdateListener on the
    // search box compares `searchBox.text` against `lastObservedText` each
    // tick; any diff resets `lastKeystrokeMs`. Once DEBOUNCE_MS of stillness
    // has passed and the text differs from `currentQuery` (the last actually-
    // issued search), it fires loadPage. `currentQuery` doubles as the "last
    // fired query" so we don't keep a separate field.
    var lastObservedText = ""
    var lastKeystrokeMs = -1L
    val DEBOUNCE_MS = 300L
    // Filter state, kept generic so any IService.getCategories /
    // getSortOptions shape works - we just round-trip ids back to the
    // service in search(). Different platforms can ship different group
    // names and category lists without UI changes.
    val selectedCategories = mutableSetOf<String>()
    var currentSort = defaultSortKey
    // MC version filter. Default to whatever MC we're running on so the
    // first search shows immediately-relevant results.
    var currentMcVersions: List<String> = listOf(Platform.getMcVersion())
    // Per-pill display names so the dynamic label supplier can show the
    // human-readable name (e.g. "32x") rather than the raw id ("32x" too in
    // some services, but other platforms use enum-style ids).
    val categoryNames = mutableMapOf<String, String>()
    val sortNames = mutableMapOf<String, String>()

    fun loadPage(append: Boolean) {
        if (loadingPage) return
        loadingPage = true
        if (!append) {
            resultsList.removeAll()
            loadedCount = 0
            totalCount = 0
            currentQuery = searchBox.text ?: ""
            // Snap debounce state so the tick observer doesn't re-fire the
            // search we just issued manually (filter pill, platform/version
            // change, Enter, button, type switch, initial load).
            lastObservedText = currentQuery
            lastKeystrokeMs = -1L
            resultsList.child(TextWidget(IKey.str("Searching...")).color(textSecondary))
        } else {
            loadMoreBtn?.let { resultsList.remove(it) }
            loadMoreBtn = null
            val loadingMore = TextWidget(IKey.str("Loading more...")).color(textSecondary)
            resultsList.child(loadingMore)
            loadMoreBtn = loadingMore
        }
        val query = currentQuery
        val offset = loadedCount
        val typeAtRequest = currentType
        val folderAtRequest = packsFolder
        val sortAtRequest = currentSort
        val categoriesAtRequest = selectedCategories.toList()
        val mcVersionsAtRequest = currentMcVersions
        MultiThreading.supplyAsync {
            try {
                service.search(query, sortAtRequest, mcVersionsAtRequest, categoriesAtRequest, offset, typeAtRequest)
            } catch (e: Exception) {
                VintageResourcify.LOG.warn("Search failed", e)
                null
            }
        }.thenAccept { result ->
            Minecraft.getMinecraft().func_152344_a {
                // If the user switched tabs while the request was in flight,
                // ignore the stale results entirely.
                if (typeAtRequest != currentType) return@func_152344_a
                loadingPage = false
                if (!append) resultsList.removeAll()
                else loadMoreBtn?.let { resultsList.remove(it); loadMoreBtn = null }

                val projects: List<IProject> = result?.projects ?: emptyList()
                totalCount = result?.totalCount ?: loadedCount
                if (loadedCount == 0 && projects.isEmpty()) {
                    resultsList.child(TextWidget(IKey.str("No results")).color(textSecondary))
                    return@func_152344_a
                }
                val platformIdAtRequest = service.getPlatformId()
                val mcVersionAtRequest = mcVersionsAtRequest.firstOrNull() ?: Platform.getMcVersion()
                projects.forEach { project ->
                    resultsList.child(buildCard(project, typeAtRequest, folderAtRequest, sourceParent, platformIdAtRequest, mcVersionAtRequest, cardBg, textPrimary, textSecondary, textSecondaryHover))
                }
                loadedCount += projects.size
                if (loadedCount < totalCount && projects.isNotEmpty()) {
                    val btn = SimpleButton()
                        // Right margin clears the ListWidget scrollbar. Cards
                        // don't need this because their content uses
                        // right(INNER_PAD); the button is a single
                        // edge-to-edge overlay and would otherwise paint
                        // under the scrollbar track.
                        .left(0).right(10).height(20).margin(0, 0, 0, CARD_GAP)
                        .overlay(IKey.str("Load more (${loadedCount}/${totalCount})"))
                        .onMousePressed { b -> if (b == 0) { loadPage(append = true); true } else false }
                    resultsList.child(btn)
                    loadMoreBtn = btn
                }
            }
        }
    }

    // Type switcher: two buttons, one per supported pack type. Hidden tab
    // for shaders when Iris/Angelica isn't loaded. Width is set wide enough
    // that "Resource Packs" fits on a single line at default font scale.
    val shadersAvailable = ShaderGuiHelper.isPresent()
    val packsTab = SimpleButton().size(112, 18)
    val shadersTab = SimpleButton().size(112, 18)

    fun tabLabel(label: String, selected: Boolean): String =
        if (selected) "§f§n$label§r" else "§7$label§r"

    fun refreshTabLabels() {
        packsTab.overlay(IKey.str(tabLabel("Resource Packs", currentType == ProjectType.RESOURCE_PACK)))
        shadersTab.overlay(IKey.str(tabLabel("Shaders", currentType == ProjectType.IRIS_SHADER)))
    }
    refreshTabLabels()

    // Compact pill: a centered overlay label that reflects selection state.
    // We localize at display time so services can hand back raw i18n keys
    // (Modrinth returns "resourcify.browse.sort.relevance" and friends).
    // IKey.dynamic re-evaluates each frame, so toggling state updates the
    // visible label without rebuilding the widget.
    fun filterPill(
        id: String, displayName: String,
        isSelected: () -> Boolean,
        onToggle: () -> Unit,
    ): SimpleButton {
        // Right margin clears the filtersList scrollbar (~6px); without it,
        // the pill's right edge paints under the scroll track.
        val btn = SimpleButton().left(0).right(7).height(12).margin(0, 0, 1, 1)
        btn.overlay(IKey.dynamic {
            val resolved = dev.dediamondpro.resourcify.util.localizeOrDefault(displayName, displayName)
            if (isSelected()) "§f§l$resolved§r" else "§7$resolved§r"
        })
        btn.onMousePressed { b -> if (b == 0) { onToggle(); loadPage(append = false); true } else false }
        return btn
    }

    // Re-fetch categories from the current service for the current type
    // and rebuild the filter sidebar. The sidebar is fully driven by the
    // service contract (getCategories + getSortOptions) so adding a new
    // platform requires no UI changes.
    fun rebuildFilters() {
        categoryNames.clear()
        sortNames.clear()
        filtersList.removeAll()
        versionDropdownHolder.removeAll()
        versionDropdownHolder.child(
            TextWidget(IKey.str("§lFilters§r")).color(textPrimary).top(0).left(0).widthRel(1f).height(10)
        )

        // Platform dropdown: same shape as the MC-version one below. Picking
        // a new platform throws away the current filter state (its categories
        // and sort keys are platform-specific) and reloads.
        versionDropdownHolder.child(
            TextWidget(IKey.str("§lPlatform§r")).color(textPrimary).top(12).left(0).widthRel(1f).height(10)
        )
        val availableServices = ServiceRegistry.getServices(currentType)
        val platformPopup = SimpleList()
            .top(40).left(0).widthRel(1f).height(availableServices.size * 12 + 4)
            .background(com.cleanroommc.modularui.drawable.Rectangle().color(0xFF1F2328.toInt()))
        platformPopup.setEnabled(false)
        val platformButton = SimpleButton().widthRel(1f).height(14).top(24).left(0)
        platformButton.overlay(IKey.dynamic { "${service.getName()}  v" })
        platformButton.onMousePressed { b ->
            if (b == 0) {
                platformPopup.setEnabled(!platformPopup.isEnabled)
                val panel = filtersList.panel
                if (panel != null && panel.isValid) {
                    com.cleanroommc.modularui.widget.WidgetTree.resizeInternal(panel.resizer(), false)
                }
                true
            } else false
        }
        for (svc in availableServices) {
            val opt = SimpleButton().widthRel(1f).height(12)
            opt.overlay(IKey.dynamic {
                val n = svc.getName()
                if (svc === service) "§f§l$n§r" else "§7$n§r"
            })
            opt.onMousePressed { b ->
                if (b == 0) {
                    if (svc !== service) {
                        service = svc
                        defaultSortKey = service.getSortOptions().keys.firstOrNull() ?: ""
                        currentSort = defaultSortKey
                        selectedCategories.clear()
                        currentMcVersions = listOf(Platform.getMcVersion())
                        platformPopup.setEnabled(false)
                        rebuildFilters()
                        filtersList.scrollArea.scrollY?.scrollTo(filtersList.scrollArea, 0)
                        loadPage(append = false)
                    } else {
                        platformPopup.setEnabled(false)
                        val panel = filtersList.panel
                        if (panel != null && panel.isValid) {
                            com.cleanroommc.modularui.widget.WidgetTree.resizeInternal(panel.resizer(), false)
                        }
                    }
                    true
                } else false
            }
            platformPopup.child(opt)
        }
        versionDropdownHolder.child(platformButton)
        versionDropdownHolder.child(platformPopup)

        versionDropdownHolder.child(
            TextWidget(IKey.str("§lMinecraft version§r")).color(textPrimary).top(42).left(0).widthRel(1f).height(10)
        )

        // Custom dropdown: a label button + a hidden ListWidget popup. We
        // roll our own rather than use MUI2's DropDownMenu because the
        // deprecated DropDownMenu's internal wrapper subclasses ScrollWidget
        // with null scroll data and never gains real scroll behaviour, so
        // any version list past ~10 entries simply gets clipped. ListWidget
        // does initialise scroll data in onInit, so this popup scrolls.
        val versionPlaceholder = TextWidget(IKey.str("§7Loading versions...§r")).color(textSecondary)
            .top(54).left(0).widthRel(1f).height(14)
        versionDropdownHolder.child(versionPlaceholder)
        val typeAtVersions = currentType
        val serviceAtVersions = service
        service.getMinecraftVersions().thenAccept { versions ->
            Minecraft.getMinecraft().func_152344_a {
                if (typeAtVersions != currentType || serviceAtVersions !== service) return@func_152344_a
                val ordered = LinkedHashMap<String, String>()
                val mcv = Platform.getMcVersion()
                if (versions.containsKey(mcv)) ordered[mcv] = versions[mcv] ?: mcv
                versions.forEach { (id, name) -> ordered.putIfAbsent(id, name) }
                // Keep the dropdown's display in sync with whatever the
                // search uses. If the user picked a non-default version
                // and the screen rebuilds (type switch resets to default,
                // but other rebuild paths might preserve it), the visible
                // button should match the filter state.
                val preselected = currentMcVersions.firstOrNull()?.takeIf { ordered.containsKey(it) }
                val selectedId = arrayOf(preselected ?: if (ordered.containsKey(mcv)) mcv else ordered.keys.first())
                val popup = SimpleList()
                    .top(70).left(0).widthRel(1f).height(135)
                    .background(com.cleanroommc.modularui.drawable.Rectangle().color(0xFF1F2328.toInt()))
                popup.setEnabled(false)
                val button = SimpleButton().widthRel(1f).height(14).top(54).left(0)
                button.overlay(IKey.dynamic { "${ordered[selectedId[0]] ?: selectedId[0]}  v" })
                button.onMousePressed { b ->
                    if (b == 0) {
                        popup.setEnabled(!popup.isEnabled)
                        val panel = filtersList.panel
                        if (panel != null && panel.isValid) {
                            com.cleanroommc.modularui.widget.WidgetTree.resizeInternal(panel.resizer(), false)
                        }
                        true
                    } else false
                }
                for ((id, name) in ordered) {
                    val opt = SimpleButton().widthRel(1f).height(12)
                    opt.overlay(IKey.dynamic { if (selectedId[0] == id) "§f§l$name§r" else "§7$name§r" })
                    opt.onMousePressed { b ->
                        if (b == 0) {
                            selectedId[0] = id
                            currentMcVersions = listOf(id)
                            popup.setEnabled(false)
                            val panel = filtersList.panel
                            if (panel != null && panel.isValid) {
                                com.cleanroommc.modularui.widget.WidgetTree.resizeInternal(panel.resizer(), false)
                            }
                            loadPage(append = false)
                            true
                        } else false
                    }
                    popup.child(opt)
                }
                versionDropdownHolder.remove(versionPlaceholder)
                versionDropdownHolder.child(button)
                versionDropdownHolder.child(popup)
                // The version button + popup were added after the platform
                // popup, so they now paint on top of it (last child = topmost).
                // Pull the platform popup back to the end so its open menu
                // overlays the version dropdown instead of disappearing behind
                // it.
                versionDropdownHolder.remove(platformPopup)
                versionDropdownHolder.child(platformPopup)
                val panel = filtersList.panel
                if (panel != null && panel.isValid) {
                    com.cleanroommc.modularui.widget.WidgetTree.resizeInternal(panel.resizer(), false)
                }
            }
        }

        // Sort group: synchronous list from the service.
        val sortOptions = service.getSortOptions()
        if (sortOptions.isNotEmpty()) {
            filtersList.child(TextWidget(IKey.str("§lSort by§r")).color(textPrimary).margin(0, 2, 0, 2))
            for ((id, label) in sortOptions) {
                sortNames[id] = label
                val pill = filterPill(id, label, { id == currentSort }) { currentSort = id }
                filtersList.child(pill)
            }
        }

        // Categories: async because services may need a network round-trip
        // (Modrinth caches the tag dump). Show a loading line until ready.
        val loadingMarker = TextWidget(IKey.str("§7Loading categories...§r")).color(textSecondary).margin(0, 4, 0, 2)
        filtersList.child(loadingMarker)
        val typeAtRequest = currentType
        val serviceAtRequest = service
        service.getCategories(currentType).thenAccept { groups ->
            Minecraft.getMinecraft().func_152344_a {
                if (typeAtRequest != currentType || serviceAtRequest !== service) return@func_152344_a
                filtersList.remove(loadingMarker)
                if (groups.isEmpty()) {
                    filtersList.child(TextWidget(IKey.str("§7(no categories)§r")).color(textSecondary))
                    return@func_152344_a
                }
                for ((groupName, members) in groups) {
                    if (members.isEmpty()) continue
                    val header = TextWidget(IKey.str("§l${groupName.replaceFirstChar { it.uppercase() }}§r"))
                        .color(textPrimary).margin(0, 6, 0, 2)
                    filtersList.child(header)
                    for ((id, displayName) in members) {
                        categoryNames[id] = displayName
                        val pill = filterPill(id, displayName, { id in selectedCategories }) {
                            if (id in selectedCategories) selectedCategories.remove(id)
                            else selectedCategories.add(id)
                        }
                        filtersList.child(pill)
                    }
                }
            }
        }
    }

    fun switchType(t: ProjectType) {
        if (t == currentType) return
        currentType = t
        service = ServiceRegistry.getDefaultService(t)
        defaultSortKey = service.getSortOptions().keys.firstOrNull() ?: ""
        currentSort = defaultSortKey
        selectedCategories.clear()
        // Fully reset the left panel: same default MC version, empty search
        // query, no saved category selections, default sort. Without these
        // the previous type's filters silently bleed into the new view
        // even though the visible pills change.
        currentMcVersions = listOf(Platform.getMcVersion())
        searchBox.text = ""
        packsFolder = when (t) {
            ProjectType.IRIS_SHADER -> ShaderGuiHelper.getShaderpacksFolder()
            else -> Minecraft.getMinecraft().resourcePackRepository.dirResourcepacks
        }
        refreshTabLabels()
        rebuildFilters()
        // Snap the sidebar back to top so the new type's filters are
        // visible from the first option (rather than wherever the user
        // had scrolled in the old type's category list).
        filtersList.scrollArea.scrollY?.scrollTo(filtersList.scrollArea, 0)
        loadPage(append = false)
    }
    packsTab.onMousePressed { b -> if (b == 0) { switchType(ProjectType.RESOURCE_PACK); true } else false }
    shadersTab.onMousePressed { b -> if (b == 0) { switchType(ProjectType.IRIS_SHADER); true } else false }

    val tabRow = Flow.row().top(6).left(10).height(18)
        .child(packsTab.margin(0, 4, 0, 0))
        .apply { if (shadersAvailable) child(shadersTab) }

    val closeButton = SimpleButton()
        .top(CLOSE_BUTTON_TOP).right(CLOSE_BUTTON_RIGHT).size(CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE)
        .disableThemeBackground(true)
        .disableHoverThemeBackground(true)
        .overlay(CloseButtonDrawable(false))
        .hoverOverlay(CloseButtonDrawable(true))
        .onMousePressed { b ->
            if (b == 0) {
                closeLikeEscape()
                true
            } else false
        }
    closeButton.tooltip().addLine("Close")

    // Search fires automatically on idle via the tick-driven debounce below;
    // Enter still triggers an immediate search through searchBox's key handler.
    val searchRow = Flow.row().top(28).left(10).right(10).height(16)
        .child(searchBox.heightRel(1f).widthRel(1f))

    triggerSearch = { loadPage(append = false) }
    // Search-as-you-type. Runs on every client tick (~20Hz) via MUI2's per-
    // widget onUpdate hook. We rely on currentQuery being kept in sync inside
    // loadPage(!append), so a fire only happens when the visible text has
    // actually diverged from what was last searched. loadingPage guard prevents
    // overlapping requests; if a previous load is still pending, we just try
    // again on the next tick.
    searchBox.onUpdateListener { _ ->
        val current = searchBox.text ?: ""
        if (current != lastObservedText) {
            lastObservedText = current
            lastKeystrokeMs = System.currentTimeMillis()
        } else if (lastKeystrokeMs >= 0 && current != currentQuery
            && !loadingPage
            && System.currentTimeMillis() - lastKeystrokeMs >= DEBOUNCE_MS) {
            loadPage(append = false)
        }
    }
    // versionDropdownHolder sits at the top of the sidebar slot. The
    // dropdown's open menu paints downward and over the categories list;
    // by keeping it OUTSIDE the scrollable filtersList we avoid scroll-
    // viewport clipping. filtersList starts below it.
    versionDropdownHolder.top(50).left(10).width(120).height(72)
    filtersList.top(124).left(10).width(120).bottom(10)
    resultsList.top(50).left(140).right(10).bottom(10)
        .child(TextWidget(IKey.str("Loading...")).color(textSecondary))
    rebuildFilters()
    loadPage(append = false)

    ModularPanel.defaultPanel("vintage-resourcify-browse")
        .full()
        .child(tabRow)
        .child(searchRow)
        .child(filtersList)
        .child(resultsList)
        // versionDropdownHolder is declared LAST so its open dropdown menu
        // paints over filtersList instead of being hidden behind it.
        .child(versionDropdownHolder)
        .child(closeButton)
})

/** Project result card: thumbnail + title + author + summary, clickable. */
private fun buildCard(
    project: IProject,
    type: ProjectType,
    packsFolder: File,
    sourceParent: GuiScreen?,
    platformId: String,
    initialMcVersion: String,
    cardBg: Int,
    textPrimary: Int,
    textSecondary: Int,
    textSecondaryHover: Int,
): IWidget {
    val card = SimpleButton()
    val click = { btn: Int ->
        if (btn == 0) {
            VintageResourcify.LOG.info(
                "BrowseScreen card clicked: {} (id={})",
                project.getName(), project.getId(),
            )
            // ClientGUI.open immediately calls displayGuiScreen. Opening from
            // inside MUI2's mouse dispatch can leave the new wrapper visible
            // while ClientScreenHandler.currentScreen is invalidated, so do a
            // real tick defer instead of Minecraft.func_152344_a (which runs
            // immediately when already on the client thread).
            val clickedFrom = Minecraft.getMinecraft().currentScreen
            ClientGuiTasks.runNextClientTick {
                if (Minecraft.getMinecraft().currentScreen !== clickedFrom) {
                    VintageResourcify.LOG.info(
                        "Skipping stale project open for {} because current screen changed",
                        project.getId(),
                    )
                    return@runNextClientTick
                }
                val mcBefore = Minecraft.getMinecraft().currentScreen
                val muiBefore = try {
                    val cls = Class.forName("com.cleanroommc.modularui.screen.ClientScreenHandler")
                    val f = cls.getDeclaredField("currentScreen")
                    f.isAccessible = true
                    f.get(null)
                } catch (e: Throwable) {
                    "<reflection failed: ${e.message}>"
                }
                VintageResourcify.LOG.info(
                    "Pre-open mc.currentScreen={}@{} mui.currentScreen={}@{}",
                    mcBefore?.javaClass?.simpleName,
                    mcBefore?.let { Integer.toHexString(System.identityHashCode(it)) },
                    muiBefore?.javaClass?.simpleName,
                    muiBefore?.let { Integer.toHexString(System.identityHashCode(it)) },
                )
                try {
                    val newScreen = ProjectScreen(project, type, packsFolder, sourceParent, platformId, initialMcVersion)
                    // Go through MUI2's official entry point so UISettings,
                    // recipe-viewer integration, etc. get wired up. Calling
                    // displayGuiScreen on a hand-constructed wrapper skips
                    // that and crashes on the first mouse press (NEI ghost
                    // ingredient check reads UISettings).
                    ClientGUI.open(newScreen)
                    val mcAfter = Minecraft.getMinecraft().currentScreen
                    VintageResourcify.LOG.info(
                        "ClientGUI.open returned cleanly. newProjectScreen={} currentScreen now={}@{}",
                        Integer.toHexString(System.identityHashCode(newScreen)),
                        mcAfter?.javaClass?.simpleName,
                        mcAfter?.let { Integer.toHexString(System.identityHashCode(it)) },
                    )
                } catch (t: Throwable) {
                    VintageResourcify.LOG.error("Opening ProjectScreen threw", t)
                }
            }
            true
        } else false
    }
    val textLeft = INNER_PAD + ICON_SIZE + 10
    // Truncate to two visual lines at the card's actual rendered width.
    // listFormattedStringToWidth wraps to the column width; if it returns
    // more than 2 lines, keep the first two and ellipsize the second.
    val rawSummary = project.getSummary()
    val mc = Minecraft.getMinecraft()
    val sr = ScaledResolution(mc, mc.displayWidth, mc.displayHeight)
    // Layout budget: panel uses .full(); filter sidebar takes left(10) +
    // 120, then 10px gap, then the results list goes to right(10). Inside
    // the list, scrollbar ~12, card uses INNER_PAD on each side, icon
    // column + 10px gap before the text.
    val resultsListWidth = sr.scaledWidth - (10 + 120 + 10) - 10
    val summaryW = resultsListWidth - 12 - INNER_PAD * 2 - ICON_SIZE - 10
    val fr = mc.fontRenderer
    val summary = if (fr != null) {
        @Suppress("UNCHECKED_CAST")
        val wrapped = fr.listFormattedStringToWidth(rawSummary, summaryW.coerceAtLeast(40)) as List<String>
        if (wrapped.size <= 2) {
            wrapped.joinToString(" ")
        } else {
            val first = wrapped[0]
            val secondTrimmed = fr.trimStringToWidth(
                wrapped[1] + " " + wrapped[2],
                (summaryW - fr.getStringWidth("...")).coerceAtLeast(20),
            )
            "$first $secondTrimmed..."
        }
    } else {
        if (rawSummary.length > 130) rawSummary.take(127) + "..." else rawSummary
    }
    // Title: hard single-line. .height(10) doesn't stop TextWidget from
    // wrapping a long title onto a second line that collides with the author
    // row, so trim explicitly. Bold glyphs are ~1px wider, so we measure
    // against the bold-formatted string.
    val rawTitle = project.getName()
    val title = if (fr != null && fr.getStringWidth("§l$rawTitle") > summaryW) {
        fr.trimStringToWidth("§l$rawTitle", (summaryW - fr.getStringWidth("...")).coerceAtLeast(20))
            .removePrefix("§l") + "..."
    } else rawTitle
    // Author + downloads: same hard single-line treatment as the title. The
    // row uses a dynamic color so it stays readable on the hovered card.
    val rawAuthor = "by ${project.getAuthor()}  •  ${project.getDownloads().formatCompact()} downloads"
    val authorLine = if (fr != null && fr.getStringWidth(rawAuthor) > summaryW) {
        fr.trimStringToWidth(rawAuthor, (summaryW - fr.getStringWidth("...")).coerceAtLeast(20)) + "..."
    } else rawAuthor
    // Vertically center the icon and right-side text column inside the
    // CARD_HEIGHT box. The text column is 36px tall (title 10 + author 9 +
    // summary 17), centered.
    val iconTop = (CARD_HEIGHT - ICON_SIZE) / 2
    val textBlockH = 36
    val textTop = (CARD_HEIGHT - textBlockH) / 2
    val content = Container()
        .widthRel(1f).heightRel(1f)
        .child(
            AsyncIcon(project.getIconUrl(), ICON_SIZE)
                .top(iconTop).left(INNER_PAD)
        )
        .child(
            TextWidget(IKey.str(title).style(EnumChatFormatting.BOLD))
                .top(textTop).left(textLeft).right(INNER_PAD).height(10)
                .color(textPrimary)
                .alignment(com.cleanroommc.modularui.utils.Alignment.CenterLeft)
        )
        .child(
            TextWidget(IKey.str(authorLine))
                .top(textTop + 11).left(textLeft).right(INNER_PAD).height(9)
                .color { if (card.isHovering) textSecondaryHover else textSecondary }
                .alignment(com.cleanroommc.modularui.utils.Alignment.CenterLeft)
        )
        .child(
            TextWidget(IKey.str(summary))
                .top(textTop + 22).left(textLeft).right(INNER_PAD).height(textBlockH - 22)
                .color(textPrimary)
                .alignment(com.cleanroommc.modularui.utils.Alignment.TopLeft)
        )

    return card
        .widthRel(1f).height(CARD_HEIGHT).margin(0, 0, 0, CARD_GAP)
        .background(Rectangle().color(cardBg))
        .overlay()
        .child(content)
        .onMousePressed(click)
}
