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

import com.cleanroommc.modularui.api.widget.Interactable
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext
import com.cleanroommc.modularui.theme.WidgetThemeEntry
import com.cleanroommc.modularui.widget.ParentWidget
import com.cleanroommc.modularui.widget.Widget
import dev.dediamondpro.resourcify.VintageResourcify
import net.minecraft.client.Minecraft

/**
 * Paragraph with inline links. Plain text segments are drawn directly by this
 * parent. Each link gets its own child [LinkSpan] widget sized to the segment
 * rect, so hover/click/tooltip all use MUI2's normal widget machinery and
 * we don't need any manual coordinate math.
 */
class MarkdownParagraph(
    runs: List<Run>,
    private val maxWidth: Int,
    private val theme: MarkdownTheme,
) : ParentWidget<MarkdownParagraph>() {

    /** Inline run produced by [MarkdownRenderer]. [styles] is a leading run of `§x` codes. */
    data class Run(val text: String, val styles: String, val linkUrl: String?)

    private data class Segment(
        val text: String,
        val color: Int,
        val x: Int,
        val y: Int,
        val w: Int,
        val linkUrl: String?,
    )

    private val textSegments: List<Segment>
    private val totalHeight: Int

    init {
        widthRel(1f)
        val (segs, h) = layout(runs)
        totalHeight = h
        textSegments = segs.filter { it.linkUrl == null }
        for (s in segs.filter { it.linkUrl != null }) {
            child(LinkSpan(s.text, s.color, s.linkUrl!!).left(s.x).top(s.y).size(s.w, lineHeight()))
        }
    }

    override fun getDefaultHeight(): Int = totalHeight

    private fun lineHeight(): Int = (Minecraft.getMinecraft().fontRenderer?.FONT_HEIGHT ?: 9) + 1

    private fun layout(runs: List<Run>): Pair<List<Segment>, Int> {
        val fr = Minecraft.getMinecraft().fontRenderer ?: return emptyList<Segment>() to 12
        val lineHeight = fr.FONT_HEIGHT + 1
        val spaceWidth = fr.getStringWidth(" ")
        data class Tok(val text: String, val isSpace: Boolean, val styles: String, val color: Int, val linkUrl: String?)
        val toks = mutableListOf<Tok>()
        for (run in runs) {
            val color = if (run.linkUrl != null) theme.link else theme.text
            val parts = run.text.split(' ')
            for ((i, p) in parts.withIndex()) {
                if (i > 0) toks.add(Tok(" ", true, run.styles, color, run.linkUrl))
                if (p.isNotEmpty()) toks.add(Tok(p, false, run.styles, color, run.linkUrl))
            }
        }
        val out = mutableListOf<Segment>()
        var x = 0
        var y = 0
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.isSpace) {
                if (x > 0) x += spaceWidth
                i++
                continue
            }
            val pureW = fr.getStringWidth(t.text)
            if (x + pureW > maxWidth && x > 0) {
                x = 0
                y += lineHeight
                continue
            }
            if (pureW > maxWidth) {
                // Long URL etc. - break across lines at character boundaries.
                var remaining = t.text
                while (remaining.isNotEmpty()) {
                    val fit = greedyFit(fr, remaining, maxWidth)
                    val chunk = remaining.substring(0, fit)
                    val w = fr.getStringWidth(chunk)
                    out.add(Segment(t.styles + chunk, t.color, x, y, w, t.linkUrl))
                    remaining = remaining.substring(fit)
                    if (remaining.isNotEmpty()) {
                        x = 0
                        y += lineHeight
                    } else {
                        x += w
                    }
                }
                i++
                continue
            }
            out.add(Segment(t.styles + t.text, t.color, x, y, pureW, t.linkUrl))
            x += pureW
            i++
        }
        return out to (y + lineHeight)
    }

    private fun greedyFit(fr: net.minecraft.client.gui.FontRenderer, s: String, maxW: Int): Int {
        var lo = 1
        var hi = s.length
        var best = 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (fr.getStringWidth(s.substring(0, mid)) <= maxW) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    override fun draw(context: ModularGuiContext, widgetTheme: WidgetThemeEntry<*>) {
        val fr = Minecraft.getMinecraft().fontRenderer ?: return
        for (s in textSegments) {
            fr.drawString(s.text, s.x, s.y, 0xFF shl 24 or (s.color and 0xFFFFFF), false)
        }
    }

    /** A single clickable link span. Draws its own text; hover/click/tooltip via MUI2. */
    private class LinkSpan(
        private val text: String,
        private val color: Int,
        private val linkUrl: String,
    ) : Widget<LinkSpan>(), Interactable {

        init {
            tooltip().addLine(linkUrl)
        }

        override fun draw(context: ModularGuiContext, widgetTheme: WidgetThemeEntry<*>) {
            val fr = Minecraft.getMinecraft().fontRenderer ?: return
            fr.drawString(text, 0, 0, 0xFF shl 24 or (color and 0xFFFFFF), false)
        }

        override fun onMousePressed(mouseButton: Int): Interactable.Result =
            if (mouseButton == 0) Interactable.Result.ACCEPT else Interactable.Result.IGNORE

        override fun onMouseTapped(mouseButton: Int): Interactable.Result {
            if (mouseButton != 0) return Interactable.Result.IGNORE
            VintageResourcify.LOG.info("MarkdownParagraph link click: {}", linkUrl)
            UrlOpener.openLinkPrompted(linkUrl, Minecraft.getMinecraft().currentScreen)
            return Interactable.Result.SUCCESS
        }
    }
}
