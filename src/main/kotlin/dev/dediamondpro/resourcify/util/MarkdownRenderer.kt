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

import com.cleanroommc.modularui.api.drawable.IKey
import com.cleanroommc.modularui.api.widget.IWidget
import com.cleanroommc.modularui.widgets.TextWidget
import dev.dediamondpro.resourcify.config.Config
import net.minecraft.client.Minecraft
import net.minecraft.util.EnumChatFormatting
import java.net.URL
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Parses CommonMark + HTML and emits a vertical stack of [TextWidget]s sized
 * to fit the given column width. Per-block color comes from a [MarkdownTheme]
 * (light vs dark via Config.instance.markdownTheme); inline run-styling
 * (bold/italic) still rides on Minecraft `§` codes which the FontRenderer
 * blends with the base color.
 */
object MarkdownRenderer {

    private val parser = Parser.builder().build()
    private val htmlTag = Regex("<[^>]+>")

    fun render(markdown: String, widthPx: Int): List<IWidget> {
        // Strip HTML tags first. Then strip leading whitespace on every line
        // - the HTML strip leaves tabs from the original markup, and
        // CommonMark interprets any line starting with 4+ spaces or a tab as
        // an indented code block, so untouched indented HTML becomes a giant
        // fake code block rendered in `theme.code` (red).
        val cleaned = markdown.replace(htmlTag, "")
            .lineSequence()
            .joinToString("\n") { it.trimStart() }
        val doc = parser.parse(cleaned)
        val theme = MarkdownThemes.named(Config.instance.markdownTheme)
        val out = mutableListOf<IWidget>()
        doc.accept(BlockEmitter(out, widthPx, theme))
        return out
    }

    private class BlockEmitter(
        private val out: MutableList<IWidget>,
        private val width: Int,
        private val theme: MarkdownTheme,
    ) : AbstractVisitor() {

        override fun visit(heading: Heading) {
            val text = collectText(heading)
            // Mark heading runs bold; size hints encode level visually.
            val prefix = "§l"
            emitLines(prefix + text + "§r", theme.heading)
            if (heading.level <= 2) {
                emitLines("-".repeat(80), theme.rule)
            }
            spacer()
        }

        override fun visit(paragraph: Paragraph) {
            // If the paragraph contains only image nodes (with maybe whitespace
            // text between them), emit each as a block-level rendered image.
            // Common case: Modrinth's banner area is a single paragraph wrapping
            // one or more <img>/markdown-image references.
            val images = mutableListOf<Image>()
            var hasNonImage = false
            var child = paragraph.firstChild
            while (child != null) {
                when (child) {
                    is Image -> images.add(child)
                    is Text -> if (child.literal.isNotBlank()) hasNonImage = true
                    is SoftLineBreak, is HardLineBreak -> {}
                    else -> hasNonImage = true
                }
                child = child.next
            }
            if (images.isNotEmpty() && !hasNonImage) {
                images.forEach { img ->
                    val url = try { URL(img.destination) } catch (_: Exception) { null }
                    if (url != null) {
                        out += MarkdownImage(url, width)
                    } else {
                        val alt = collectInline(img)
                        if (alt.isNotEmpty()) emitLines("[$alt]", theme.muted)
                    }
                }
                spacer()
                return
            }
            val text = collectText(paragraph)
            if (text.isNotBlank()) {
                emitLines(text, theme.text)
                spacer()
            }
        }

        override fun visit(bulletList: BulletList) {
            visitChildren(bulletList)
            spacer()
        }

        override fun visit(orderedList: OrderedList) {
            visitChildren(orderedList)
            spacer()
        }

        override fun visit(listItem: ListItem) {
            val text = "  - " + collectText(listItem)
            emitLines(text, theme.text)
        }

        override fun visit(blockQuote: BlockQuote) {
            val text = "> " + collectText(blockQuote)
            emitLines(text, theme.muted)
            spacer()
        }

        override fun visit(fencedCodeBlock: FencedCodeBlock) {
            fencedCodeBlock.literal.lineSequence().forEach { emitLines(it, theme.code) }
            spacer()
        }

        override fun visit(indentedCodeBlock: IndentedCodeBlock) {
            indentedCodeBlock.literal.lineSequence().forEach { emitLines("    " + it, theme.code) }
            spacer()
        }

        override fun visit(thematicBreak: ThematicBreak) {
            emitLines("-".repeat(80), theme.rule)
            spacer()
        }

        override fun visit(htmlBlock: HtmlBlock) {
            // Tags already stripped at the top-level entry point.
        }

        private fun emitLines(text: String, color: Int) {
            val fr = Minecraft.getMinecraft().fontRenderer ?: run {
                out += widget(text, color)
                return
            }
            @Suppress("UNCHECKED_CAST")
            val wrapped = fr.listFormattedStringToWidth(text, width) as List<String>
            (if (wrapped.isEmpty()) listOf(text) else wrapped).forEach { line ->
                out += widget(line, color)
            }
        }

        private fun widget(line: String, color: Int): IWidget {
            // TextWidget.draw reads its own .color field and ignores the IKey's
            // color, so the color must be set on the widget rather than on the
            // wrapped IKey.
            return TextWidget(IKey.str(line))
                .widthRel(1f)
                .color(color)
                .alignment(com.cleanroommc.modularui.utils.Alignment.TopLeft)
        }

        private fun spacer() {
            out += TextWidget(IKey.str("")).widthRel(1f).height(4)
        }
    }

    /** Walk inline children of [node] and concatenate text with §-coded styles. */
    private fun collectText(node: Node): String {
        val sb = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            appendInline(child, sb)
            child = child.next
        }
        return sb.toString()
    }

    private fun appendInline(node: Node, sb: StringBuilder) {
        when (node) {
            is Text -> sb.append(node.literal)
            is StrongEmphasis -> wrap(node, sb, EnumChatFormatting.BOLD)
            is Emphasis -> wrap(node, sb, EnumChatFormatting.ITALIC)
            is Code -> sb.append("§o").append(node.literal).append("§r")
            is Link -> {
                val inner = collectInline(node)
                sb.append("§n").append(inner).append("§r")
            }
            is Image -> {
                val alt = collectInline(node)
                if (alt.isNotEmpty()) sb.append("[").append(alt).append("]")
            }
            is SoftLineBreak, is HardLineBreak -> sb.append(' ')
            is HtmlInline -> { /* tag bodies already stripped */ }
            else -> {
                var child = node.firstChild
                while (child != null) {
                    appendInline(child, sb)
                    child = child.next
                }
            }
        }
    }

    private fun wrap(node: Node, sb: StringBuilder, fmt: EnumChatFormatting) {
        sb.append("§").append(fmt.formattingCode)
        var child = node.firstChild
        while (child != null) {
            appendInline(child, sb)
            child = child.next
        }
        sb.append("§r")
    }

    private fun collectInline(node: Node): String {
        val sb = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            appendInline(child, sb)
            child = child.next
        }
        return sb.toString()
    }
}
