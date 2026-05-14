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

package dev.dediamondpro.resourcify.util

import net.minecraft.client.resources.I18n
import java.awt.Color
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

fun Int.formatCompact(): String {
    if (this < 1_000) return toString()
    val (value, suffix) = when {
        this < 1_000_000 -> this / 1_000.0 to "K"
        this < 1_000_000_000 -> this / 1_000_000.0 to "M"
        else -> this / 1_000_000_000.0 to "B"
    }
    val str = if (value >= 100) "%.0f".format(value)
    else if (value >= 10) "%.1f".format(value).removeSuffix(".0")
    else "%.2f".format(value).removeSuffix("0").removeSuffix("0").removeSuffix(".")
    return "$str$suffix"
}

fun String.capitalizeAll(): String {
    return this.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
        .split("-").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
}

@JvmName("localizeExtension")
fun String.localize(vararg parameters: Any): String {
    return I18n.format(this, *parameters)
}

fun localize(key: String, vararg parameters: Any): String {
    return I18n.format(key, *parameters)
}

@JvmName("localizeOrDefaultExtension")
fun String.localizeOrDefault(default: String, vararg parameters: Any): String {
    val formatted = I18n.format(this, *parameters)
    return if (formatted == this) default.format(*parameters) else formatted
}

fun localizeOrDefault(key: String, default: String, vararg parameters: Any): String {
    val formatted = I18n.format(key, *parameters)
    return if (formatted == key) default.format(*parameters) else formatted
}

object Utils {

    // drawTexture(...) lived here in upstream; lives in the MUI2 rewrite now.

    fun getSha1(file: File): String? {
        try {
            FileInputStream(file).use { it ->
                val digest: MessageDigest = MessageDigest.getInstance("SHA-1")
                val buffer = ByteArray(1024)
                var count: Int
                while (it.read(buffer).also { count = it } != -1) {
                    digest.update(buffer, 0, count)
                }
                val digested: ByteArray = digest.digest()
                val sb = StringBuilder()
                for (b in digested) {
                    sb.append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
                }
                return sb.toString()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }
        return null
    }

    fun getShadowColor(color: Color): Color {
        val rgb = color.rgb
        return Color(rgb and 16579836 shr 2 or (rgb and -16777216))
    }

    fun incrementFileName(fileName: String): String {
        val regex = """\((\d+)\)(\.\w+)$""".toRegex()
        val matchResult = regex.find(fileName)

        return if (matchResult != null) {
            val currentNumber = matchResult.groupValues[1].toInt()
            val extension = matchResult.groupValues[2]
            fileName.replace(regex, "(${currentNumber + 1})$extension")
        } else {
            val dotIndex = fileName.lastIndexOf('.')
            if (dotIndex != -1) {
                fileName.substring(0, dotIndex) + " (1)." + fileName.substring(dotIndex + 1)
            } else {
                "$fileName (1)"
            }
        }
    }
}
