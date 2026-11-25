package com.hk.gallery

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

// ------------------------------------------------------------------ shared helpers

fun fileUri(ctx: android.content.Context, f: File): Uri = FileProvider.getUriForFile(ctx, "com.hk.gallery.files", f)

fun mimeOf(f: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

fun openExternal(ctx: android.content.Context, f: File) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(ctx, f), mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
}

fun shareFile(ctx: android.content.Context, f: File) {
    val i = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, fileUri(ctx, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
}

val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")

@Composable
fun FileViewer(file: File, onEdit: (() -> Unit)? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBackIosNew, "Back", Modifier.size(20.dp)) }
            Text(file.name, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleMedium, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (onEdit != null && file.extension.lowercase() in setOf("docx", "pptx", "xlsx", "csv", "tsv")) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { shareFile(ctx, file) }) { Icon(Icons.Outlined.IosShare, "Share", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { openExternal(ctx, file) }) { Icon(Icons.Outlined.OpenInNew, "Open with", tint = MaterialTheme.colorScheme.primary) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (file.extension.lowercase()) {
                "pdf" -> PdfViewer(file)
                "docx" -> DocxViewer(file)
                "pptx" -> PptxViewer(file)
                "xlsx", "csv", "tsv" -> SheetViewer(file)
                in AUDIO_EXT -> AudioPlayer(file)
                in VIDEO_EXT -> VideoPlayer(Uri.fromFile(file), Modifier.fillMaxSize().background(Color.Black))
                else -> Text("No preview for this file type.", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun canPreview(f: File) = f.extension.lowercase().let { it == "pdf" || it == "docx" || it == "pptx" || it == "xlsx" || it == "csv" || it == "tsv" || it in VIDEO_EXT || it in AUDIO_EXT }

// ------------------------------------------------------------------ video

@Composable
fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier, autoPlay: Boolean = true, insets: Boolean = false) {
    val ctx = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = autoPlay }
    }
    var muted by remember(uri) { mutableStateOf(false) }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    Box(modifier) {
        AndroidView(
            factory = { c -> (android.view.LayoutInflater.from(c).inflate(R.layout.player_texture, null) as PlayerView).also { it.player = player } },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.align(Alignment.TopEnd).then(if (insets) Modifier.statusBarsPadding() else Modifier).padding(top = 8.dp, end = 12.dp)
                .size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x66000000))
                .clickable { muted = !muted; player.volume = if (muted) 0f else 1f },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (muted) "Unmute" else "Mute", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

// ------------------------------------------------------------------ PDF

@Composable
fun PdfViewer(file: File) {
    val renderer = remember(file) { runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) { Text("Couldn't open this PDF.", Modifier.padding(24.dp)); return }
    val mutex = remember { Mutex() }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val wPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtMost(1400)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(renderer.pageCount) { i ->
                val bmp by produceState<ImageBitmap?>(null, i) {
                    value = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            runCatching {
                                renderer.openPage(i).use { p ->
                                    val b = Bitmap.createBitmap(wPx, (wPx.toFloat() * p.height / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                    b.eraseColor(AColor.WHITE)
                                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    b.asImageBitmap()
                                }
                            }.getOrNull()
                        }
                    }
                }
                val b = bmp
                if (b != null) Image(b, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.FillWidth)
                else Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
        }
    }
}

// ------------------------------------------------------------------ Office XML parsing helpers

private fun parseXml(bytes: ByteArray): org.w3c.dom.Document =
    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream())

private fun ZipFile.bytes(name: String): ByteArray? = getEntry(name)?.let { getInputStream(it).use { s -> s.readBytes() } }

private fun Element.kids(): List<Element> {
    val l = ArrayList<Element>()
    var c: Node? = firstChild
    while (c != null) { if (c is Element) l += c; c = c.nextSibling }
    return l
}

private fun Element.desc(name: String): List<Element> {
    val nl = getElementsByTagNameNS("*", name)
    return (0 until nl.length).map { nl.item(it) as Element }
}

private fun Element.firstDesc(name: String) = desc(name).firstOrNull()

/** The relationship id (r:id) of an element, not its plain numeric id attribute. */
private fun Element.relId(): String? {
    val a = attributes
    for (i in 0 until a.length) { val n = a.item(i); if (n.localName == "id" && n.namespaceURI?.contains("relationships") == true) return n.nodeValue }
    return null
}
private fun Element.attr(name: String): String? =
    getAttributeNS("*", name).takeIf { it.isNotEmpty() } ?: attributes.let { a ->
        (0 until a.length).map { a.item(it) }.firstOrNull { it.localName == name || it.nodeName.substringAfter(':') == name }?.nodeValue
    }

private fun readRels(zip: ZipFile, path: String): Map<String, String> {
    val b = zip.bytes(path) ?: return emptyMap()
    val d = parseXml(b).documentElement
    return d.kids().associate { (it.getAttribute("Id")) to it.getAttribute("Target") }
}

private fun resolve(baseDir: String, target: String): String {
    val parts = ArrayList<String>()
    if (target.startsWith("/")) return target.trimStart('/')
    (baseDir.split('/').filter { it.isNotEmpty() } + target.split('/')).forEach { if (it == "..") { if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) } else if (it != ".") parts += it }
    return parts.joinToString("/")
}

private fun decodeBmp(bytes: ByteArray, max: Int = 1400): ImageBitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1
    while (o.outWidth / s > max * 2) s *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}

// ------------------------------------------------------------------ DOCX

private sealed class DocBlock {
    class Para(val text: AnnotatedString, val size: Float, val align: TextAlign) : DocBlock()
    class Img(val bmp: ImageBitmap) : DocBlock()
    class Row(val cells: List<String>) : DocBlock()
}

private fun parseDocx(file: File): List<DocBlock> {
    val out = ArrayList<DocBlock>()
    ZipFile(file).use { zip ->
        val doc = parseXml(zip.bytes("word/document.xml") ?: return out).documentElement
        val rels = readRels(zip, "word/_rels/document.xml.rels")
        val body = doc.firstDesc("body") ?: return out

        fun para(p: Element) {
            val style = p.firstDesc("pStyle")?.attr("val").orEmpty()
            val jc = p.firstDesc("jc")?.attr("val")
            val numbered = p.firstDesc("numPr") != null
            val headingSize = when {
                style.equals("Title", true) -> 28f
                style.startsWith("Heading1", true) -> 24f
                style.startsWith("Heading2", true) -> 20f
                style.startsWith("Heading", true) -> 18f
                else -> 0f
            }
            val imgs = ArrayList<ImageBitmap>()
            val text = buildAnnotatedString {
                if (numbered) append("•  ")
                for (r in p.desc("r")) {
                    val rPr = r.kids().firstOrNull { it.localName == "rPr" }
                    val bold = rPr?.kids()?.any { it.localName == "b" && it.attr("val") !in setOf("0", "false") } == true || headingSize > 0
                    val ital = rPr?.kids()?.any { it.localName == "i" && it.attr("val") !in setOf("0", "false") } == true
                    val sb = StringBuilder()
                    r.kids().forEach { k ->
                        when (k.localName) { "t" -> sb.append(k.textContent); "tab" -> sb.append("    "); "br" -> sb.append('\n') }
                    }
                    if (sb.isNotEmpty()) withStyle(SpanStyle(fontWeight = if (bold) FontWeight.Bold else null, fontStyle = if (ital) FontStyle.Italic else null)) { append(sb) }
                    r.desc("blip").forEach { b ->
                        val id = b.attr("embed")
                        val target = rels[id] ?: return@forEach
                        zip.bytes(resolve("word", target))?.let { decodeBmp(it) }?.let { imgs += it }
                    }
                }
            }
            if (text.text.isNotBlank()) {
                val sz = if (headingSize > 0) headingSize else (p.firstDesc("sz")?.attr("val")?.toFloatOrNull()?.div(2f) ?: 16f).coerceIn(10f, 26f) * 0.95f
                out += DocBlock.Para(text, sz, when (jc) { "center" -> TextAlign.Center; "right", "end" -> TextAlign.End; else -> TextAlign.Start })
            }
            imgs.forEach { out += DocBlock.Img(it) }
        }

        for (el in body.kids()) {
            when (el.localName) {
                "p" -> para(el)
                "tbl" -> el.kids().filter { it.localName == "tr" }.forEach { tr ->
                    out += DocBlock.Row(tr.kids().filter { it.localName == "tc" }.map { tc -> tc.desc("t").joinToString("") { it.textContent } })
                }
            }
        }
    }
    return out
}

@Composable
fun DocxViewer(file: File) {
    val blocks by produceState<List<DocBlock>?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { parseDocx(file) }.getOrDefault(emptyList()) } }
    val b = blocks
    if (b == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (b.isEmpty()) { Text("Nothing to show in this document.", Modifier.padding(24.dp)); return }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        LazyColumn(
            Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.White),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(b) { blk ->
                when (blk) {
                    is DocBlock.Para -> Text(blk.text, Modifier.fillMaxWidth(), color = Color.Black, fontSize = blk.size.sp, lineHeight = (blk.size * 1.4f).sp, textAlign = blk.align)
                    is DocBlock.Img -> Image(blk.bmp, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.FillWidth)
                    is DocBlock.Row -> Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F7)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        blk.cells.forEach { Text(it, Modifier.weight(1f), color = Color.Black, fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ PPTX

private class SlideEl(val x: Long, val y: Long, val w: Long, val h: Long, val paras: List<SlidePara>, val img: ImageBitmap?)
private class SlidePara(val text: AnnotatedString, val sizePt: Float, val align: TextAlign, val color: Color)
private class Slide(val bg: Color, val els: List<SlideEl>)
private class Deck(val cx: Long, val cy: Long, val slides: List<Slide>)

private fun parsePptx(file: File): Deck {
    ZipFile(file).use { zip ->
        val pres = parseXml(zip.bytes("ppt/presentation.xml")!!).documentElement
        val sz = pres.firstDesc("sldSz")
        val cx = sz?.getAttribute("cx")?.toLongOrNull() ?: 12_192_000L
        val cy = sz?.getAttribute("cy")?.toLongOrNull() ?: 6_858_000L
        val presRels = readRels(zip, "ppt/_rels/presentation.xml.rels")
        val slidePaths = (pres.firstDesc("sldIdLst")?.kids() ?: emptyList()).mapNotNull { id ->
            presRels[id.relId()]?.let { resolve("ppt", it) }
        }

        fun xfrmOf(sp: Element): LongArray? {
            val x = sp.kids().firstOrNull { it.localName == "spPr" }?.kids()?.firstOrNull { it.localName == "xfrm" } ?: return null
            val off = x.kids().firstOrNull { it.localName == "off" } ?: return null
            val ext = x.kids().firstOrNull { it.localName == "ext" } ?: return null
            return longArrayOf(off.getAttribute("x").toLong(), off.getAttribute("y").toLong(), ext.getAttribute("cx").toLong(), ext.getAttribute("cy").toLong())
        }

        fun inherited(slidePath: String, ph: Element?): LongArray? {
            if (ph == null) return null
            val type = ph.getAttribute("type"); val idx = ph.getAttribute("idx")
            val rels = readRels(zip, slidePath.substringBeforeLast('/') + "/_rels/" + slidePath.substringAfterLast('/') + ".rels")
            val layoutPath = rels.values.map { resolve(slidePath.substringBeforeLast('/'), it) }.firstOrNull { it.contains("slideLayout") } ?: return null
            fun search(path: String): LongArray? {
                val d = parseXml(zip.bytes(path) ?: return null).documentElement
                for (sp in d.desc("sp")) {
                    val p = sp.firstDesc("ph") ?: continue
                    if ((idx.isNotEmpty() && p.getAttribute("idx") == idx) || (type.isNotEmpty() && p.getAttribute("type") == type)) xfrmOf(sp)?.let { return it }
                }
                return null
            }
            search(layoutPath)?.let { return it }
            val lrels = readRels(zip, layoutPath.substringBeforeLast('/') + "/_rels/" + layoutPath.substringAfterLast('/') + ".rels")
            val masterPath = lrels.values.map { resolve(layoutPath.substringBeforeLast('/'), it) }.firstOrNull { it.contains("slideMaster") } ?: return null
            return search(masterPath)
        }

        val slides = slidePaths.map { path ->
            val d = parseXml(zip.bytes(path) ?: return@map Slide(Color.White, emptyList())).documentElement
            val rels = readRels(zip, path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels")
            val bgHex = d.firstDesc("bg")?.firstDesc("srgbClr")?.getAttribute("val")
            val bg = bgHex?.let { runCatching { Color(AColor.parseColor("#$it")) }.getOrNull() } ?: Color.White
            val els = ArrayList<SlideEl>()
            val tree = d.firstDesc("spTree")
            val shapes = tree?.desc("sp").orEmpty() + tree?.desc("pic").orEmpty()
            for (sp in shapes) {
                if (sp.localName == "pic") {
                    val x = xfrmOf(sp) ?: continue
                    val rid = sp.firstDesc("blip")?.attr("embed")
                    val bmp = rels[rid]?.let { zip.bytes(resolve(path.substringBeforeLast('/'), it)) }?.let { decodeBmp(it) }
                    if (bmp != null) els += SlideEl(x[0], x[1], x[2], x[3], emptyList(), bmp)
                } else {
                    val ph = sp.firstDesc("ph")
                    val x = xfrmOf(sp) ?: inherited(path, ph) ?: continue
                    val isTitle = ph?.getAttribute("type")?.let { it == "title" || it == "ctrTitle" } == true
                    val paras = sp.desc("p").mapNotNull { p ->
                        val runs = p.kids().filter { it.localName == "r" }
                        if (runs.isEmpty()) return@mapNotNull null
                        var size = 0f; var color = Color.Unspecified
                        val text = buildAnnotatedString {
                            for (r in runs) {
                                val rPr = r.kids().firstOrNull { it.localName == "rPr" }
                                rPr?.getAttribute("sz")?.toFloatOrNull()?.let { if (size == 0f) size = it / 100f }
                                rPr?.firstDesc("srgbClr")?.getAttribute("val")?.let { h -> runCatching { color = Color(AColor.parseColor("#$h")) } }
                                val b = rPr?.getAttribute("b") == "1"; val i = rPr?.getAttribute("i") == "1"
                                withStyle(SpanStyle(fontWeight = if (b || isTitle) FontWeight.Bold else null, fontStyle = if (i) FontStyle.Italic else null)) {
                                    append(r.kids().filter { it.localName == "t" }.joinToString("") { it.textContent })
                                }
                            }
                        }
                        val algn = p.kids().firstOrNull { it.localName == "pPr" }?.getAttribute("algn")
                        SlidePara(
                            text, if (size > 0f) size else if (isTitle) 32f else 18f,
                            when (algn) { "ctr" -> TextAlign.Center; "r" -> TextAlign.End; else -> TextAlign.Start }, color,
                        )
                    }
                    if (paras.isNotEmpty()) els += SlideEl(x[0], x[1], x[2], x[3], paras, null)
                }
            }
            Slide(bg, els)
        }
        return Deck(cx, cy, slides)
    }
}

@Composable
fun PptxViewer(file: File) {
    val deck by produceState<Deck?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { parsePptx(file) }.getOrNull() } }
    val d = deck
    if (d == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (d.slides.isEmpty()) {
        val outline by produceState<List<TextPara>>(emptyList(), file) { value = withContext(Dispatchers.IO) { runCatching { OfficeText.load(file) }.getOrDefault(emptyList()) } }
        if (outline.isEmpty()) Text("Couldn't read this presentation.", Modifier.padding(24.dp))
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(outline) { i, p ->
                if (i == 0 || outline[i - 1].label != p.label) Text(p.label, Modifier.padding(top = 10.dp), fontWeight = FontWeight.Medium)
                Text(p.original, fontSize = 15.sp)
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(d.slides) { i, s ->
            Column {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(d.cx.toFloat() / d.cy).clip(RoundedCornerShape(8.dp)).background(s.bg)) {
                    val dpPerEmu = maxWidth.value / d.cx.toFloat()
                    val dpPerPt = maxWidth.value / (d.cx / 12700f)
                    s.els.forEach { e ->
                        Box(Modifier.offset((e.x * dpPerEmu).dp, (e.y * dpPerEmu).dp).size((e.w * dpPerEmu).dp, (e.h * dpPerEmu).dp)) {
                            if (e.img != null) Image(e.img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else Column {
                                e.paras.forEach { p ->
                                    val fs = with(LocalDensity.current) { (p.sizePt * dpPerPt).dp.toSp() }
                                    Text(
                                        p.text, Modifier.fillMaxWidth(), fontSize = fs, lineHeight = fs * 1.2f, textAlign = p.align,
                                        color = if (p.color == Color.Unspecified) (if (s.bg.luminance() < 0.4f) Color.White else Color.Black) else p.color,
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Slide ${i + 1}", Modifier.padding(top = 4.dp, start = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun Color.luminance() = 0.299f * red + 0.587f * green + 0.114f * blue
