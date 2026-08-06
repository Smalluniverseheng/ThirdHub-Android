package com.thirdhub.app.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipInputStream

/* 本地书籍（TXT / EPUB）导入与读取，全部离线完成 */
object LocalBook {

    data class Meta(
        val id: String,
        val title: String,
        val author: String,
        val chapterCount: Int,
        val fileName: String,
        val addedAt: Long,
    )

    private fun booksDir(ctx: Context): File = File(ctx.filesDir, "books").apply { mkdirs() }
    private fun bookDir(ctx: Context, id: String): File = File(booksDir(ctx), id)
    private fun indexFile(ctx: Context): File = File(booksDir(ctx), "index.json")

    /* ---------- 索引 ---------- */

    fun list(ctx: Context): List<Meta> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Meta>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Meta(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        author = o.optString("author"),
                        chapterCount = o.optInt("chapterCount"),
                        fileName = o.optString("fileName"),
                        addedAt = o.optLong("addedAt"),
                    )
                )
            }
            out.sortedByDescending { it.addedAt }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveIndex(ctx: Context, metas: List<Meta>) {
        val arr = JSONArray()
        for (m in metas) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("title", m.title)
                    .put("author", m.author)
                    .put("chapterCount", m.chapterCount)
                    .put("fileName", m.fileName)
                    .put("addedAt", m.addedAt)
            )
        }
        indexFile(ctx).writeText(arr.toString())
    }

    fun delete(ctx: Context, id: String) {
        bookDir(ctx, id).deleteRecursively()
        saveIndex(ctx, list(ctx).filter { it.id != id })
    }

    /* ---------- 导入 ---------- */

    fun displayName(ctx: Context, uri: Uri): String {
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else "未知文件"
            } ?: "未知文件"
        } catch (_: Exception) { "未知文件" }
    }

    fun import(ctx: Context, uri: Uri, name: String): Meta {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法读取文件")
        if (bytes.isEmpty()) throw Exception("文件内容为空")

        val isEpub = name.lowercase().endsWith(".epub")
        val title: String
        val author: String
        val chapters: List<Pair<String, String>> // name to content

        if (isEpub) {
            val parsed = parseEpub(bytes)
            title = parsed.first
            author = parsed.second
            chapters = parsed.third
        } else {
            val text = decodeText(bytes)
            if (text.isBlank()) throw Exception("文件内容为空")
            title = name.replace(Regex("\\.\\w+$"), "")
            author = ""
            chapters = splitTxt(text)
        }
        if (chapters.isEmpty()) throw Exception("未能解析出任何章节")

        val id = "lb-" + UUID.randomUUID().toString().substring(0, 8)
        val dir = bookDir(ctx, id)
        dir.mkdirs()

        val titles = JSONArray()
        chapters.forEachIndexed { i, ch ->
            File(dir, "chap_$i.txt").writeText(ch.second)
            titles.put(ch.first)
        }
        File(dir, "meta.json").writeText(
            JSONObject()
                .put("title", title)
                .put("author", author)
                .put("fileName", name)
                .put("titles", titles)
                .toString()
        )

        val meta = Meta(id, title, author, chapters.size, name, System.currentTimeMillis())
        saveIndex(ctx, list(ctx) + meta)
        return meta
    }

    /* ---------- 读取 ---------- */

    fun chapterTitles(ctx: Context, id: String): List<String> {
        return try {
            val o = JSONObject(File(bookDir(ctx, id), "meta.json").readText())
            val arr = o.getJSONArray("titles")
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun bookTitle(ctx: Context, id: String): String {
        return try { JSONObject(File(bookDir(ctx, id), "meta.json").readText()).optString("title") } catch (_: Exception) { "" }
    }

    fun chapterText(ctx: Context, id: String, index: Int): String {
        val f = File(bookDir(ctx, id), "chap_$index.txt")
        if (!f.exists()) throw Exception("章节不存在")
        return f.readText()
    }

    /* ---------- TXT ---------- */

    private fun decodeText(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            try { String(bytes, Charset.forName("GBK")) } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
        }
    }

    private fun splitTxt(text: String): List<Pair<String, String>> {
        val re = Regex(
            "^\\s*(第[\\d零一二三四五六七八九十百千]{1,10}[章节回卷部篇][^\\n]{0,40}|序章|序|楔子|尾声|番外[^\\n]{0,30})\\s*$",
            RegexOption.MULTILINE
        )
        val matches = re.findAll(text).toList()
        if (matches.size < 3) {
            val chunks = ArrayList<Pair<String, String>>()
            val size = 40000
            var i = 0
            while (i < text.length) {
                chunks.add("第 ${chunks.size + 1} 节" to text.substring(i, minOf(i + size, text.length)))
                i += size
            }
            return chunks
        }
        val chapters = ArrayList<Pair<String, String>>()
        if (matches[0].range.first > 200) chapters.add("卷首" to text.substring(0, matches[0].range.first))
        for (i in matches.indices) {
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            chapters.add(matches[i].groupValues[1].trim() to text.substring(start + matches[i].value.length, end))
        }
        return chapters
    }

    /* ---------- EPUB ---------- */

    private fun newParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newPullParser()
    }

    private fun localName(tag: String): String = tag.substringAfter(':')

    private fun parseEpub(bytes: ByteArray): Triple<String, String, List<Pair<String, String>>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var entry = z.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) files[entry.name] = z.readBytes()
                z.closeEntry()
                entry = z.nextEntry
            }
        }
        val containerXml = files["META-INF/container.xml"] ?: throw Exception("不是有效的 EPUB 文件")

        // container.xml -> OPF 路径
        var opfPath = ""
        run {
            val p = newParser()
            p.setInput(StringReader(String(containerXml, Charsets.UTF_8)))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && localName(p.name) == "rootfile") {
                    opfPath = p.getAttributeValue(null, "full-path") ?: ""
                    break
                }
                ev = p.next()
            }
        }
        if (opfPath.isEmpty()) throw Exception("EPUB 缺少 OPF 索引")
        val opfBytes = files[opfPath] ?: throw Exception("EPUB 缺少 OPF 文件")
        val opfDir = if (opfPath.contains("/")) opfPath.substring(0, opfPath.lastIndexOf("/") + 1) else ""

        // OPF 解析
        var title = "未命名"
        var author = ""
        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        run {
            val p = newParser()
            p.setInput(StringReader(String(opfBytes, Charsets.UTF_8)))
            var ev = p.eventType
            var inTitle = false
            var inCreator = false
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (localName(p.name)) {
                        "title" -> inTitle = true
                        "creator" -> inCreator = true
                        "item" -> {
                            val id = p.getAttributeValue(null, "id") ?: ""
                            val href = p.getAttributeValue(null, "href") ?: ""
                            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href
                        }
                        "itemref" -> {
                            val idref = p.getAttributeValue(null, "idref") ?: ""
                            if (idref.isNotEmpty()) spine.add(idref)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inTitle && title == "未命名") title = p.text?.trim() ?: title
                        if (inCreator && author.isEmpty()) author = p.text?.trim() ?: ""
                    }
                    XmlPullParser.END_TAG -> when (localName(p.name)) {
                        "title" -> inTitle = false
                        "creator" -> inCreator = false
                    }
                }
                ev = p.next()
            }
        }

        val chapters = ArrayList<Pair<String, String>>()
        for (idref in spine) {
            val href = manifest[idref] ?: continue
            val raw = files[opfDir + href] ?: continue
            val html = String(raw, Charsets.UTF_8)
            val text = htmlToText(html)
            if (text.length <= 10) continue
            val h = Regex("(?is)<h[1-6][^>]*>(.*?)</h[1-6]>").find(html)?.groupValues?.get(1)
            val name = (if (h != null) htmlToText(h).replace("\n", " ").trim() else "").ifEmpty { "第 ${chapters.size + 1} 章" }
            chapters.add(name to text)
        }
        if (chapters.isEmpty()) throw Exception("EPUB 内容为空")
        return Triple(title, author, chapters)
    }

    private fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<script.*?</script>"), "")
        s = s.replace(Regex("(?is)<style.*?</style>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote)>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n\n")
        return s.trim()
    }
}
