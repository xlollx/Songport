package com.xlollx.songport.server

import org.w3c.dom.Element
import java.io.InputStream
import java.util.IllegalFormatException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's texts, read from its own `res/values…/strings.xml` (copied into the server's resources
 * at build time): strings, string arrays and plurals, in the language asked for, with English
 * underneath for whatever a translation lacks. Formats are Android's (`%1$s`, `%d`), which are
 * Java's too.
 */
class Strings(
    private val strings: Map<String, String>,
    private val arrays: Map<String, List<String>> = emptyMap(),
    private val plurals: Map<String, Map<String, String>> = emptyMap(),
    val locale: Locale = Locale.ENGLISH,
) {
    /** The text named [name], formatted with [args]; the name itself when there is no such text. */
    fun text(name: String, vararg args: Any): String {
        val raw = strings[name] ?: return name
        return format(raw, args)
    }

    fun has(name: String) = name in strings

    fun array(name: String): List<String> = arrays[name].orEmpty()

    /** The plural form of [name] for [count] (English rules: "one" for 1, "other" otherwise). */
    fun plural(name: String, count: Int, vararg args: Any): String {
        val forms = plurals[name] ?: return name
        val raw = (if (count == 1) forms["one"] else null) ?: forms["other"] ?: forms.values.firstOrNull() ?: return name
        return format(raw, args)
    }

    private fun format(raw: String, args: Array<out Any>): String =
        if (args.isEmpty()) raw else try { String.format(locale, raw, *args) } catch (e: IllegalFormatException) { raw }

    companion object {
        /** Where the copied resources are on the classpath. */
        private const val ROOT = "/res"

        /**
         * The texts for [tag] ("it", "pt-BR", "zh-CN"...; blank = English), from the classpath. Region
         * folders are tried first ("values-pt-rBR"), then the language alone ("values-zh").
         */
        fun load(tag: String?): Strings {
            val base = parse(Strings::class.java.getResourceAsStream("$ROOT/values/strings.xml") ?: return Strings(emptyMap()))
            val locale = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) } ?: Locale.ENGLISH
            val overlay = folders(locale).firstNotNullOfOrNull { f ->
                Strings::class.java.getResourceAsStream("$ROOT/$f/strings.xml")?.let { parse(it) }
            }
            return if (overlay == null) Strings(base.strings, base.arrays, base.plurals, locale)
            else Strings(base.strings + overlay.strings, base.arrays + overlay.arrays, base.plurals + overlay.plurals, locale)
        }

        /** Android's resource folder names for a locale, most specific first. */
        fun folders(locale: Locale): List<String> {
            val lang = locale.language.ifEmpty { return emptyList() }
            if (lang == "en") return emptyList()
            return listOfNotNull(locale.country.takeIf { it.isNotEmpty() }?.let { "values-$lang-r$it" }, "values-$lang")
        }

        /** One strings.xml. */
        fun parse(input: InputStream): Strings {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // Our own files, but an XML parser is never left open to external entities.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val doc = input.use { factory.newDocumentBuilder().parse(it) }
            val strings = HashMap<String, String>()
            val arrays = HashMap<String, List<String>>()
            val plurals = HashMap<String, Map<String, String>>()
            val children = doc.documentElement.childNodes
            for (i in 0 until children.length) {
                val e = children.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                if (name.isEmpty()) continue
                when (e.tagName) {
                    "string" -> strings[name] = unescape(e.textContent)
                    "string-array" -> arrays[name] = elements(e, "item").map { unescape(it.textContent) }
                    "plurals" -> plurals[name] = elements(e, "item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
                }
            }
            return Strings(strings, arrays, plurals)
        }

        private fun elements(parent: Element, tag: String): List<Element> {
            val list = parent.getElementsByTagName(tag)
            return (0 until list.length).map { list.item(it) as Element }
        }

        /**
         * Android's rules for a resource string: text in double quotes is kept as it is, otherwise
         * runs of white space become one space; then the backslash escapes (\n, \t, \', \", \\, \@,
         * \?, \uXXXX).
         */
        fun unescape(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var space = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        val n = raw[i + 1]
                        i++
                        when (n) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'u' -> {
                                val hex = raw.substring(i + 1, minOf(i + 5, raw.length))
                                val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                                if (code != null) { out.append(code.toChar()); i += 4 } else out.append('u')
                            }
                            else -> out.append(n)
                        }
                        space = false
                    }
                    c == '"' -> quoted = !quoted
                    c.isWhitespace() && !quoted -> {
                        if (!space) out.append(' ')
                        space = true
                    }
                    else -> { out.append(c); space = false }
                }
                i++
            }
            return out.toString().trim()
        }
    }
}
