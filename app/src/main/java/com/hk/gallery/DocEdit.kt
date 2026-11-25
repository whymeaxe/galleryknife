package com.hk.gallery

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

/** One editable line of text from a Word document or a slide. */
class TextPara(val part: String, val index: Int, val original: String, val label: String) { var text by mutableStateOf(original) }

object OfficeText {
    private fun parts(zip: ZipFile, ext: String): List<String> {
        val names = zip.entries().asSequence().map { it.name }.toList()
        return if (ext == "docx") listOf("word/document.xml").filter { it in names }
        else names.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }.sortedBy { Regex("\\d+").find(it.substringAfterLast('/'))!!.value.toInt() }
    }

    private fun parse(zip: ZipFile, name: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(name)))

    private fun paragraphs(doc: Document, ext: String): List<Pair<Element, List<Element>>> {
        val pTag = "p"
        val tTag = "t"
        val ps = doc.getElementsByTagNameNS("*", pTag)
        val out = ArrayList<Pair<Element, List<Element>>>()
        for (i in 0 until ps.length) {
            val p = ps.item(i) as Element
            val ts = p.getElementsByTagNameNS("*", tTag)
            val list = (0 until ts.length).map { ts.item(it) as Element }.filter { it.localName == "t" }
            if (list.isNotEmpty()) out += p to list
        }
        return out
    }

    fun load(file: File): List<TextPara> {
        val ext = file.extension.lowercase()
        val res = ArrayList<TextPara>()
        ZipFile(file).use { zip ->
            parts(zip, ext).forEachIndexed { pi, part ->
                val label = if (ext == "docx") "Document" else "Slide ${pi + 1}"
                paragraphs(parse(zip, part), ext).forEachIndexed { idx, (_, ts) ->
                    res += TextPara(part, idx, ts.joinToString("") { it.textContent }, label)
                }
            }
        }
        return res
    }

    /** Writes a copy with the changed text. Formatting inside an edited paragraph is simplified to its first style. */
    fun save(src: File, edits: List<TextPara>, out: File) {
        val ext = src.extension.lowercase()
        val changed = edits.filter { it.text != it.original }.groupBy { it.part }
        ZipFile(src).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zo ->
                for (entry in zip.entries()) {
                    zo.putNextEntry(ZipEntry(entry.name))
                    val edits2 = changed[entry.name]
                    if (edits2 == null) zip.getInputStream(entry).use { it.copyTo(zo) }
                    else {
                        val doc = parse(zip, entry.name)
                        val ps = paragraphs(doc, ext)
                        edits2.forEach { e ->
                            val ts = ps.getOrNull(e.index)?.second ?: return@forEach
                            ts.forEachIndexed { i, t ->
                                t.textContent = if (i == 0) e.text else ""
                                if (i == 0) t.setAttribute("xml:space", "preserve")
                            }
                        }
                        val tf = TransformerFactory.newInstance().newTransformer().apply {
                            setOutputProperty(OutputKeys.ENCODING, "UTF-8"); setOutputProperty(OutputKeys.STANDALONE, "yes")
                        }
                        tf.transform(DOMSource(doc), StreamResult(zo))
                    }
                    zo.closeEntry()
                }
            }
        }
    }
}

@Composable
fun DocEditorScreen(file: File, onClose: () -> Unit, onSaved: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = onClose)
    val paras by produceState<List<TextPara>?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { OfficeText.load(file) }.getOrDefault(emptyList()) } }
    var saving by remember { mutableStateOf(false) }
    val list = paras

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel", color = cs.tertiary, fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            Text("Edit text", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = !saving && list != null && list.any { it.text != it.original },
                onClick = {
                    saving = true
                    scope.launch(Dispatchers.IO) {
                        val dir = if (file.parentFile?.canWrite() == true) file.parentFile!! else Converters.outputDir(ctx)
                        val out = File(dir, file.nameWithoutExtension + " (edited)." + file.extension)
                        val ok = runCatching { OfficeText.save(file, list!!, out) }.isSuccess
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
        else if (list.isEmpty()) Text("No editable text found in this file.", Modifier.padding(24.dp), color = cs.onSurfaceVariant)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Change the text, then Save copy. Pictures and layout are kept. The original file stays untouched.", fontSize = 13.sp, color = cs.onSurfaceVariant, lineHeight = 18.sp) }
            itemsIndexed(list) { i, p ->
                if (i == 0 || list[i - 1].label != p.label) Text(p.label, Modifier.padding(top = 10.dp), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                OutlinedTextField(
                    p.text, { p.text = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
            item { Footer() }
        }
    }
}
