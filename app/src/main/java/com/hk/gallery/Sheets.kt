package com.hk.gallery

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

typealias Cell = Pair<Int, Int> // row, column (0-based)

class SheetData(val name: String, val path: String, val cells: Map<Cell, String>, val rows: Int, val cols: Int)

fun colName(i: Int): String { var n = i; var s = ""; do { s = ('A' + n % 26) + s; n = n / 26 - 1 } while (n >= 0); return s }
private fun colIndex(ref: String): Int { var n = 0; for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }; return n - 1 }
private fun rowIndex(ref: String): Int = ref.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1) ?: 0

object Sheets {
    private const val MAX_ROWS = 1000
    private const val MAX_COLS = 60

    private fun parse(zip: ZipFile, name: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(name)))

    private fun Element.rel(): String? {
        val a = attributes
        for (i in 0 until a.length) { val n = a.item(i); if (n.localName == "id" && n.namespaceURI?.contains("relationships") == true) return n.nodeValue }
        return null
    }

    private fun texts(e: Element): String {
        val ts = e.getElementsByTagNameNS("*", "t")
        return (0 until ts.length).joinToString("") { ts.item(it).textContent }
    }

    fun isCsv(f: File) = f.extension.lowercase() in setOf("csv", "tsv")

    fun load(file: File): List<SheetData> = if (isCsv(file)) listOf(loadCsv(file)) else loadXlsx(file)

    private fun loadXlsx(file: File): List<SheetData> {
        val out = ArrayList<SheetData>()
        ZipFile(file).use { zip ->
            val shared = ArrayList<String>()
            zip.getEntry("xl/sharedStrings.xml")?.let {
                val si = parse(zip, "xl/sharedStrings.xml").getElementsByTagNameNS("*", "si")
                for (i in 0 until si.length) shared += texts(si.item(i) as Element)
            }
            val rels = HashMap<String, String>()
            val relDoc = parse(zip, "xl/_rels/workbook.xml.rels").getElementsByTagNameNS("*", "Relationship")
            for (i in 0 until relDoc.length) { val r = relDoc.item(i) as Element; rels[r.getAttribute("Id")] = r.getAttribute("Target") }
            val sheets = parse(zip, "xl/workbook.xml").getElementsByTagNameNS("*", "sheet")
            for (i in 0 until sheets.length) {
                val s = sheets.item(i) as Element
                var target = rels[s.rel()] ?: continue
                target = if (target.startsWith("/")) target.trimStart('/') else "xl/$target"
                if (zip.getEntry(target) == null) continue
                val cells = HashMap<Cell, String>()
                var maxR = 0; var maxC = 0
                val cs = parse(zip, target).getElementsByTagNameNS("*", "c")
                for (k in 0 until cs.length) {
                    val c = cs.item(k) as Element
                    val ref = c.getAttribute("r")
                    val r = rowIndex(ref); val col = colIndex(ref)
                    if (r >= MAX_ROWS || col >= MAX_COLS || col < 0) continue
                    val v = c.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
                    val text = when (c.getAttribute("t")) {
                        "s" -> shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                        "inlineStr" -> texts(c)
                        "b" -> if (v == "1") "TRUE" else "FALSE"
                        else -> v
                    }
                    if (text.isNotEmpty()) { cells[r to col] = text; maxR = maxOf(maxR, r + 1); maxC = maxOf(maxC, col + 1) }
                }
                out += SheetData(s.getAttribute("name").ifBlank { "Sheet ${i + 1}" }, target, cells, maxR, maxC)
            }
        }
        return out
    }

    private fun loadCsv(file: File): SheetData {
        val text = file.readText()
        val delim = if (file.extension.equals("tsv", true)) '\t' else if (text.lineSequence().firstOrNull().orEmpty().let { it.count { c -> c == ';' } > it.count { c -> c == ',' } }) ';' else ','
        val cells = HashMap<Cell, String>()
        var r = 0; var c = 0; var maxC = 0
        val sb = StringBuilder(); var q = false; var i = 0
        fun endCell() { if (sb.isNotEmpty() && r < MAX_ROWS && c < MAX_COLS) { cells[r to c] = sb.toString(); maxC = maxOf(maxC, c + 1) }; sb.clear(); c++ }
        while (i < text.length) {
            val ch = text[i]
            when {
                q && ch == '"' && text.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                !q && ch == delim -> endCell()
                !q && (ch == '\n' || ch == '\r') -> { if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++; endCell(); r++; c = 0 }
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty() || c > 0) { endCell(); r++ }
        return SheetData(file.name, "", cells, minOf(r, MAX_ROWS), maxC)
    }

    fun saveCsv(src: File, sheet: SheetData, edits: Map<Cell, String>, out: File) {
        val delim = if (src.extension.equals("tsv", true)) '\t' else ','
        val rows = maxOf(sheet.rows, (edits.keys.maxOfOrNull { it.first } ?: -1) + 1)
        val cols = maxOf(sheet.cols, (edits.keys.maxOfOrNull { it.second } ?: -1) + 1)
        fun esc(s: String) = if (s.any { it == delim || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        out.bufferedWriter().use { w ->
            for (r in 0 until rows) w.appendLine((0 until cols).joinToString(delim.toString()) { c -> esc(edits[r to c] ?: sheet.cells[r to c].orEmpty()) })
        }
    }

    private fun setCell(doc: Document, data: Element, r: Int, c: Int, text: String) {
        val ns = data.namespaceURI
        val rowEls = data.getElementsByTagNameNS("*", "row")
        var row: Element? = null
        var before: org.w3c.dom.Node? = null
        for (i in 0 until rowEls.length) {
            val e = rowEls.item(i) as Element
            val rn = e.getAttribute("r").toIntOrNull() ?: continue
            if (rn == r + 1) { row = e; break }
            if (rn > r + 1 && before == null) before = e
        }
        if (row == null) { row = doc.createElementNS(ns, "row"); row.setAttribute("r", (r + 1).toString()); data.insertBefore(row, before) }
        val rw: Element = row!!
        val ref = colName(c) + (r + 1)
        var cell: Element? = null
        var cBefore: org.w3c.dom.Node? = null
        var k = rw.firstChild
        while (k != null) {
            if (k is Element && k.localName == "c") {
                val ci = colIndex(k.getAttribute("r"))
                if (ci == c) { cell = k; break }
                if (ci > c && cBefore == null) cBefore = k
            }
            k = k.nextSibling
        }
        if (cell == null) { cell = doc.createElementNS(ns, "c"); rw.insertBefore(cell, cBefore) }
        val ce: Element = cell!!
        while (ce.firstChild != null) ce.removeChild(ce.firstChild)
        ce.removeAttribute("t")
        ce.setAttribute("r", ref)
        if (text.isEmpty()) return
        if (text.toDoubleOrNull() != null) {
            ce.appendChild(doc.createElementNS(ns, "v").also { it.textContent = text })
        } else {
            ce.setAttribute("t", "inlineStr")
            val isEl = doc.createElementNS(ns, "is")
            val t = doc.createElementNS(ns, "t"); t.setAttribute("xml:space", "preserve"); t.textContent = text
            isEl.appendChild(t); ce.appendChild(isEl)
        }
    }

    fun saveXlsx(src: File, sheet: SheetData, edits: Map<Cell, String>, out: File) {
        ZipFile(src).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zo ->
                for (e in zip.entries()) {
                    zo.putNextEntry(ZipEntry(e.name))
                    if (e.name == sheet.path) {
                        val doc = parse(zip, e.name)
                        val data = doc.getElementsByTagNameNS("*", "sheetData").item(0) as Element
                        edits.forEach { (cell, text) -> setCell(doc, data, cell.first, cell.second, text) }
                        TransformerFactory.newInstance().newTransformer().apply {
                            setOutputProperty(OutputKeys.ENCODING, "UTF-8"); setOutputProperty(OutputKeys.STANDALONE, "yes")
                        }.transform(DOMSource(doc), StreamResult(zo))
                    } else zip.getInputStream(e).use { it.copyTo(zo) }
                    zo.closeEntry()
                }
            }
        }
    }
}

@Composable
private fun SheetGrid(sheet: SheetData, edits: Map<Cell, String>?, onCell: ((Int, Int) -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val rows = maxOf(sheet.rows + 5, 25)
    val cols = maxOf(sheet.cols + 2, 8)
    val cellW = 104.dp; val numW = 44.dp
    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width(numW + cellW * cols)) {
            item {
                Row(Modifier.background(cs.surfaceVariant)) {
                    Box(Modifier.size(numW, 32.dp).border(0.5.dp, cs.outline))
                    for (c in 0 until cols) Box(Modifier.size(cellW, 32.dp).border(0.5.dp, cs.outline), contentAlignment = Alignment.Center) {
                        Text(colName(c), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                    }
                }
            }
            items(rows) { r ->
                Row {
                    Box(Modifier.size(numW, 40.dp).background(cs.surfaceVariant).border(0.5.dp, cs.outline), contentAlignment = Alignment.Center) {
                        Text("${r + 1}", style = MonoStyle, color = cs.onSurfaceVariant)
                    }
                    for (c in 0 until cols) {
                        val edited = edits?.containsKey(r to c) == true
                        val text = edits?.get(r to c) ?: sheet.cells[r to c].orEmpty()
                        Box(
                            Modifier.size(cellW, 40.dp).border(0.5.dp, cs.outline)
                                .background(if (edited) cs.secondary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                .then(if (onCell != null) Modifier.clickable { onCell(r, c) } else Modifier)
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text(text, fontSize = 13.sp, maxLines = 2, lineHeight = 16.sp, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTabs(sheets: List<SheetData>, selected: Int, onSelect: (Int) -> Unit) {
    if (sheets.size < 2) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sheets.forEachIndexed { i, s ->
            val on = i == selected
            Text(
                s.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun SheetViewer(file: File) {
    val sheets by produceState<List<SheetData>?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { Sheets.load(file) }.getOrDefault(emptyList()) } }
    var sel by remember { mutableIntStateOf(0) }
    val list = sheets
    if (list == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }; return }
    if (list.isEmpty()) { Text("Couldn't read this spreadsheet.", Modifier.padding(24.dp)); return }
    Column(Modifier.fillMaxSize()) {
        SheetTabs(list, sel) { sel = it }
        Box(Modifier.weight(1f)) { SheetGrid(list[sel.coerceIn(0, list.lastIndex)], null, null) }
    }
}

@Composable
fun SheetEditorScreen(file: File, onClose: () -> Unit, onSaved: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = onClose)
    val sheets by produceState<List<SheetData>?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { Sheets.load(file) }.getOrDefault(emptyList()) } }
    var sel by remember { mutableIntStateOf(0) }
    val edits = remember { mutableStateMapOf<Pair<Int, Pair<Int, Int>>, String>() } // sheet -> cell
    var editing by remember { mutableStateOf<Cell?>(null) }
    var saving by remember { mutableStateOf(false) }
    val list = sheets
    val current = list?.getOrNull(sel)
    val shown: Map<Cell, String> = edits.filterKeys { it.first == sel }.mapKeys { it.key.second }

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel", color = cs.tertiary, fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            Text("Edit cells", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = !saving && edits.isNotEmpty() && list != null,
                onClick = {
                    saving = true
                    scope.launch(Dispatchers.IO) {
                        val dir = if (file.parentFile?.canWrite() == true) file.parentFile!! else Converters.outputDir(ctx)
                        val out = File(dir, file.nameWithoutExtension + " (edited)." + file.extension)
                        val ok = runCatching {
                            // One sheet at a time: apply edits for the first sheet that has any, others are kept as they are.
                            val bySheet = edits.entries.groupBy { it.key.first }
                            if (Sheets.isCsv(file)) Sheets.saveCsv(file, list!![0], bySheet[0].orEmpty().associate { it.key.second to it.value }, out)
                            else {
                                var src = file
                                val tmp = File(ctx.cacheDir, "xlsx_tmp").apply { mkdirs() }
                                bySheet.entries.forEachIndexed { n, (si, es) ->
                                    val dst = if (n == bySheet.size - 1) out else File(tmp, "step$n.xlsx")
                                    Sheets.saveXlsx(src, list!![si], es.associate { it.key.second to it.value }, dst)
                                    src = dst
                                }
                                tmp.deleteRecursively()
                            }
                        }.isSuccess
                        if (ok) android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.path), null, null)
                        withContext(Dispatchers.Main) {
                            saving = false
                            Toast.makeText(ctx, if (ok) "Saved ${out.name}" else "Couldn't save", Toast.LENGTH_SHORT).show()
                            if (ok) onSaved(out)
                        }
                    }
                },
            ) { Text("Save copy", color = cs.secondary, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
        }
        if (list == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        else if (list.isEmpty()) Text("Couldn't read this spreadsheet.", Modifier.padding(24.dp))
        else {
            SheetTabs(list, sel) { sel = it }
            Text("Tap a cell to change it. Edited cells are tinted. The original file stays untouched.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = cs.onSurfaceVariant)
            Box(Modifier.weight(1f)) { SheetGrid(current!!, shown) { r, c -> editing = r to c } }
        }
    }

    editing?.let { cell ->
        var text by remember(cell, sel) { mutableStateOf(edits[sel to cell] ?: current?.cells?.get(cell).orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${colName(cell.second)}${cell.first + 1}") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    val original = current?.cells?.get(cell).orEmpty()
                    if (text == original) edits.remove(sel to cell) else edits[sel to cell] = text
                    editing = null
                }) { Text("Done", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
