package com.hk.gallery

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** MobileFaceNet (TFLite) -> L2-normalised face embedding. Fully on-device. */
class FaceEmbedder(ctx: android.content.Context) {
    var error: String? = null
    private var batch = 1
    private var interp: Interpreter? = null
    private var size = 112
    private var dim = 192

    init {
        try {
            val fd = ctx.assets.openFd("MobileFaceNet.tflite")
            val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val it = Interpreter(buf, Interpreter.Options().setNumThreads(4))
            val s = it.getInputTensor(0).shape()
            batch = s[0].coerceAtLeast(1)
            size = s[1]
            dim = it.getOutputTensor(0).shape().last()
            interp = it
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
            Log.e("HK", "Face model unavailable", e)
        }
    }

    val ready get() = interp != null
    val inputSize get() = size

    /** [face] must be an aligned 112x112 crop. Averages the face and its mirror image for a steadier embedding. */
    fun embed(face: Bitmap): FloatArray? {
        val ip = interp ?: return null
        return try {
            val flip = Bitmap.createBitmap(face, 0, 0, face.width, face.height, android.graphics.Matrix().apply { preScale(-1f, 1f) }, true)
            val bb = ByteBuffer.allocateDirect(4 * size * size * 3 * batch).order(ByteOrder.nativeOrder())
            val imgs = if (batch >= 2) listOf(face, flip) + List(batch - 2) { flip } else listOf(face)
            for (b in imgs) {
                val sc = Bitmap.createScaledBitmap(b, size, size, true)
                val px = IntArray(size * size)
                sc.getPixels(px, 0, size, 0, 0, size, size)
                for (p in px) {
                    bb.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
                    bb.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
                    bb.putFloat(((p and 0xFF) - 127.5f) / 128f)
                }
            }
            bb.rewind()
            val out = Array(batch) { FloatArray(dim) }
            ip.run(bb, out)
            val v = FloatArray(dim)
            for (row in out.take(2)) for (i in 0 until dim) v[i] += row[i] / row.let { r -> sqrt(r.sumOf { (it * it).toDouble() }.toFloat().coerceAtLeast(1e-9f)) }
            var n = 0f
            for (x in v) n += x * x
            n = sqrt(n.coerceAtLeast(1e-9f))
            for (i in v.indices) v[i] /= n
            v
        } catch (e: Throwable) {
            Log.e("HK", "embed failed", e); null
        }
    }
}

/** Walks the library newest-first and extracts labels, OCR text and faces with on-device models. */
class Indexer(private val store: Store) {
    private val ctx = store.app
    @Volatile private var running = false

    suspend fun run() {
        if (running) return
        running = true
        withContext(Dispatchers.Default) {
            val metaDone = store.synchronizedMetaIds()
            val faceDone = store.db.faceDoneIds()
            val todo = store.media.value.filter { it.id !in metaDone || (!it.isVideo && it.id !in faceDone) }
            store.progress.value = Progress(todo.size, 0, todo.isNotEmpty())
            if (todo.isEmpty()) { running = false; return@withContext }

            val embedder = FaceEmbedder(ctx)
            store.faceError.value = if (embedder.ready) null else (embedder.error ?: "Face model failed to load")
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
            val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setMinFaceSize(0.06f).build()
            )
            try {
                for ((i, m) in todo.withIndex()) {
                    try {
                        one(m, m.id !in metaDone, !m.isVideo && m.id !in faceDone, embedder, labeler, ocr, detector)
                    } catch (e: Throwable) {
                        Log.w("HK", "index failed ${m.id}", e)
                        if (m.id !in metaDone) { store.db.putMeta(m.id, emptyList(), ""); store.putMetaMem(m.id, Meta(emptySet(), "")) }
                        store.db.markFaceDone(m.id)
                    }
                    store.progress.value = Progress(todo.size, i + 1, true)
                    if ((i + 1) % 8 == 0) { store.rebuildPeople(); store.rebuildTopLabels() }
                }
            } finally {
                labeler.close(); ocr.close(); detector.close()
                store.rebuildPeople(); store.rebuildTopLabels()
                store.progress.value = Progress(todo.size, todo.size, false)
                running = false
            }
        }
    }

    private suspend fun one(
        m: Media, needMeta: Boolean, needFace: Boolean, emb: FaceEmbedder,
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        ocr: com.google.mlkit.vision.text.TextRecognizer,
        detector: com.google.mlkit.vision.face.FaceDetector,
    ) {
        var bmp = ctx.contentResolver.loadThumbnail(m.uri, Size(1024, 1024), null)
        if (bmp.config == Bitmap.Config.HARDWARE) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val img = InputImage.fromBitmap(bmp, 0)

        if (needMeta) {
            val labels = labeler.process(img).await().map { it.text.lowercase() }
            val text = ocr.process(img).await().text.lowercase().replace('\n', ' ').take(2500)
            store.db.putMeta(m.id, labels, text)
            store.putMetaMem(m.id, Meta(labels.toSet(), text))
        }
        if (needFace) {
            if (!emb.ready) return // model unavailable: leave unmarked so it retries after a fix
            val raw = detector.process(img).await()
            val faces = raw
                .filter { abs(it.headEulerAngleY) < 35 && abs(it.headEulerAngleX) < 30 && it.boundingBox.width() >= bmp.width * 0.06f }
                .sortedByDescending { it.boundingBox.width() }
                .take(10)
            Log.i("HK", "media ${m.id}: ${raw.size} detected, ${faces.size} used")
            for (f in faces) {
                val aligned = alignFace(bmp, f, emb.inputSize) ?: continue
                val e = emb.embed(aligned) ?: continue
                val crop = cropSquare(bmp, f.boundingBox)
                val pid = store.assignFace(e)
                val area = f.boundingBox.width().toFloat() * f.boundingBox.height() / (bmp.width.toFloat() * bmp.height)
                val fid = store.db.insertFace(m.id, pid, e, area)
                store.registerFace(fid, pid, e, area)
                FileOutputStream(File(store.facesDir, "$fid.jpg")).use {
                    Bitmap.createScaledBitmap(crop, 200, 200, true).compress(Bitmap.CompressFormat.JPEG, 88, it)
                }
            }
            store.db.markFaceDone(m.id)
        }
    }

    /** Rotates and scales the face so both eyes land where the recognition model expects them. */
    private fun alignFace(src: Bitmap, f: com.google.mlkit.vision.face.Face, size: Int): Bitmap? {
        val a = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position ?: return null
        val b = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position ?: return null
        val (p1, p2) = if (a.x <= b.x) a to b else b to a
        val dx = p2.x - p1.x; val dy = p2.y - p1.y
        val d = kotlin.math.hypot(dx, dy)
        if (d < 8f) return null
        val k = size / 112f
        val q1x = 38.29f * k; val q1y = 51.69f * k; val q2x = 73.53f * k; val q2y = 51.50f * k
        val s = kotlin.math.hypot(q2x - q1x, q2y - q1y) / d
        val ang = Math.toDegrees((kotlin.math.atan2(q2y - q1y, q2x - q1x) - kotlin.math.atan2(dy, dx)).toDouble()).toFloat()
        val m = android.graphics.Matrix().apply { postTranslate(-p1.x, -p1.y); postRotate(ang); postScale(s, s); postTranslate(q1x, q1y) }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun cropSquare(src: Bitmap, box: Rect): Bitmap {
        val side = (max(box.width(), box.height()) * 1.3f).toInt().coerceIn(16, min(src.width, src.height))
        val l = (box.centerX() - side / 2).coerceIn(0, src.width - side)
        val t = (box.centerY() - side / 2).coerceIn(0, src.height - side)
        return Bitmap.createBitmap(src, l, t, side, side)
    }
}
