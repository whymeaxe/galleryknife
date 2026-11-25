package com.hk.gallery

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Private album for photos and videos. Every file is encrypted in 1 MB blocks with AES-256-GCM using a key held in the
 * Android Keystore (hardware-backed where the phone supports it) and stored in the app's private storage. The index of
 * names and the thumbnails are encrypted too. Opening the album needs fingerprint, face or the screen lock.
 */
object Vault {
    class Entry(val id: String, val name: String, val mime: String, val date: Long) { val video get() = mime.startsWith("video/") }

    val entries = mutableStateListOf<Entry>()
    private lateinit var dir: File
    private const val ALIAS = "gk_private_album_key"
    private const val CHUNK = 1 shl 20
    private val index get() = File(dir, "index.enc")

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "private").apply { mkdirs() }
        clearTemp(ctx)
        runCatching { load() }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
        )
        return gen.generateKey()
    }

    private fun readFully(i: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = i.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }

    /** Streams [input] into an encrypted file, one authenticated block at a time. */
    private fun encryptTo(input: InputStream, out: File) {
        DataOutputStream(out.outputStream().buffered()).use { o ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = readFully(input, buf)
                if (n <= 0) break
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                val enc = c.doFinal(buf, 0, n)
                o.write(c.iv.size); o.write(c.iv); o.writeInt(enc.size); o.write(enc)
                if (n < CHUNK) break
            }
        }
    }

    private fun decryptTo(file: File, out: OutputStream) {
        DataInputStream(file.inputStream().buffered()).use { i ->
            while (true) {
                val ivLen = i.read()
                if (ivLen < 0) break
                val iv = ByteArray(ivLen); i.readFully(iv)
                val enc = ByteArray(i.readInt()); i.readFully(enc)
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
                out.write(c.doFinal(enc))
            }
        }
    }

    private fun decryptBytes(file: File): ByteArray = ByteArrayOutputStream().also { decryptTo(file, it) }.toByteArray()

    private fun load() {
        entries.clear()
        if (!index.exists()) return
        val arr = JSONArray(String(decryptBytes(index)))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            entries += Entry(o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("date"))
        }
    }

    private fun save() {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("date", it.date)) }
        encryptTo(ByteArrayInputStream(arr.toString().toByteArray()), index)
    }

    /** Encrypts the photos and videos into the album and returns the ones that were safely stored. */
    fun hide(ctx: Context, items: List<Media>): List<Media> {
        val done = ArrayList<Media>()
        for (m in items) {
            runCatching {
                val id = UUID.randomUUID().toString()
                ctx.contentResolver.openInputStream(m.uri)!!.use { encryptTo(it, File(dir, "$id.bin")) }
                runCatching {
                    val t = Compat.thumbnail(ctx, m.uri, m.isVideo, 512)
                    val jpg = ByteArrayOutputStream().also { t.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                    encryptTo(ByteArrayInputStream(jpg), File(dir, "$id.thumb"))
                }
                val mime = ctx.contentResolver.getType(m.uri) ?: if (m.isVideo) "video/mp4" else "image/jpeg"
                entries += Entry(id, m.name.ifBlank { if (m.isVideo) "video.mp4" else "photo.jpg" }, mime, m.date)
                done += m
            }
        }
        if (done.isNotEmpty()) runCatching { save() }
        return done
    }

    private fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / s > maxDim * 2 || o.outHeight / s > maxDim * 2) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun thumb(e: Entry): Bitmap? = runCatching {
        val f = File(dir, "${e.id}.thumb")
        if (f.exists()) decode(decryptBytes(f), 360) else if (!e.video) decode(decryptBytes(File(dir, "${e.id}.bin")), 360) else null
    }.getOrNull()

    fun bitmap(e: Entry, maxDim: Int): Bitmap? = runCatching { decode(decryptBytes(File(dir, "${e.id}.bin")), maxDim) }.getOrNull()

    /** Decrypts a video to a temporary file for playback. Temporary files are wiped when the album closes. */
    fun videoFile(ctx: Context, e: Entry): File {
        val d = File(ctx.cacheDir, "vault_play").apply { mkdirs() }
        val f = File(d, "${e.id}.mp4")
        if (!f.exists()) f.outputStream().buffered().use { decryptTo(File(dir, "${e.id}.bin"), it) }
        return f
    }

    fun clearTemp(ctx: Context) { File(ctx.cacheDir, "vault_play").deleteRecursively() }

    fun restore(ctx: Context, e: Entry): Boolean = runCatching {
        val o = Compat.openOut(ctx, e.name, e.mime, e.video, e.date)!!
        o.stream.use { decryptTo(File(dir, "${e.id}.bin"), it) }
        o.finish()
        remove(e)
        true
    }.getOrDefault(false)

    fun remove(e: Entry) {
        File(dir, "${e.id}.bin").delete()
        File(dir, "${e.id}.thumb").delete()
        entries.remove(e)
        runCatching { save() }
    }
}

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Asks for fingerprint, face or the screen lock. Older Android versions support fewer combinations, so we try each. */
fun authenticate(act: FragmentActivity, title: String, onSuccess: () -> Unit, onFail: (String) -> Unit) {
    val bm = BiometricManager.from(act)
    val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
    val weak = BiometricManager.Authenticators.BIOMETRIC_WEAK
    val cred = BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val auth = listOf(strong or cred, weak or cred, cred, weak)
        .firstOrNull { bm.canAuthenticate(it) == BiometricManager.BIOMETRIC_SUCCESS }
    if (auth == null) {
        // Android 9 and 10 cannot always use the system prompt with only a PIN or pattern: ask for the screen lock directly.
        (act as? MainActivity)?.requestCredential(title, onSuccess, onFail) ?: onFail("Set a screen lock or fingerprint on this phone to use the Private album.")
        return
    }
    val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onFail(errString.toString()) }
    })
    val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Your private files stay encrypted on this phone").setAllowedAuthenticators(auth)
    if (auth and cred == 0) info.setNegativeButtonText("Cancel")
    prompt.authenticate(info.build())
}

@Composable
fun VaultScreen(onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = onClose)
    var open by remember { mutableStateOf<Int?>(null) }

    // Hide the album from screenshots and the recent-apps preview while it is open, and wipe temporary video files after.
    DisposableEffect(Unit) {
        val w = ctx.findActivity()?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); Vault.clearTemp(ctx) }
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        val list = Vault.entries
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp).height(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ChevronLeft, null, Modifier.size(20.dp), tint = cs.tertiary)
                        Text("Albums", color = cs.tertiary, fontSize = 15.sp)
                    }
                    Text("Private", Modifier.padding(start = 20.dp, top = 4.dp), style = MaterialTheme.typography.displayLarge)
                    MetaLine("${list.size} items", "Encrypted", modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 16.dp))
                }
            }
            if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 32.dp, vertical = 48.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(32.dp), tint = cs.onSurfaceVariant)
                    Text("Nothing here yet", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    Text(
                        "Select photos or videos in the Library and tap Hide. They are encrypted, moved here and removed from the gallery.",
                        Modifier.padding(top = 6.dp), color = cs.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            items(list.size, key = { list[it].id }) { i ->
                val e = list[i]
                val bmp by produceState<ImageBitmap?>(null, e.id) { value = withContext(Dispatchers.IO) { Vault.thumb(e)?.asImageBitmap() } }
                Box(Modifier.aspectRatio(1f).background(cs.surfaceVariant).clickable { open = i }) {
                    bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (e.video) Icon(Icons.Filled.PlayArrow, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp), tint = Color.White)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Footer() }
        }

        open?.let { start -> VaultViewer(start, onClose = { open = null }, onChanged = onChanged) }
    }
}

@Composable
private fun VaultViewer(start: Int, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = onClose)
    val list = Vault.entries
    if (list.isEmpty()) { onClose(); return }
    val pager = rememberPagerState(start.coerceIn(0, list.lastIndex)) { list.size }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = list[pager.currentPage.coerceIn(0, list.lastIndex)]

    Box(Modifier.fillMaxSize().background(cs.background)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 16.dp, key = { list.getOrNull(it)?.id ?: it }) { page ->
            val e = list.getOrNull(page)
            if (e != null && e.video) {
                val file by produceState<File?>(null, e.id) { value = withContext(Dispatchers.IO) { runCatching { Vault.videoFile(ctx, e) }.getOrNull() } }
                val f = file
                if (f != null && page == pager.currentPage) {
                    VideoPlayer(Uri.fromFile(f), Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 84.dp).background(Color.Black), insets = true)
                } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            } else {
                val bmp by produceState<ImageBitmap?>(null, e?.id) { value = withContext(Dispatchers.IO) { e?.let { Vault.bitmap(it, 2200)?.asImageBitmap() } } }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) { IconBtn(Icons.Outlined.ChevronLeft, "Back", onClose) }
        Glass(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.size(84.dp, 52.dp).bouncy({
                    val ok = Vault.restore(ctx, current)
                    Toast.makeText(ctx, if (ok) "Restored to your gallery" else "Couldn't restore", Toast.LENGTH_SHORT).show()
                    if (ok) onChanged()
                }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.LockOpen, "Restore", Modifier.size(20.dp), tint = cs.onSurface)
                    Text("Restore", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.size(84.dp, 52.dp).bouncy({ confirmDelete = true }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.DeleteOutline, "Delete", Modifier.size(20.dp), tint = Color(0xFFFF453A))
                    Text("Delete", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFF453A))
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete permanently?") },
            text = { Text("This is only stored here. Deleting it cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; Vault.remove(current) }) { Text("Delete", color = Color(0xFFFF453A)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
