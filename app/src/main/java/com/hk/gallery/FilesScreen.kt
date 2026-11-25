package com.hk.gallery

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.material.icons.filled.Check
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class FileCat(val label: String, val icon: ImageVector, val color: Color, val exts: Set<String>) {
    Pdf("PDF", Icons.Outlined.PictureAsPdf, Color(0xFFE5484D), setOf("pdf")),
    Word("Word", Icons.Outlined.Description, Color(0xFF2B6CF0), setOf("doc", "docx", "odt", "rtf")),
    Slides("Slides", Icons.Outlined.Slideshow, Color(0xFFEE7A30), setOf("ppt", "pptx", "odp", "key")),
    Sheets("Sheets", Icons.Outlined.TableChart, Color(0xFF1F9D55), setOf("xls", "xlsx", "csv", "ods")),
    Videos("Videos", Icons.Outlined.Movie, Color(0xFF8B5CF6), setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")),
    Audio("Audio", Icons.Outlined.MusicNote, Color(0xFFEC4899), setOf("mp3", "m4a", "wav", "flac", "aac", "ogg", "opus")),
    Archives("Archives", Icons.Outlined.FolderZip, Color(0xFFB7791F), setOf("zip", "rar", "7z", "tar", "gz")),
    Apps("Apps", Icons.Outlined.Android, Color(0xFF3DDC84), setOf("apk", "xapk")),
    Text("Text", Icons.Outlined.TextSnippet, Color(0xFF64748B), setOf("txt", "md", "json", "log", "xml", "html")),
}

class FileItem(val file: File, val cat: FileCat, val size: Long, val modified: Long)

private fun scanFiles(): List<FileItem> {
    val root = Environment.getExternalStorageDirectory()
    val byExt = FileCat.values().flatMap { c -> c.exts.map { it to c } }.toMap()
    val out = ArrayList<FileItem>()
    root.walkTopDown().onEnter { d -> !d.name.startsWith(".") && !(d.parentFile == root && d.name == "Android") }.forEach { f ->
        if (f.isFile) {
            val c = byExt[f.extension.lowercase()]
            if (c != null && f.length() > 0) out += FileItem(f, c, f.length(), f.lastModified())
        }
    }
    return out.sortedByDescending { it.modified }
}

object PdfMerge {
    /** Joins PDFs in the given order into one real PDF (text and vector content are kept). */
    fun merge(ctx: android.content.Context, files: List<File>): File {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val dir = files.first().parentFile?.takeIf { it.canWrite() } ?: Converters.outputDir(ctx)
        var out = File(dir, "Merged (${files.size} PDFs).pdf")
        var n = 2
        while (out.exists()) out = File(dir, "Merged (${files.size} PDFs) $n.pdf").also { n++ }
        val m = PDFMergerUtility()
        files.forEach { m.addSource(it) }
        m.destinationFileName = out.path
        m.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.path), null, null)
        return out
    }
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
fun FilesScreen(hasAccess: Boolean, askAccess: () -> Unit, onSettings: () -> Unit, openFile: (File) -> Unit, version: Int, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<FileItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var cat by remember { mutableStateOf<FileCat?>(null) }
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var merging by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(picked.isNotEmpty()) { picked.clear() }
    BackHandler(cat != null && picked.isEmpty()) { cat = null; query = "" }

    LaunchedEffect(hasAccess, reload, version) {
        if (hasAccess) items = withContext(Dispatchers.IO) { scanFiles() }
    }

    if (!hasAccess) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("All your files, sorted", style = MaterialTheme.typography.titleLarge)
            Text(
                "Allow file access so GalleryKnife can find PDFs, documents, slides, videos and more on this device. Nothing leaves your phone.",
                Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = askAccess, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Allow file access", fontWeight = FontWeight.SemiBold) }
        }
        return
    }

    val all = items
    if (all == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }

    if (cat != null) {
        val c = cat!!
        val list = all.filter { it.cat == c && (query.isBlank() || it.file.name.contains(query, true)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Column {
                    if (picked.isNotEmpty()) {
                        val pickedItems = picked.mapNotNull { p -> all.firstOrNull { it.file.path == p } }
                        val pdfs = pickedItems.filter { it.cat == FileCat.Pdf }
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 4.dp, start = 20.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${picked.size} selected", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 16.sp)
                            TextButton(onClick = { picked.clear() }) { Text("Cancel", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium) }
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                                DropdownMenu(menuOpen, { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (pdfs.size >= 2) "Merge ${pdfs.size} PDFs" else "Merge PDFs (select 2 or more)") },
                                        enabled = pdfs.size >= 2 && pdfs.size == pickedItems.size,
                                        onClick = {
                                            menuOpen = false
                                            val order = pdfs.map { it.file }
                                            merging = true
                                            scope.launch(Dispatchers.IO) {
                                                val res = runCatching { PdfMerge.merge(ctx, order) }
                                                withContext(Dispatchers.Main) {
                                                    merging = false
                                                    val out = res.getOrNull()
                                                    if (out != null) {
                                                        Toast.makeText(ctx, "Saved ${out.name}", Toast.LENGTH_SHORT).show()
                                                        picked.clear(); reload++; openFile(out)
                                                    } else Toast.makeText(ctx, "Couldn't merge these PDFs", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(text = { Text("Share") }, onClick = {
                                        menuOpen = false
                                        val uris = ArrayList(pickedItems.map { fileUri(ctx, it.file) })
                                        val i = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                                            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        runCatching { ctx.startActivity(android.content.Intent.createChooser(i, null)) }
                                    })
                                    DropdownMenuItem(text = { Text("Delete", color = Color(0xFFFF453A)) }, onClick = {
                                        menuOpen = false
                                        pickedItems.forEach { it.file.delete() }
                                        picked.clear(); onDeleted()
                                    })
                                }
                            }
                        }
                    } else Row(Modifier.statusBarsPadding().padding(top = 4.dp, start = 4.dp)) {
                        TextButton(onClick = { cat = null; query = "" }) {
                            Icon(Icons.Outlined.ArrowBackIosNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Files", fontSize = 17.sp)
                        }
                    }
                    Text(c.label, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.displayLarge)
                    MetaLine("${list.size} items", modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                    FileSearchBox(query) { query = it }
                }
            }
            items(list, key = { it.file.path }) { fi ->
                val path = fi.file.path
                FileRow(
                    fi, selected = path in picked, selecting = picked.isNotEmpty(),
                    onOpen = { if (picked.isNotEmpty()) { if (path in picked) picked.remove(path) else picked.add(path) } else if (canPreview(fi.file)) openFile(fi.file) else openExternal(ctx, fi.file) },
                    onSelect = { if (path in picked) picked.remove(path) else picked.add(path) },
                    onDeleted = onDeleted,
                )
            }
            item { Footer() }
        }
        if (merging) AlertDialog(
            onDismissRequest = {}, confirmButton = {},
            title = { Text("Merging PDFs…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
        return
    }

    val recent = all.filter { it.cat != FileCat.Videos && it.cat != FileCat.Audio }.take(4)
    val kinds = FileCat.values().filter { c -> all.any { it.cat == c } }
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Files", style = MaterialTheme.typography.displayLarge)
                    MetaLine("${all.size} files", fmtSize(all.sumOf { it.size }), modifier = Modifier.padding(top = 8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconBtn(Icons.Outlined.Refresh, "Rescan") { reload++ }
                    IconBtn(Icons.Outlined.Tune, "Settings", onSettings)
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { Text("Recent", Modifier.padding(start = 20.dp, top = 28.dp, bottom = 12.dp), fontWeight = FontWeight.Medium, fontSize = 15.sp) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { "r" + it.file.path }) { fi ->
                        Column(Modifier.width(108.dp).clickable { if (canPreview(fi.file)) openFile(fi.file) else openExternal(ctx, fi.file) }) {
                            PaperCard(fi)
                            Text(fi.file.name, Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fmtSize(fi.size), style = MonoStyle.copy(fontSize = 11.sp, lineHeight = 14.sp), color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { Text("Kinds", Modifier.padding(start = 20.dp, top = 28.dp, bottom = 12.dp), fontWeight = FontWeight.Medium, fontSize = 15.sp) }
        item { Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(0.5.dp).background(cs.outline)) }
        items(kinds, key = { it.name }) { c ->
            val inCat = all.count { it.cat == c }
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(48.dp).clickable { cat = c }
                    .drawBehind { drawLine(cs.outline, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1f) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(c.icon, null, Modifier.size(18.dp), tint = cs.tertiary)
                }
                Text(c.label, Modifier.weight(1f), fontSize = 15.sp)
                Text("$inCat", style = MonoStyle, color = cs.onSurfaceVariant)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
        }
        item { Footer() }
    }
}

private class PaperShape : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val c = with(density) { 22.dp.toPx() }
        return androidx.compose.ui.graphics.Outline.Generic(androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(size.width - c, 0f); lineTo(size.width, c); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        })
    }
}

/** A sheet of paper with the folded corner from the logo. */
@Composable
private fun PaperCard(fi: FileItem) {
    val paper = Color(0xFFF3F1EA); val ink = Color(0xFFCFCABD); val tagc = Color(0xFF5E5A50)
    Box(Modifier.fillMaxWidth().height(140.dp).clip(PaperShape()).background(paper)) {
        Box(
            Modifier.align(Alignment.TopEnd).size(22.dp).drawBehind {
                drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(0f, size.height); lineTo(size.width, size.height); close() }, Color(0xFFCFCBBF))
            },
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fun line(w: Float) = Modifier.fillMaxWidth(w).height(4.dp).clip(RoundedCornerShape(2.dp)).background(ink)
            when (fi.cat) {
                FileCat.Slides -> {
                    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1D1D1F))) {
                        Box(Modifier.padding(start = 8.dp, top = 10.dp).fillMaxWidth(0.46f).height(5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFE7A14A)))
                        Box(Modifier.padding(start = 8.dp, top = 22.dp).fillMaxWidth(0.62f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF6B6B70)))
                    }
                    Box(line(0.8f)); Box(line(0.6f))
                }
                FileCat.Pdf -> {
                    Box(line(0.8f)); Box(line(1f))
                    Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(3.dp)).background(Brush.linearGradient(listOf(Color(0xFFD9D3C4), Color(0xFFB9B2A0)))))
                    Box(line(0.6f))
                }
                else -> { Box(line(0.8f)); Box(line(1f)); Box(line(1f)); Box(line(0.6f)); Box(line(0.8f)); Box(line(1f)) }
            }
            Spacer(Modifier.weight(1f))
            Text(fi.file.extension.uppercase(), style = MonoStyle.copy(fontSize = 11.sp, lineHeight = 14.sp), color = tagc)
        }
    }
}

@Composable
private fun FileSearchBox(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search files", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            BasicTextField(
                query, onChange, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FileRow(fi: FileItem, selected: Boolean = false, selecting: Boolean = false, onOpen: () -> Unit, onSelect: () -> Unit = {}, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(if (selected) cs.surfaceVariant else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onSelect).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) cs.secondary else cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = cs.onSecondary)
            else Icon(fi.cat.icon, null, Modifier.size(18.dp), tint = cs.tertiary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(fi.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(
                "${fmtSize(fi.size)}  ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(fi.modified))}",
                style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (fi.cat == FileCat.Pdf) "Select to merge" else "Select") }, onClick = { menu = false; onSelect() })
                DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; openExternal(ctx, fi.file) })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; shareFile(ctx, fi.file) })
                DropdownMenuItem(text = { Text("Delete", color = Color(0xFFFF453A)) }, onClick = { menu = false; fi.file.delete(); onDeleted() })
            }
        }
    }
}
