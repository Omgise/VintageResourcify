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
    // GFM-style bare URL autolinking. CommonMark only links explicit
    // <url> / [text](url) forms, but Modrinth bodies routinely paste raw
    // URLs in paragraphs.
    private val BARE_URL = Regex("""\bhttps?://[^\s<>\[\]"']+""")
    private val htmlTag = Regex("<[^>]+>")
    // Catches <img ... src="..." ... alt="..."> with attributes in any order
    // and optional self-close. Modrinth bodies frequently embed bare HTML img
    // tags (with no surrounding markdown image syntax), so converting them up
    // front before the generic htmlTag strip means commonmark can see them
    // as Image nodes.
    private val htmlImg = Regex(
        """<img\b([^>]*?)/?>""",
        RegexOption.IGNORE_CASE
    )
    // Match <a href="...">...<img...></a>. DOTALL because anchors often span
    // multiple lines. Captures: href, then inner content including the img.
    private val htmlLinkedImg = Regex(
        """<a\b[^>]*?href\s*=\s*"([^"]*)"[^>]*>([\s\S]*?)</a>""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val attrSrc = Regex("""src\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val attrAlt = Regex("""alt\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    fun render(markdown: String, widthPx: Int): List<IWidget> {
        // 1. Promote bare HTML <img> tags to markdown ![alt](src) so commonmark
        //    sees them as Image nodes instead of having them stripped silently.
        // 2. Strip remaining HTML tags (links, divs, br, etc.). Surrounding
        //    <a href> wrappers around images are dropped here - link target
        //    is lost, but the image itself survives.
        // 3. Strip leading whitespace per line. The HTML strip leaves tabs
        //    from the original markup, and CommonMark treats any line with
        //    4+ leading spaces or a tab as an indented code block (rendered
        //    in `theme.code` red).
        // First: <a href=X> ... <img src=Y alt=Z> ... </a>  ->  [![Z](Y)](X)
        // CommonMark renders that as a Link node wrapping an Image node, which
        // visit(Paragraph) below picks up so the rendered image becomes
        // clickable.
        val withLinkedImages = htmlLinkedImg.replace(markdown) { m ->
            val href = m.groupValues[1]
            val inner = m.groupValues[2]
            val imgMatch = htmlImg.find(inner) ?: return@replace m.value
            val attrs = imgMatch.groupValues[1]
            val src = attrSrc.find(attrs)?.groupValues?.getOrNull(1) ?: return@replace ""
            val alt = attrAlt.find(attrs)?.groupValues?.getOrNull(1) ?: ""
            "\n\n[![$alt]($src)]($href)\n\n"
        }
        // Second: bare <img> outside an anchor -> plain markdown image.
        val withImages = htmlImg.replace(withLinkedImages) { m ->
            val attrs = m.groupValues[1]
            val src = attrSrc.find(attrs)?.groupValues?.getOrNull(1) ?: return@replace ""
            val alt = attrAlt.find(attrs)?.groupValues?.getOrNull(1) ?: ""
            "\n\n![$alt]($src)\n\n"
        }
        val cleaned = withImages.replace(htmlTag, "")
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
            // If the paragraph contains only image nodes (or links wrapping
            // image nodes), emit each as a block-level rendered image, with
            // the surrounding link's href attached so clicks open the URL.
            val imageEntries = mutableListOf<Pair<Image, String?>>()
            var hasNonImage = false
            var child = paragraph.firstChild
            while (child != null) {
                when (child) {
                    is Image -> imageEntries.add(child to null)
                    is Link -> {
                        // A Link containing exactly one Image (whitespace
                        // allowed) is the [![alt](src)](href) shape.
                        val innerImage = sololImage(child)
                        if (innerImage != null) {
                            imageEntries.add(innerImage to child.destination)
                        } else {
                            hasNonImage = true
                        }
                    }
                    is Text -> if (child.literal.isNotBlank()) hasNonImage = true
                    is SoftLineBreak, is HardLineBreak -> {}
                    else -> hasNonImage = true
                }
                child = child.next
            }
            if (imageEntries.isNotEmpty() && !hasNonImage) {
                imageEntries.forEach { (img, link) ->
                    val url = try { URL(img.destination) } catch (_: Exception) { null }
                    if (url != null) {
                        out += MarkdownImage(url, width, link)
                    } else {
                        val alt = collectInline(img)
                        if (alt.isNotEmpty()) emitLines("[$alt]", theme.muted)
                    }
                }
                spacer()
                return
            }
            if (hasInlineLink(paragraph)) {
                val runs = mutableListOf<MarkdownParagraph.Run>()
                collectRuns(paragraph, "", null, runs)
                if (runs.isNotEmpty()) {
                    out += MarkdownParagraph(runs, width, theme)
                    spacer()
                    return
                }
            }
            val text = collectText(paragraph)
            if (text.isNotBlank()) {
                emitLines(text, theme.text)
                spacer()
            }
        }

        private fun hasInlineLink(node: Node): Boolean {
            var c = node.firstChild
            while (c != null) {
                if (c is Link) {
                    // Image-only links are handled separately above.
                    if (sololImage(c) == null) return true
                }
                if (c is Text && BARE_URL.containsMatchIn(c.literal)) return true
                if (hasInlineLink(c)) return true
                c = c.next
            }
            return false
        }

        private fun appendTextWithAutolinks(
            literal: String,
            styles: String,
            linkUrl: String?,
            out: MutableList<MarkdownParagraph.Run>,
        ) {
            // If we're already inside an explicit Link, don't carve out
            // sub-links - the surrounding URL wins.
            if (linkUrl != null) {
                out += MarkdownParagraph.Run(literal, styles, linkUrl)
                return
            }
            var cursor = 0
            for (m in BARE_URL.findAll(literal)) {
                if (m.range.first > cursor) {
                    out += MarkdownParagraph.Run(literal.substring(cursor, m.range.first), styles, null)
                }
                var url = m.value
                // Strip common sentence-final punctuation so "see foo.com." doesn't
                // resolve to "foo.com." (which 404s) - mirrors GFM behavior.
                while (url.isNotEmpty() && url.last() in ".,;:!?)") {
                    url = url.dropLast(1)
                }
                if (url.isNotEmpty()) {
                    out += MarkdownParagraph.Run(url, styles + "§n", url)
                }
                val tail = m.value.length - url.length
                cursor = m.range.last + 1 - tail
            }
            if (cursor < literal.length) {
                out += MarkdownParagraph.Run(literal.substring(cursor), styles, null)
            }
        }

        private fun collectRuns(
            node: Node,
            styles: String,
            linkUrl: String?,
            out: MutableList<MarkdownParagraph.Run>,
        ) {
            var child = node.firstChild
            while (child != null) {
                when (child) {
                    is Text -> appendTextWithAutolinks(child.literal, styles, linkUrl, out)
                    is StrongEmphasis -> collectRuns(child, styles + "§l", linkUrl, out)
                    is Emphasis -> collectRuns(child, styles + "§o", linkUrl, out)
                    is Code -> out += MarkdownParagraph.Run(child.literal, styles + "§o", linkUrl)
                    is Link -> {
                        val inner = sololImage(child)
                        if (inner != null) {
                            val alt = collectInline(inner)
                            if (alt.isNotEmpty()) {
                                out += MarkdownParagraph.Run("[$alt]", styles + "§n", child.destination)
                            }
                        } else {
                            collectRuns(child, styles + "§n", child.destination, out)
                        }
                    }
                    is Image -> {
                        val alt = collectInline(child)
                        if (alt.isNotEmpty()) out += MarkdownParagraph.Run("[$alt]", styles, linkUrl)
                    }
                    is SoftLineBreak, is HardLineBreak -> out += MarkdownParagraph.Run(" ", styles, linkUrl)
                    is HtmlInline -> { /* stripped earlier */ }
                    else -> collectRuns(child, styles, linkUrl, out)
                }
                child = child.next
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

    /** Return the single Image child of [link] if its only content is one Image (whitespace allowed). */
    private fun sololImage(link: Link): Image? {
        var image: Image? = null
        var child = link.firstChild
        while (child != null) {
            when (child) {
                is Image -> {
                    if (image != null) return null
                    image = child
                }
                is Text -> if (child.literal.isNotBlank()) return null
                is SoftLineBreak, is HardLineBreak -> {}
                else -> return null
            }
            child = child.next
        }
        return image
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
