package com.hk.gallery

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

sealed class Incoming {
    class View(val uri: Uri, val mime: String?) : Incoming()
    class Search(val q: String) : Incoming()
    class Go(val to: String) : Incoming()
}

private fun parseIntent(i: Intent?): Incoming? {
    i ?: return null
    i.getStringExtra("gk_go")?.let { return Incoming.Go(it) }
    return when (i.action) {
        Intent.ACTION_VIEW -> i.data?.let { Incoming.View(it, i.type) }
        Intent.ACTION_SEARCH, "com.google.android.gms.actions.SEARCH_ACTION" ->
            Incoming.Search(i.getStringExtra("query") ?: i.getStringExtra(android.app.SearchManager.QUERY) ?: "")
        else -> null
    }
}

private fun copyToCache(ctx: android.content.Context, uri: Uri, mime: String): File? = runCatching {
    var name = "document"
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
    val ext = when {
        mime.contains("pdf") -> "pdf"; mime.contains("wordprocessingml") -> "docx"; mime.contains("presentationml") -> "pptx"
        mime.contains("spreadsheetml") -> "xlsx"; mime.contains("csv") -> "csv"
        else -> name.substringAfterLast('.', "").ifEmpty { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "" }
    }
    if (!name.endsWith(".$ext", true)) name = "$name.$ext"
    val dir = File(ctx.cacheDir, "open").apply { mkdirs() }
    val out = File(dir, name.replace('/', '_'))
    ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
    out
}.getOrNull()

class MainActivity : FragmentActivity() {
    private val incoming = mutableStateOf<Incoming?>(null)
    private var credCb: Pair<() -> Unit, (String) -> Unit>? = null
    private val credLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = credCb; credCb = null
        if (r.resultCode == Activity.RESULT_OK) cb?.first?.invoke() else cb?.second?.invoke("Cancelled")
    }

    /** Asks for the phone's PIN, pattern or password directly (used when the system prompt is not available). */
    fun requestCredential(title: String, onOk: () -> Unit, onFail: (String) -> Unit) {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        val i = if (km != null && km.isDeviceSecure) km.createConfirmDeviceCredentialIntent(title, "Your private files stay encrypted on this phone") else null
        if (i == null) { onFail("Set a screen lock or fingerprint on this phone to use the Private album."); return }
        credCb = onOk to onFail
        credLauncher.launch(i)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = parseIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = (application as HkApp).store
        incoming.value = parseIntent(intent)
        setContent { HkTheme { Root(store, incoming, this@MainActivity) } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val iconSel: ImageVector) {
    Library("Library", Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    Albums("Albums", Icons.Outlined.Collections, Icons.Filled.Collections),
    People("People", Icons.Outlined.Face, Icons.Filled.Face),
    Search("Search", Icons.Outlined.Search, Icons.Filled.Search),
    Files("Files", Icons.Outlined.Folder, Icons.Filled.Folder),
}

private class ViewerState(val items: List<Media>, val index: Int)
private class Conv(val kind: String, val progress: Float, val file: File?, val error: String?)

@Composable
private fun Root(store: Store, incoming: MutableState<Incoming?>, act: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val perms = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else if (Build.VERSION.SDK_INT <= 29) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun has() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED } ||
        (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    var granted by remember { mutableStateOf(has()) }
    var allFiles by remember { mutableStateOf(Compat.hasAllFiles(ctx)) }
    var skipAll by remember { mutableStateOf(Prefs.flag("skip_all_files")) }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = has(); if (granted) store.refreshAndIndex()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = has(); allFiles = Compat.hasAllFiles(ctx)
        if (granted) store.refreshAndIndex()
    }
    fun askAllFiles() {
        if (Build.VERSION.SDK_INT < 30) { askPerm.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)); return }
        val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    if (!granted) { Welcome { askPerm.launch(perms) }; return }

    var tab by remember { mutableStateOf(Tab.Library) }
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    var fileView by remember { mutableStateOf<File?>(null) }
    var editing by remember { mutableStateOf<Media?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var conv by remember { mutableStateOf<Conv?>(null) }
    var filesVersion by remember { mutableIntStateOf(0) }
    val libState = rememberLazyGridState()
    val sel = remember { Sel() }
    var trimming by remember { mutableStateOf<Media?>(null) }
    var showVault by remember { mutableStateOf(false) }
    var editingDoc by remember { mutableStateOf<File?>(null) }
    var searchSeed by remember { mutableStateOf("") }
    val picking = remember { act.intent?.action == Intent.ACTION_PICK || act.intent?.action == Intent.ACTION_GET_CONTENT }
    val open: OpenViewer = { list, i ->
        if (i >= 0) {
            if (picking) {
                act.setResult(Activity.RESULT_OK, Intent().setData(list[i].uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                act.finish()
            } else viewer = ViewerState(list, i)
        }
    }
    fun unlockVault() = authenticate(act, "Private album", { showVault = true }, { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() })
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { showVault = false }

    var update by remember { mutableStateOf<Updater.Info?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    fun checkUpdate(manual: Boolean) {
        scope.launch {
            val info = Updater.latest()
            if (info != null && Updater.isNewer(info.version, Updater.currentVersion(ctx))) {
                if (manual || Prefs.str("skip_update") != info.version) update = info
            } else if (manual) Toast.makeText(ctx, if (info == null) "Couldn't check for updates" else "You're up to date", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - Prefs.lng("last_update_check") > 12 * 3600_000L) {
            Prefs.setLng("last_update_check", System.currentTimeMillis())
            checkUpdate(false)
        }
    }

    val inc = incoming.value
    LaunchedEffect(inc) {
        when (inc) {
            is Incoming.View -> {
                val mime = inc.mime ?: ctx.contentResolver.getType(inc.uri) ?: ""
                if (mime.startsWith("image/") || mime.startsWith("video/")) {
                    val found = if (inc.uri.authority == "media") store.media.value.firstOrNull { it.uri == inc.uri } else null
                    val m = found ?: Media(inc.uri.hashCode().toLong(), inc.uri, mime.startsWith("video/"), System.currentTimeMillis(), 0, 0, 0L, "", "photo", 0L)
                    viewer = ViewerState(listOf(m), 0)
                } else {
                    val f = withContext(Dispatchers.IO) { copyToCache(ctx, inc.uri, mime) }
                    if (f != null) fileView = f
                }
                incoming.value = null
            }
            is Incoming.Search -> { searchSeed = inc.q; tab = Tab.Search; incoming.value = null }
            is Incoming.Go -> {
                when (inc.to) { "search" -> tab = Tab.Search; "files" -> tab = Tab.Files; "private" -> unlockVault() }
                incoming.value = null
            }
            null -> {}
        }
    }



    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { viewer = null; sel.clear(); store.refreshMediaOnly() }
    }

    /** Delete with no OS prompt when All-files access is on; otherwise falls back to the system confirmation. */
    fun delete(items: List<Media>) {
        if (items.isEmpty()) return
        if (Compat.hasAllFiles(ctx)) {
            scope.launch(Dispatchers.IO) {
                val n = store.deleteDirect(items)
                scope.launch { Toast.makeText(ctx, "Deleted $n item${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show() }
            }
            viewer = null; sel.clear()
        } else if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(ctx, "Allow file access in Settings to delete photos", Toast.LENGTH_LONG).show()
        } else {
            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, items.map { it.uri })
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

    fun hide(items: List<Media>) {
        scope.launch(Dispatchers.IO) {
            val done = Vault.hide(ctx, items)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, if (done.isEmpty()) "Couldn't hide these photos" else "Moved ${done.size} to Private", Toast.LENGTH_SHORT).show()
                if (done.isNotEmpty()) delete(done)
            }
        }
        sel.clear()
    }

    fun convert(kind: String) {
        val items = sel.list.toList()
        conv = Conv(kind, 0f, null, null)
        scope.launch(Dispatchers.IO) {
            try {
                val f = Converters.convert(ctx, items, kind) { d, t -> conv = Conv(kind, d / t.toFloat(), null, null) }
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
                conv = Conv(kind, 1f, f, null)
                filesVersion++
                sel.clear()
            } catch (e: Throwable) {
                conv = Conv(kind, 0f, null, e.message ?: "Conversion failed")
            }
        }
    }

    BackHandler(sel.active) { sel.clear() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GlassRoot(
            Modifier.fillMaxSize(),
            background = {
                AnimatedContent(
                    tab, Modifier.fillMaxSize(), label = "tabs",
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(100)) },
                ) { t ->
                    when (t) {
                        Tab.Library -> LibraryScreen(store, open, sel, libState) { showSettings = true }
                        Tab.Albums -> AlbumsScreen(store, open, sel) { unlockVault() }
                        Tab.People -> PeopleScreen(store, open, sel)
                        Tab.Search -> SearchScreen(store, open, sel, searchSeed)
                        Tab.Files -> FilesScreen(allFiles, { askAllFiles() }, { showSettings = true }, { fileView = it }, filesVersion) { filesVersion++ }
                    }
                }
            },
            overlay = {
                AnimatedContent(
                    sel.active, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), label = "bars",
                    transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 }) togetherWith (fadeOut(tween(120)) + slideOutVertically(tween(200)) { it / 2 }) },
                ) { selecting ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        if (!selecting) TabBar(tab) { tab = it }
                        else {
                            val photos = sel.list.count { !it.isVideo }
                            Glass(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                                Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                                    ActionBtn(Icons.Outlined.IosShare, "Share") {
                                        val uris = ArrayList(sel.list.map { it.uri })
                                        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        ctx.startActivity(Intent.createChooser(i, null))
                                    }
                                    ActionBtn(Icons.Outlined.PictureAsPdf, "PDF", enabled = photos > 0) { convert("pdf") }
                                    ActionBtn(Icons.Outlined.Slideshow, "Slides", enabled = photos > 0) { convert("pptx") }
                                    ActionBtn(Icons.Outlined.Description, "Word", enabled = photos > 0) { convert("docx") }
                                    ActionBtn(Icons.Outlined.Lock, "Hide") { hide(sel.list.toList()) }
                                    ActionBtn(Icons.Outlined.DeleteOutline, "Delete", tint = Color(0xFFFF453A)) { delete(sel.list.toList()) }
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    sel.active, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
                ) {
                    Glass(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                        Row(Modifier.padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${sel.list.size} selected", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            TextButton(onClick = { sel.clear() }) { Text("Cancel", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary) }
                        }
                    }
                }
            },
        )

        AnimatedVisibility(viewer != null, enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.92f), exit = fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.92f)) {
            viewer?.let { v -> Viewer(v.items, v.index, store, onClose = { viewer = null }, onDelete = { m -> delete(listOf(m)) }, onEdit = { m -> if (m.isVideo) trimming = m else editing = m }) }
        }
        AnimatedVisibility(editing != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(140))) {
            editing?.let { m -> CropScreen(m, onClose = { editing = null }, onSaved = { editing = null; viewer = null; store.refreshMediaOnly() }) }
        }
        AnimatedVisibility(trimming != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(140))) {
            trimming?.let { m -> TrimScreen(m, onClose = { trimming = null }, onSaved = { trimming = null; viewer = null; store.refreshMediaOnly() }) }
        }
        AnimatedVisibility(editingDoc != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(140))) {
            editingDoc?.let { f ->
                if (f.extension.lowercase() in setOf("xlsx", "csv", "tsv")) SheetEditorScreen(f, onClose = { editingDoc = null }, onSaved = { out -> editingDoc = null; fileView = out; filesVersion++ })
                else DocEditorScreen(f, onClose = { editingDoc = null }, onSaved = { out -> editingDoc = null; fileView = out; filesVersion++ })
            }
        }
        AnimatedVisibility(showVault, enter = fadeIn(tween(200)), exit = fadeOut(tween(140))) {
            VaultScreen(onClose = { showVault = false }, onChanged = { store.refreshMediaOnly() })
        }
        AnimatedVisibility(fileView != null, enter = fadeIn(tween(200)) + slideInVertically(tween(280)) { it / 12 }, exit = fadeOut(tween(160))) {
            fileView?.let { f -> FileViewer(f, onEdit = { editingDoc = f }) { fileView = null } }
        }
        AnimatedVisibility(showSettings, enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(260)), exit = slideOutVertically(tween(220)) { it / 6 } + fadeOut(tween(180))) {
            SettingsScreen(store, allFiles, { askAllFiles() }, { checkUpdate(true) }) { showSettings = false }
        }
    }

    update?.let { u ->
        AlertDialog(
            onDismissRequest = { if (progress == null) update = null },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("GalleryKnife ${u.version} is ready (you have ${Updater.currentVersion(ctx)}).", fontSize = 14.sp)
                    if (u.notes.isNotBlank()) Text(u.notes.take(400), Modifier.padding(top = 10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                    progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().padding(top = 14.dp)) }
                }
            },
            confirmButton = {
                TextButton(enabled = progress == null, onClick = {
                    progress = 0f
                    scope.launch {
                        val f = Updater.download(ctx, u) { progress = it }
                        progress = null
                        if (f != null) { update = null; Updater.install(ctx, f) }
                        else Toast.makeText(ctx, "Download failed or didn't match. Try again.", Toast.LENGTH_LONG).show()
                    }
                }) { Text(if (progress == null) "Download and install" else "Downloading…", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                if (progress == null) Row {
                    TextButton(onClick = { Prefs.setStr("skip_update", u.version); update = null }) { Text("Skip this version") }
                    TextButton(onClick = { update = null }) { Text("Later") }
                }
            },
        )
    }

    conv?.let { c ->
        AlertDialog(
            onDismissRequest = { if (c.file != null || c.error != null) conv = null },
            title = { Text(if (c.file != null) "Saved" else if (c.error != null) "Couldn't convert" else "Creating ${c.kind.uppercase()}…") },
            text = {
                when {
                    c.file != null -> Text("${c.file.name}\n\nSaved in ${c.file.parentFile?.name}. You'll also find it in Files.")
                    c.error != null -> Text(c.error)
                    else -> Column { LinearProgressIndicator(progress = { c.progress }, Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); Text("${(c.progress * 100).toInt()}%") }
                }
            },
            confirmButton = {
                if (c.file != null) TextButton(onClick = { fileView = c.file; conv = null }) { Text("Open", fontWeight = FontWeight.SemiBold) }
                else if (c.error != null) TextButton(onClick = { conv = null }) { Text("OK") }
            },
            dismissButton = { if (c.file != null) Row { TextButton(onClick = { shareFile(ctx, c.file) }) { Text("Share") }; TextButton(onClick = { conv = null }) { Text("Close") } } },
        )
    }

    if (!allFiles && !skipAll) {
        AlertDialog(
            onDismissRequest = { skipAll = true; Prefs.setFlag("skip_all_files", true) },
            title = { Text("Allow file access?") },
            text = { Text("Lets GalleryKnife delete photos instantly without system prompts, find your PDFs and documents in the Files tab, and save converted files to Documents. Everything stays on your phone.") },
            confirmButton = { TextButton(onClick = { skipAll = true; Prefs.setFlag("skip_all_files", true); askAllFiles() }) { Text("Allow", fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { skipAll = true; Prefs.setFlag("skip_all_files", true) }) { Text("Not now") } },
        )
    }
}

@Composable
private fun TabBar(tab: Tab, onSelect: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Glass(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Box(Modifier.padding(6.dp)) {
            val x by animateDpAsState(66.dp * tab.ordinal, tween(240, easing = FastOutSlowInEasing), label = "indicator")
            Box(
                Modifier.offset(x = x).size(66.dp, 52.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant)
                    .drawBehind { drawLine(Color.White.copy(alpha = 0.10f), androidx.compose.ui.geometry.Offset(14.dp.toPx(), 0f), androidx.compose.ui.geometry.Offset(size.width - 14.dp.toPx(), 0f), 1f) },
            )
            Row {
                Tab.values().forEach { t ->
                    val on = t == tab
                    val c by animateColorAsState(if (on) cs.onBackground else cs.onSurfaceVariant, tween(180), label = "tabc")
                    Column(Modifier.size(66.dp, 52.dp).bouncy({ onSelect(t) }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(if (on) t.iconSel else t.icon, t.label, Modifier.size(20.dp), tint = c)
                        Text(t.label, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = c)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionBtn(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    val c = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Column(Modifier.size(52.dp, 52.dp).bouncy(onClick, enabled), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = c)
        Text(label, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = c)
    }
}

// ------------------------------------------------------------------ Welcome

@Composable
private fun Welcome(onAllow: () -> Unit) {
    val t = rememberInfiniteTransition(label = "emblem")
    val bob by t.animateFloat(-5f, 5f, infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(150.dp).graphicsLayer { translationY = bob }
                .shadow(28.dp, RoundedCornerShape(38.dp), ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color(0xFFB9A27A).copy(alpha = 0.5f))
                .clip(RoundedCornerShape(38.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1E1C1E), Color(0xFF060608)))),
            contentAlignment = Alignment.Center,
        ) { Image(painterResource(R.drawable.orb), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit) }
        Spacer(Modifier.height(28.dp))
        Text("GalleryKnife", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            "Your photos and files, understood.\nEverything is analysed on your phone.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, lineHeight = 24.sp,
        )
        Spacer(Modifier.height(36.dp))
        Button(onClick = onAllow, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Allow photo access", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Footer()
    }
}
