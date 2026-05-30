package dev.dediamondpro.resourcify.gui

import com.cleanroommc.modularui.api.drawable.IDrawable
import com.cleanroommc.modularui.api.widget.IWidget
import com.cleanroommc.modularui.drawable.Rectangle
import com.cleanroommc.modularui.screen.ModularPanel
import com.cleanroommc.modularui.screen.ModularScreen
import com.cleanroommc.modularui.screen.viewport.GuiContext
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext
import com.cleanroommc.modularui.theme.WidgetTheme
import com.cleanroommc.modularui.utils.Alignment
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData
import com.cleanroommc.modularui.widget.Widget
import com.cleanroommc.modularui.widgets.ButtonWidget
import com.cleanroommc.modularui.widgets.ListWidget
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget
import dev.dediamondpro.resourcify.VintageResourcify
import net.minecraft.client.gui.Gui
import org.lwjgl.input.Keyboard
import org.fentanylsolutions.fentlib.gui.sodiumgui.SodiumGuiTheme
import java.util.function.Function

/**
 * MUI-facing facade for the Sodium-like primitives in FentLib.
 *
 * Keep screen code going through this object rather than hardcoding colors or
 * FentLib classes directly. That leaves a small surface to move upstream once
 * the look is settled.
 */
object ResourcifyStyle {
    const val SELECTOR_ITEM_IDLE = -0x1000000
    const val SELECTOR_ITEM_HOVER = -0x00E6E6E6
    const val SODIUM_SCROLLBAR_WIDTH = 7
    const val SODIUM_SCROLLBAR_TRACK = 0x96323232.toInt()
    const val SODIUM_SCROLLBAR_THUMB = 0x96646464.toInt()

    data class Palette(
        val accent: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textSecondaryHover: Int,
        val screenScrim: Int,
        val panel: Int,
        val panelRaised: Int,
        val panelInset: Int,
        val popup: Int,
        val field: Int,
        val fieldHover: Int,
        val buttonIdle: Int,
        val buttonHover: Int,
        val buttonDisabled: Int,
        val rowIdle: Int,
        val rowHover: Int,
        val rowSelected: Int,
        val rowDisabled: Int,
        val overlayScrim: Int,
        val overlayScrimStrong: Int,
        val progressBackground: Int,
        val progressTrack: Int,
        val progressFill: Int,
    )

    fun palette(themeName: String): Palette {
        val sodium = SodiumGuiTheme.getDefault()
        val accent = ensureAlpha(sodium.accentColor)
        return Palette(
            accent = accent,
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFFB7BEC8.toInt(),
            textSecondaryHover = 0xFFFFFFFF.toInt(),
            screenScrim = 0x00000000,
            panel = 0x90000000.toInt(),
            panelRaised = 0xD8000000.toInt(),
            panelInset = 0x60000000,
            popup = 0xEA000000.toInt(),
            field = 0x90000000.toInt(),
            fieldHover = 0xB0000000.toInt(),
            buttonIdle = 0x90000000.toInt(),
            buttonHover = 0xE0000000.toInt(),
            buttonDisabled = 0x60000000,
            rowIdle = 0x40000000,
            rowHover = 0xE0000000.toInt(),
            rowSelected = 0x90000000.toInt(),
            rowDisabled = 0x30000000,
            overlayScrim = 0xA0000000.toInt(),
            overlayScrimStrong = 0xEA000000.toInt(),
            progressBackground = 0x60000000,
            progressTrack = 0x90000000.toInt(),
            progressFill = accent,
        )
    }

    fun rect(color: Int): IDrawable = Rectangle().color(color)

    fun flat(color: Int, selected: () -> Boolean = { false }, accent: Int = 0): IDrawable = object : IDrawable {
        override fun draw(context: GuiContext, x: Int, y: Int, width: Int, height: Int, widgetTheme: WidgetTheme) {
            Gui.drawRect(x, y, x + width, y + height, color)
            if (selected()) {
                Gui.drawRect(x, y + height - 1, x + width, y + height, accent)
            }
        }
    }

    fun dynamicRect(color: () -> Int): IDrawable = object : IDrawable {
        override fun draw(context: GuiContext, x: Int, y: Int, width: Int, height: Int, widgetTheme: WidgetTheme) {
            Gui.drawRect(x, y, x + width, y + height, color())
        }
    }

    private fun ensureAlpha(color: Int): Int {
        return if (color and 0xFF000000.toInt() == 0) color or 0xFF000000.toInt() else color
    }
}

open class ResourcifyScreen(
    mainPanelCreator: Function<ModularGuiContext, ModularPanel>,
) : ModularScreen(VintageResourcify.MODID, mainPanelCreator) {
    override fun onKeyPressed(typedChar: Char, keyCode: Int): Boolean {
        if (keyCode != Keyboard.KEY_ESCAPE) {
            return super.onKeyPressed(typedChar, keyCode)
        }

        if (!getContext().isFocused && super.onKeyPressed(typedChar, keyCode)) {
            return true
        }

        closeLikeEscape()
        return true
    }

    override fun drawScreen() {
        getScreenWrapper().getGuiScreen().drawDefaultBackground()
        super.drawScreen()
    }
}

fun <W : Widget<W>> W.sodiumTransparent(): W {
    return disableThemeBackground(true)
        .disableHoverThemeBackground(true)
}

fun <W : Widget<W>> W.sodiumSurface(color: Int): W {
    return background(ResourcifyStyle.rect(color))
        .disableHoverThemeBackground(true)
}

fun <I : IWidget, W : ListWidget<I, W>> W.sodiumScrollbars(): W {
    scrollDirection(
        VerticalScrollData(false, ResourcifyStyle.SODIUM_SCROLLBAR_WIDTH)
            .apply { texture(ResourcifyStyle.rect(ResourcifyStyle.SODIUM_SCROLLBAR_THUMB)) }
    )
    crossAxisAlignment(Alignment.CrossAxis.START)
    scrollArea.scrollBarBackgroundColor = ResourcifyStyle.SODIUM_SCROLLBAR_TRACK
    showScrollShadows(false)
    return this
}

fun <W : ButtonWidget<W>> W.sodiumButton(palette: ResourcifyStyle.Palette): W {
    return background(ResourcifyStyle.rect(palette.buttonIdle))
        .hoverBackground(ResourcifyStyle.rect(palette.buttonHover))
}

fun <W : ButtonWidget<W>> W.sodiumButton(palette: ResourcifyStyle.Palette, selected: () -> Boolean): W {
    return background(ResourcifyStyle.flat(palette.buttonIdle, selected, palette.accent))
        .hoverBackground(ResourcifyStyle.flat(palette.buttonHover, selected, palette.accent))
}

fun <W : ButtonWidget<W>> W.sodiumIconButton(palette: ResourcifyStyle.Palette): W {
    return disableThemeBackground(true)
        .disableHoverThemeBackground(true)
}

fun <W : ButtonWidget<W>> W.sodiumSelectorItem(palette: ResourcifyStyle.Palette, selected: () -> Boolean): W {
    return background(ResourcifyStyle.flat(ResourcifyStyle.SELECTOR_ITEM_IDLE, selected, palette.accent))
        .hoverBackground(ResourcifyStyle.flat(ResourcifyStyle.SELECTOR_ITEM_HOVER, selected, palette.accent))
}

fun <W : TextFieldWidget> W.sodiumTextField(palette: ResourcifyStyle.Palette): W {
    background(ResourcifyStyle.rect(palette.field))
    hoverBackground(ResourcifyStyle.rect(palette.fieldHover))
    setTextColor(palette.textPrimary)
    hintColor(palette.textSecondary)
    setMarkedColor(palette.accent)
    return this
}
