package com.hk.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

class Jpeg(val bytes: ByteArray, val w: Int, val h: Int)

/** Pure on-device converters: images -> PDF / PPTX / DOCX, written by hand (no external libraries). */
object Converters {

    fun outputDir(ctx: Context): File {
        val dir = if (Environment.isExternalStorageManager()) File(Environment.getExternalStorageDirectory(), "Documents/GalleryKnife")
        else File(ctx.getExternalFilesDir(null), "Converted")
        dir.mkdirs()
        return dir
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun loadJpeg(ctx: Context, m: Media, maxDim: Int = 1800): Jpeg {
        val src = ImageDecoder.createSource(ctx.contentResolver, m.uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val s = min(1f, maxDim.toFloat() / maxOf(info.size.width, info.size.height))
            dec.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 86, out)
        val j = Jpeg(out.toByteArray(), bmp.width, bmp.height)
        bmp.recycle()
        return j
    }

    fun convert(ctx: Context, items: List<Media>, kind: String, onProgress: (Int, Int) -> Unit): File {
        val imgs = items.filter { !it.isVideo }
        require(imgs.isNotEmpty()) { "Select at least one photo" }
        val file = File(outputDir(ctx), "GalleryKnife-${stamp()}.$kind")
        file.outputStream().buffered().use { os ->
            val jpegs = ArrayList<Jpeg>()
            for ((i, m) in imgs.withIndex()) { jpegs += loadJpeg(ctx, m); onProgress(i + 1, imgs.size) }
            when (kind) {
                "pdf" -> writePdf(jpegs, os)
                "pptx" -> writePptx(jpegs, os)
                "docx" -> writeDocx(jpegs, os)
                else -> error("Unknown type")
            }
        }
        return file
    }

    // ------------------------------------------------------------------ PDF

    fun writePdf(imgs: List<Jpeg>, os: OutputStream) {
        val buf = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun w(s: String) = buf.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(id: Int, body: () -> Unit) { while (offsets.size < id) offsets.add(0); offsets[id - 1] = buf.size(); w("$id 0 obj\n"); body(); w("\nendobj\n") }

        w("%PDF-1.4\n%âãÏÓ\n")
        val n = imgs.size
        obj(1) { w("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) { w("<< /Type /Pages /Kids [" + (0 until n).joinToString(" ") { "${3 + it * 3} 0 R" } + "] /Count $n >>") }
        for ((i, im) in imgs.withIndex()) {
            val pid = 3 + i * 3; val cid = pid + 1; val iid = pid + 2
            val landscape = im.w > im.h
            val pw = if (landscape) 842f else 595f
            val ph = if (landscape) 595f else 842f
            val margin = 24f
            val s = min((pw - 2 * margin) / im.w, (ph - 2 * margin) / im.h)
            val dw = im.w * s; val dh = im.h * s
            val x = (pw - dw) / 2; val y = (ph - dh) / 2
            obj(pid) { w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] /Resources << /XObject << /Im0 $iid 0 R >> >> /Contents $cid 0 R >>") }
            val content = "q ${"%.2f".format(Locale.US, dw)} 0 0 ${"%.2f".format(Locale.US, dh)} ${"%.2f".format(Locale.US, x)} ${"%.2f".format(Locale.US, y)} cm /Im0 Do Q"
            obj(cid) { w("<< /Length ${content.length} >>\nstream\n$content\nendstream") }
            obj(iid) {
                w("<< /Type /XObject /Subtype /Image /Width ${im.w} /Height ${im.h} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${im.bytes.size} >>\nstream\n")
                buf.write(im.bytes); w("\nendstream")
            }
        }
        val xref = buf.size()
        val total = offsets.size + 1
        w("xref\n0 $total\n0000000000 65535 f \n")
        offsets.forEach { w("%010d 00000 n \n".format(it)) }
        w("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xref\n%%EOF")
        os.write(buf.toByteArray())
    }

    // ------------------------------------------------------------------ Office helpers

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"

    private fun ZipOutputStream.put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))
    private fun ZipOutputStream.put(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name)); write(data); closeEntry()
    }

    private fun rels(vararg r: Triple<String, String, String>) =
        XML + "<Relationships xmlns=\"$NS_PKG_REL\">" +
            r.joinToString("") { "<Relationship Id=\"${it.first}\" Type=\"${it.second}\" Target=\"${it.third}\"/>" } + "</Relationships>"

    // ------------------------------------------------------------------ DOCX

    fun writeDocx(imgs: List<Jpeg>, os: OutputStream) {
        val z = ZipOutputStream(os)
        z.put(
            "[Content_Types].xml",
            XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
        )
        z.put("_rels/.rels", rels(Triple("rId1", "$NS_R/officeDocument", "word/document.xml")))
        z.put("word/_rels/document.xml.rels", rels(*imgs.indices.map { Triple("rId${it + 1}", "$NS_R/image", "media/image${it + 1}.jpeg") }.toTypedArray()))

        val maxW = 6_100_000.0; val maxH = 8_900_000.0
        val body = StringBuilder()
        for ((i, im) in imgs.withIndex()) {
            val s = min(maxW / im.w, maxH / im.h)
            val cx = (im.w * s).toLong(); val cy = (im.h * s).toLong()
            body.append(
                "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
                    "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
                    "<wp:docPr id=\"${i + 1}\" name=\"Picture ${i + 1}\"/>" +
                    "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
                    "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                    "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"${i + 1}\" name=\"image${i + 1}.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                    "<pic:blipFill><a:blip r:embed=\"rId${i + 1}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                    "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                    "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>" +
                    (if (i < imgs.lastIndex) "<w:r><w:br w:type=\"page\"/></w:r>" else "") + "</w:p>"
            )
        }
        z.put(
            "word/document.xml",
            XML + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"$NS_R\" " +
                "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"$NS_A\" " +
                "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>$body" +
                "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1080\" w:right=\"1080\" w:bottom=\"1080\" w:left=\"1080\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>" +
                "</w:body></w:document>",
        )
        imgs.forEachIndexed { i, im -> z.put("word/media/image${i + 1}.jpeg", im.bytes) }
        z.finish()
    }

    // ------------------------------------------------------------------ PPTX

    private const val GRP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private fun theme(): String {
        val fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        fun clr(n: String, v: String) = "<a:$n><a:srgbClr val=\"$v\"/></a:$n>"
        return XML + "<a:theme xmlns:a=\"$NS_A\" name=\"HK\"><a:themeElements><a:clrScheme name=\"HK\">" +
            clr("dk1", "000000") + clr("lt1", "FFFFFF") + clr("dk2", "1F2937") + clr("lt2", "F3F4F6") +
            clr("accent1", "12A06A") + clr("accent2", "0A84FF") + clr("accent3", "C8A04A") + clr("accent4", "7C5CFF") +
            clr("accent5", "E5486B") + clr("accent6", "14B8C4") + clr("hlink", "0563C1") + clr("folHlink", "954F72") +
            "</a:clrScheme><a:fontScheme name=\"HK\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"HK\"><a:fillStyleLst>$fill$fill$fill</a:fillStyleLst><a:lnStyleLst>" +
            "<a:ln w=\"6350\">$fill</a:ln><a:ln w=\"12700\">$fill</a:ln><a:ln w=\"19050\">$fill</a:ln></a:lnStyleLst>" +
            "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
            "<a:bgFillStyleLst>$fill$fill$fill</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"
    }

    fun writePptx(imgs: List<Jpeg>, os: OutputStream) {
        val z = ZipOutputStream(os)
        val n = imgs.size
        z.put(
            "[Content_Types].xml",
            XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
                "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
                "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
                "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
                (1..n).joinToString("") { "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } +
                "</Types>",
        )
        z.put("_rels/.rels", rels(Triple("rId1", "$NS_R/officeDocument", "ppt/presentation.xml")))

        val sw = 12_192_000L; val sh = 6_858_000L
        z.put(
            "ppt/presentation.xml",
            XML + "<p:presentation xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">" +
                "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>" +
                (1..n).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"rId${it + 1}\"/>" } +
                "</p:sldIdLst><p:sldSz cx=\"$sw\" cy=\"$sh\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>",
        )
        z.put(
            "ppt/_rels/presentation.xml.rels",
            rels(
                *(listOf(Triple("rId1", "$NS_R/slideMaster", "slideMasters/slideMaster1.xml")) +
                    (1..n).map { Triple("rId${it + 1}", "$NS_R/slide", "slides/slide$it.xml") } +
                    Triple("rId${n + 2}", "$NS_R/theme", "theme/theme1.xml")).toTypedArray()
            ),
        )
        z.put(
            "ppt/slideMasters/slideMaster1.xml",
            XML + "<p:sldMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>" +
                "<p:spTree>$GRP</p:spTree></p:cSld>" +
                "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
                "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>",
        )
        z.put(
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            rels(Triple("rId1", "$NS_R/slideLayout", "../slideLayouts/slideLayout1.xml"), Triple("rId2", "$NS_R/theme", "../theme/theme1.xml")),
        )
        z.put(
            "ppt/slideLayouts/slideLayout1.xml",
            XML + "<p:sldLayout xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\" type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$GRP</p:spTree></p:cSld>" +
                "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>",
        )
        z.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels(Triple("rId1", "$NS_R/slideMaster", "../slideMasters/slideMaster1.xml")))
        z.put("ppt/theme/theme1.xml", theme())

        for ((i, im) in imgs.withIndex()) {
            val idx = i + 1
            val margin = 300_000.0
            val s = min((sw - 2 * margin) / im.w, (sh - 2 * margin) / im.h)
            val cx = (im.w * s).toLong(); val cy = (im.h * s).toLong()
            val x = (sw - cx) / 2; val y = (sh - cy) / 2
            z.put(
                "ppt/slides/slide$idx.xml",
                XML + "<p:sld xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld>" +
                    "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"000000\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree>$GRP" +
                    "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Picture $idx\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
                    "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                    "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>" +
                    "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>",
            )
            z.put(
                "ppt/slides/_rels/slide$idx.xml.rels",
                rels(Triple("rId1", "$NS_R/slideLayout", "../slideLayouts/slideLayout1.xml"), Triple("rId2", "$NS_R/image", "../media/image$idx.jpeg")),
            )
            z.put("ppt/media/image$idx.jpeg", im.bytes)
        }
        z.finish()
    }
}
