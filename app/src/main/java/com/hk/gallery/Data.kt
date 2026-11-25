package com.hk.gallery

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.MediaStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

class HkApp : Application(), ImageLoaderFactory {
    lateinit var store: Store
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Fonts.init(assets)
        Vault.init(this)
        store = Store(this)
    }

    override fun newImageLoader() = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .crossfade(true)
        .build()
}

data class Media(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val date: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    val folder: String,
    val name: String,
    val size: Long,
) {
    val localDate: LocalDate by lazy { Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate() }
    val isScreenshot: Boolean get() = name.contains("screenshot", true) || folder.contains("screenshot", true)
}

data class PersonUi(val id: Long, val name: String, val count: Int, val cover: File)
data class Progress(val total: Int = 0, val done: Int = 0, val running: Boolean = false)
class Meta(val labels: Set<String>, val ocr: String)

object MediaRepo {
    fun load(ctx: Context): List<Media> {
        val out = ArrayList<Media>()
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
        )
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), proj, sel, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val taken = c.getLong(2)
                val added = c.getLong(3) * 1000
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += Media(
                    id, ContentUris.withAppendedId(base, id), video, if (taken > 0) taken else added,
                    c.getInt(4), c.getInt(5), c.getLong(6), c.getString(7) ?: "", c.getString(8) ?: "", c.getLong(9),
                )
            }
        }
        out.sortByDescending { it.date }
        return out
    }
}

class FaceVec(val id: Long, var personId: Long, val emb: FloatArray, val area: Float)

class FaceRow(val id: Long, val mediaId: Long, val personId: Long, val area: Float)

class HkDb(ctx: Context) : SQLiteOpenHelper(ctx, "hk.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta(mediaId INTEGER PRIMARY KEY, labels TEXT, ocr TEXT)")
        db.execSQL("CREATE TABLE persons(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
        db.execSQL("CREATE TABLE faces(id INTEGER PRIMARY KEY AUTOINCREMENT, mediaId INTEGER, personId INTEGER, emb BLOB, area REAL)")
        db.execSQL("CREATE INDEX faces_person ON faces(personId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS facedone(mediaId INTEGER PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        db.execSQL("CREATE TABLE IF NOT EXISTS facedone(mediaId INTEGER PRIMARY KEY)")
    }

    fun faceDoneIds(): Set<Long> {
        val s = HashSet<Long>()
        readableDatabase.rawQuery("SELECT mediaId FROM facedone", null).use { c -> while (c.moveToNext()) s += c.getLong(0) }
        return s
    }

    fun markFaceDone(id: Long) {
        writableDatabase.insertWithOnConflict("facedone", null, ContentValues().apply { put("mediaId", id) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearFaces() {
        val d = writableDatabase
        d.execSQL("DELETE FROM faces"); d.execSQL("DELETE FROM persons"); d.execSQL("DELETE FROM facedone")
    }

    fun readMeta(): Map<Long, Meta> {
        val m = HashMap<Long, Meta>()
        readableDatabase.rawQuery("SELECT mediaId, labels, ocr FROM meta", null).use { c ->
            while (c.moveToNext()) {
                val l = c.getString(1).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                m[c.getLong(0)] = Meta(l, c.getString(2).orEmpty())
            }
        }
        return m
    }

    fun putMeta(id: Long, labels: Collection<String>, ocr: String) {
        val v = ContentValues().apply {
            put("mediaId", id); put("labels", labels.joinToString(",")); put("ocr", ocr)
        }
        writableDatabase.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun newPerson(): Long = writableDatabase.insert("persons", null, ContentValues().apply { put("name", "") })

    fun insertFace(mediaId: Long, personId: Long, emb: FloatArray, area: Float): Long {
        val bb = ByteBuffer.allocate(emb.size * 4).order(ByteOrder.nativeOrder())
        emb.forEach { bb.putFloat(it) }
        val v = ContentValues().apply {
            put("mediaId", mediaId); put("personId", personId); put("emb", bb.array()); put("area", area)
        }
        return writableDatabase.insert("faces", null, v)
    }

    fun faceRows(): List<FaceRow> {
        val l = ArrayList<FaceRow>()
        readableDatabase.rawQuery("SELECT id, mediaId, personId, area FROM faces", null).use { c ->
            while (c.moveToNext()) l += FaceRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getFloat(3))
        }
        return l
    }

    fun embeddings(): List<Pair<Long, FloatArray>> {
        val l = ArrayList<Pair<Long, FloatArray>>()
        readableDatabase.rawQuery("SELECT personId, emb FROM faces", null).use { c ->
            while (c.moveToNext()) {
                val fb = ByteBuffer.wrap(c.getBlob(1)).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val arr = FloatArray(fb.remaining()); fb.get(arr)
                l += c.getLong(0) to arr
            }
        }
        return l
    }

    fun faceVectors(): List<FaceVec> {
        val l = ArrayList<FaceVec>()
        readableDatabase.rawQuery("SELECT id, personId, emb, area FROM faces", null).use { c ->
            while (c.moveToNext()) {
                val fb = ByteBuffer.wrap(c.getBlob(2)).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val arr = FloatArray(fb.remaining()); fb.get(arr)
                l += FaceVec(c.getLong(0), c.getLong(1), arr, c.getFloat(3))
            }
        }
        return l
    }

    fun setFacePerson(faceId: Long, personId: Long) {
        writableDatabase.execSQL("UPDATE faces SET personId=? WHERE id=?", arrayOf(personId, faceId))
    }

    fun movePersonFacesOfMedia(mediaId: Long, from: Long, to: Long) {
        writableDatabase.execSQL("UPDATE faces SET personId=? WHERE mediaId=? AND personId=?", arrayOf(to, mediaId, from))
    }

    fun dropEmptyPersons() {
        writableDatabase.execSQL("DELETE FROM persons WHERE id NOT IN (SELECT DISTINCT personId FROM faces)")
    }

    fun personNames(): Map<Long, String> {
        val m = HashMap<Long, String>()
        readableDatabase.rawQuery("SELECT id, name FROM persons", null).use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getString(1).orEmpty()
        }
        return m
    }

    fun rename(id: Long, name: String) {
        writableDatabase.update("persons", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    }

    fun merge(from: Long, to: Long) {
        val d = writableDatabase
        d.execSQL("UPDATE faces SET personId=? WHERE personId=?", arrayOf(to, from))
        d.delete("persons", "id=?", arrayOf(from.toString()))
    }
}

class Cent(val sum: FloatArray, var n: Int)

class Store(val app: Application) {
    val db = HkDb(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val facesDir = File(app.filesDir, "faces").also { it.mkdirs() }

    val media = MutableStateFlow<List<Media>>(emptyList())
    val persons = MutableStateFlow<List<PersonUi>>(emptyList())
    val progress = MutableStateFlow(Progress())
    val indexVersion = MutableStateFlow(0)
    val topLabels = MutableStateFlow<List<String>>(emptyList())
    val faceError = MutableStateFlow<String?>(null)

    val lock = Any()
    val meta = HashMap<Long, Meta>()
    private var faceMedia: Map<Long, Set<Long>> = emptyMap()
    private var mediaPeople: Map<Long, Set<Long>> = emptyMap()
    val cents = HashMap<Long, Cent>()
    private var metaLoaded = false
    private var indexer: Indexer? = null

    fun metaOf(id: Long): Meta? = synchronized(lock) { meta[id] }
    fun synchronizedMetaIds(): Set<Long> = synchronized(lock) { meta.keys.toHashSet() }
    fun putMetaMem(id: Long, m: Meta) { synchronized(lock) { meta[id] = m } }

    fun refreshAndIndex() {
        scope.launch {
            media.value = MediaRepo.load(app)
            if (!Prefs.flag("face_v3")) {
                db.clearFaces(); facesDir.listFiles()?.forEach { it.delete() }; Prefs.setFlag("face_v3", true)
            }
            if (!metaLoaded) {
                synchronized(lock) { meta.putAll(db.readMeta()) }
                rebuildCentroids()
                metaLoaded = true
            }
            rebuildPeople()
            rebuildTopLabels()
            if (indexer == null) indexer = Indexer(this@Store)
            indexer!!.run()
        }
    }

    fun rescanFaces() {
        scope.launch {
            db.clearFaces()
            facesDir.listFiles()?.forEach { it.delete() }
            synchronized(lock) { cents.clear() }
            rebuildPeople()
            refreshAndIndex()
        }
    }

    /** Deletes immediately (needs All-files access). Returns number deleted. */
    fun deleteDirect(items: List<Media>): Int {
        var n = 0
        val paths = ArrayList<String>()
        for (m in items) {
            var path: String? = null
            app.contentResolver.query(m.uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) path = c.getString(0)
            }
            var ok = false
            path?.let { p -> if (File(p).delete()) { ok = true; paths += p } }
            if (!ok) ok = runCatching { app.contentResolver.delete(m.uri, null, null) > 0 }.getOrDefault(false)
            if (ok) n++
        }
        if (paths.isNotEmpty()) android.media.MediaScannerConnection.scanFile(app, paths.toTypedArray(), null, null)
        val gone = items.map { it.id }.toSet()
        media.value = media.value.filter { it.id !in gone }
        rebuildPeople()
        return n
    }

    fun refreshMediaOnly() {
        scope.launch { media.value = MediaRepo.load(app); rebuildPeople() }
    }

    private val members = HashMap<Long, ArrayList<FaceVec>>()

    private fun addVec(f: FaceVec) {
        members.getOrPut(f.personId) { ArrayList() } += f
        val c = cents.getOrPut(f.personId) { Cent(FloatArray(f.emb.size), 0) }
        for (i in f.emb.indices) c.sum[i] += f.emb[i]
        c.n++
    }

    /** Loads every stored face embedding so new faces can be matched against real members of each person. */
    private fun rebuildCentroids() {
        val all = db.faceVectors()
        synchronized(lock) {
            cents.clear(); members.clear()
            all.forEach { addVec(it) }
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
    private fun norm(a: FloatArray): Float = sqrt(dot(a, a).coerceAtLeast(1e-9f))

    private fun knnScore(emb: FloatArray, mem: List<FaceVec>): Float {
        val sims = mem.map { dot(emb, it.emb) }.sortedDescending()
        return sims.take(minOf(3, sims.size)).average().toFloat()
    }

    fun registerFace(id: Long, pid: Long, emb: FloatArray, area: Float) = synchronized(lock) { addVec(FaceVec(id, pid, emb, area)) }

    /** Moves one photo's faces out of a person (fixes a wrong match). */
    fun detachFace(pid: Long, mediaId: Long) {
        scope.launch {
            val np = db.newPerson()
            db.movePersonFacesOfMedia(mediaId, pid, np)
            rebuildCentroids(); rebuildPeople()
        }
    }

    /**
     * Groups all stored faces again from their embeddings using the current strictness. Names are kept where a group
     * is mostly one named person. Returns the number of people found.
     */
    fun regroupFaces(onDone: (Int) -> Unit) {
        scope.launch {
            val t = Prefs.faceThreshold / 100f
            val faces = db.faceVectors().sortedByDescending { it.area }
            class Cl(val faces: ArrayList<FaceVec>, val sum: FloatArray)
            val cls = ArrayList<Cl>()
            for (f in faces) {
                var best = -1; var bs = -1f
                cls.indices.map { it to dot(f.emb, cls[it].sum) / norm(cls[it].sum) }.sortedByDescending { it.second }.take(5).forEach { (i, _) ->
                    val s = knnScore(f.emb, cls[i].faces)
                    if (s > bs) { bs = s; best = i }
                }
                if (best >= 0 && bs >= t) { cls[best].faces += f; for (i in f.emb.indices) cls[best].sum[i] += f.emb[i] }
                else cls += Cl(arrayListOf(f), f.emb.copyOf())
            }
            // Merge pass: join groups whose members are close on average.
            var merged = true; var passes = 0
            while (merged && passes < 3) {
                merged = false; passes++
                var i = 0
                while (i < cls.size) {
                    var j = i + 1
                    while (j < cls.size) {
                        val cs = dot(cls[i].sum, cls[j].sum) / (norm(cls[i].sum) * norm(cls[j].sum))
                        if (cs >= t - 0.08f) {
                            val a = cls[i].faces.take(20); val b = cls[j].faces.take(20)
                            var tot = 0f; var n = 0
                            for (x in a) for (y in b) { tot += dot(x.emb, y.emb); n++ }
                            if (n > 0 && tot / n >= t - 0.03f) {
                                cls[i].faces += cls[j].faces
                                for (k in cls[i].sum.indices) cls[i].sum[k] += cls[j].sum[k]
                                cls.removeAt(j); merged = true; continue
                            }
                        }
                        j++
                    }
                    i++
                }
            }
            // Keep old person ids (and their names) for the group that is mostly them.
            val used = HashSet<Long>()
            val assign = ArrayList<Pair<Cl, Long>>()
            for (c in cls.sortedByDescending { it.faces.size }) {
                val vote = c.faces.groupingBy { it.personId }.eachCount().entries.sortedByDescending { it.value }
                val pick = vote.firstOrNull { it.key !in used }?.key
                val pid = if (pick != null && vote.first().key == pick) pick else db.newPerson()
                used += pid
                assign += c to pid
            }
            val d = db.writableDatabase
            d.beginTransaction()
            try {
                assign.forEach { (c, pid) -> c.faces.forEach { if (it.personId != pid) db.setFacePerson(it.id, pid) } }
                db.dropEmptyPersons()
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
            rebuildCentroids(); rebuildPeople()
            withContext(Dispatchers.Main) { onDone(assign.size) }
        }
    }

    fun rebuildPeople() {
        val rows = db.faceRows()
        val names = db.personNames()
        val valid = media.value.mapTo(HashSet()) { it.id }
        val byPerson = rows.groupBy { it.personId }
        val fm = byPerson.mapValues { (_, rs) -> rs.map { it.mediaId }.filter { it in valid }.toSet() }
        val mp = HashMap<Long, MutableSet<Long>>()
        fm.forEach { (pid, ms) -> ms.forEach { mp.getOrPut(it) { HashSet() }.add(pid) } }
        val list = byPerson.mapNotNull { (pid, rs) ->
            val ms = fm[pid].orEmpty()
            if (ms.isEmpty()) null else PersonUi(pid, names[pid].orEmpty(), ms.size, File(facesDir, "${rs.maxBy { it.area }.id}.jpg"))
        }.sortedWith(compareByDescending<PersonUi> { it.name.isNotBlank() }.thenByDescending { it.count })
        synchronized(lock) { faceMedia = fm; mediaPeople = mp }
        persons.value = list
        indexVersion.value++
    }

    fun rebuildTopLabels() {
        val skip = setOf("fun", "event", "leisure", "recreation", "pattern", "font", "text", "material property", "tints and shades", "gesture", "darkness")
        val counts = HashMap<String, Int>()
        synchronized(lock) { meta.values.forEach { m -> m.labels.forEach { if (it !in skip) counts.merge(it, 1, Int::plus) } } }
        topLabels.value = counts.entries.sortedByDescending { it.value }.take(18).map { it.key }
    }

    fun peopleOf(mediaId: Long): List<PersonUi> {
        val ids = synchronized(lock) { mediaPeople[mediaId] }.orEmpty()
        return persons.value.filter { it.id in ids }
    }

    fun mediaForPerson(pid: Long): List<Media> {
        val ids = synchronized(lock) { faceMedia[pid] }.orEmpty()
        return media.value.filter { it.id in ids }
    }

    fun rename(pid: Long, raw: String) {
        val name = raw.trim()
        scope.launch {
            val other = persons.value.firstOrNull { it.id != pid && name.isNotEmpty() && it.name.equals(name, true) }
            if (other != null) merge(pid, other.id) else { db.rename(pid, name); rebuildPeople() }
        }
    }

    fun merge(from: Long, to: Long) {
        db.merge(from, to)
        synchronized(lock) {
            val a = cents.remove(from); val b = cents[to]
            if (a != null && b != null) { for (i in b.sum.indices) b.sum[i] += a.sum[i]; b.n += a.n }
        }
        rebuildPeople()
    }

    /** Picks the person whose closest members look most like this face, or starts a new person. */
    fun assignFace(emb: FloatArray): Long = synchronized(lock) {
        val t = Prefs.faceThreshold / 100f
        var best = -1L; var bs = -1f
        cents.entries.map { (pid, c) -> pid to dot(emb, c.sum) / norm(c.sum) }.sortedByDescending { it.second }.take(5).forEach { (pid, _) ->
            val s = knnScore(emb, members[pid].orEmpty().ifEmpty { return@forEach })
            if (s > bs) { bs = s; best = pid }
        }
        if (best != -1L && bs >= t) best else db.newPerson()
    }

    // ---------------- Smart search ----------------
    fun search(q: String): List<Media> {
        val all = media.value
        val toks = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        var list: List<Media> = all
        val used = HashSet<String>()

        if (toks.any { it == "video" || it == "videos" }) { list = list.filter { it.isVideo }; used += listOf("video", "videos") }
        if (toks.any { it == "screenshot" || it == "screenshots" }) { list = list.filter { it.isScreenshot }; used += listOf("screenshot", "screenshots") }

        val years = toks.filter { it.matches(Regex("(19|20)\\d\\d")) }
        if (years.isNotEmpty()) { list = list.filter { it.localDate.year.toString() in years }; used += years }
        val months = toks.mapNotNull { t ->
            val i = MONTHS.indexOfFirst { it == t || (t.length == 3 && it.startsWith(t)) }
            if (i >= 0) { used += t; i + 1 } else null
        }
        if (months.isNotEmpty()) list = list.filter { it.localDate.monthValue in months }
        val today = LocalDate.now()
        if ("today" in toks) { list = list.filter { it.localDate == today }; used += "today" }
        if ("yesterday" in toks) { list = list.filter { it.localDate == today.minusDays(1) }; used += "yesterday" }

        val people = persons.value
        for (t in toks) {
            if (t in used || t in STOP) continue
            val hit = people.filter { p -> p.name.isNotBlank() && p.name.lowercase().split(Regex("\\s+")).contains(t) }
            if (hit.isNotEmpty()) {
                val ids = hit.map { it.id }
                list = list.filter { m -> synchronized(lock) { mediaPeople[m.id] }?.any { it in ids } == true }
                used += t
            }
        }

        val rest = toks.filter { it !in used && it !in STOP && it.length > 1 }
        if (rest.isEmpty()) return list
        fun matches(m: Media, t: String): Boolean {
            val alts = expand(t)
            val md = metaOf(m.id)
            val hay = (m.name + " " + m.folder).lowercase()
            return alts.any { a ->
                (md != null && (md.labels.contains(a) || md.labels.any { l -> l.contains(a) && a.length > 3 } || (a.length > 2 && md.ocr.contains(a)))) ||
                    (a.length > 2 && hay.contains(a))
            }
        }
        val strict = list.filter { m -> rest.all { matches(m, it) } }
        return if (strict.isNotEmpty() || rest.size == 1) strict else list.filter { m -> rest.any { matches(m, it) } }
    }

    private fun expand(t: String): Set<String> {
        val s = HashSet<String>()
        s += t
        if (t.length > 3 && t.endsWith("s")) s += t.dropLast(1)
        if (t.length > 4 && t.endsWith("es")) s += t.dropLast(2)
        val extra = SYNONYMS[t] ?: SYNONYMS[t.removeSuffix("s")]
        if (extra != null) s += extra
        return s
    }

    companion object {
        val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        val STOP = setOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images", "of", "with", "from", "in", "the", "an", "and", "my", "me", "on", "at", "show", "all", "taken", "for", "to")
        val SYNONYMS = mapOf(
            "pet" to listOf("dog", "cat", "pet"), "puppy" to listOf("dog"), "kitten" to listOf("cat"),
            "sea" to listOf("beach", "ocean", "sea", "water"), "ocean" to listOf("beach", "sea", "water"),
            "mountain" to listOf("mountain", "hill", "highland"), "food" to listOf("food", "dish", "cuisine", "meal", "dessert", "cake", "pizza"),
            "car" to listOf("car", "vehicle", "automotive"), "sunset" to listOf("sunset", "dusk", "sky"), "selfie" to listOf("selfie", "smile"),
            "people" to listOf("smile", "selfie", "people", "crowd"), "flower" to listOf("flower", "plant", "petal"),
            "night" to listOf("night", "darkness", "midnight"), "building" to listOf("building", "architecture", "skyscraper"),
            "city" to listOf("city", "metropolis", "urban", "skyline"), "bike" to listOf("bicycle", "motorcycle", "bike"),
        )
    }
}
