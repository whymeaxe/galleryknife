package com.hk.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias OpenViewer = (List<Media>, Int) -> Unit

private val BottomPad = 120.dp

@Composable
private fun ProgressPill(p: Progress) {
    if (!p.running) return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        MetaLine("Analysing ${p.done} / ${p.total}")
        ProgressLine(if (p.total == 0) 0f else p.done.toFloat() / p.total, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun LargeTitle(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displayLarge)
            if (subtitle != null) MetaLine(*subtitle.split(" / ").toTypedArray(), modifier = Modifier.padding(top = 8.dp))
        }
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------- Library

@Composable
fun LibraryScreen(store: Store, open: OpenViewer, sel: Sel, gridState: LazyGridState, onSettings: () -> Unit) {
    val media by store.media.collectAsState()
    val prog by store.progress.collectAsState()
    var cols by remember { mutableIntStateOf(4) }
    val groups = remember(media) { media.groupBy { it.localDate }.toList() }
    val photos = media.count { !it.isVideo }
    val videos = media.size - photos
    val byId = remember(media) { media.associateBy { it.id } }

    LazyVerticalGrid(
        GridCells.Fixed(cols), Modifier.fillMaxSize().mediaGestures(gridState, sel, byId) { m -> open(media, media.indexOfFirst { it.id == m.id }) },
        state = gridState,
        contentPadding = PaddingValues(bottom = BottomPad),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                LargeTitle("Library", "$photos photos / $videos videos") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconBtn(Icons.Outlined.GridView, "Grid size") { cols = if (cols >= 5) 2 else cols + 1 }
                        IconBtn(Icons.Outlined.Tune, "Settings", onSettings)
                    }
                }
                ProgressPill(prog)
            }
        }
        groups.forEach { (date, list) ->
            item(key = "h$date", span = { GridItemSpan(maxLineSpan) }) {
                Text(dayLabel(date), Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp), fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
            items(list, key = { it.id }) { m ->
                Thumb(m, sel, Modifier.animateItem())
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Footer() }
    }
}

// ---------------------------------------------------------------- Albums

@Composable
fun AlbumsScreen(store: Store, open: OpenViewer, sel: Sel, onVault: () -> Unit) {
    val media by store.media.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    val albums = remember(media) { media.groupBy { it.folder.ifBlank { "Other" } }.toList().sortedByDescending { it.second.size } }
    val favs = Prefs.favs
    val smart = remember(media, favs) {
        listOf("Favorites" to media.filter { it.id in favs }, "Videos" to media.filter { it.isVideo }, "Screenshots" to media.filter { it.isScreenshot }).filter { it.second.isNotEmpty() }
    }
    BackHandler(selected != null) { selected = null }

    if (selected != null) {
        val list = (albums + smart).firstOrNull { it.first == selected }?.second.orEmpty()
        PhotoGrid(selected!!, "${list.size} items", list, open, sel, onBack = { selected = null })
        return
    }
    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = BottomPad),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.offset(x = (-20).dp)) { LargeTitle("Albums") } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val cs = MaterialTheme.colorScheme
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface).border(0.5.dp, cs.outline, RoundedCornerShape(14.dp)).bouncy(onVault).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = cs.tertiary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Private", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text("Encrypted, opens with your fingerprint or screen lock", fontSize = 13.sp, color = cs.onSurfaceVariant, lineHeight = 18.sp)
                }
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
        }
        if (smart.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Media types", style = MaterialTheme.typography.titleLarge) }
        items(smart, key = { "s" + it.first }) { (n, l) -> AlbumCard(n, l) { selected = n } }
        item(span = { GridItemSpan(maxLineSpan) }) { Text("My albums", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleLarge) }
        items(albums, key = { it.first }) { (n, l) -> AlbumCard(n, l) { selected = n } }
        item(span = { GridItemSpan(maxLineSpan) }) { Footer() }
    }
}

@Composable
private fun AlbumCard(name: String, list: List<Media>, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.clickable(onClick = onClick)) {
        val req = remember(list.first().id) { ImageRequest.Builder(ctx).data(list.first().uri).size(500).build() }
        AsyncImage(
            req, null,
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Text(name, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${list.size}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PhotoGrid(title: String, subtitle: String, list: List<Media>, open: OpenViewer, sel: Sel, onBack: () -> Unit, header: @Composable (() -> Unit)? = null) {
    val gridState = rememberLazyGridState()
    val byId = remember(list) { list.associateBy { it.id } }
    LazyVerticalGrid(
        GridCells.Fixed(4), Modifier.fillMaxSize().mediaGestures(gridState, sel, byId) { m -> open(list, list.indexOfFirst { it.id == m.id }) },
        state = gridState, contentPadding = PaddingValues(bottom = BottomPad),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(Modifier.statusBarsPadding().padding(top = 4.dp, start = 4.dp)) {
                    TextButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBackIosNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Back", fontSize = 17.sp)
                    }
                }
                header?.invoke()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
        }
        items(list, key = { it.id }) { m -> Thumb(m, sel, Modifier.animateItem()) }
        item(span = { GridItemSpan(maxLineSpan) }) { Footer() }
    }
}

// ---------------------------------------------------------------- People

@Composable
fun PeopleScreen(store: Store, open: OpenViewer, selection: Sel) {
    val persons by store.persons.collectAsState()
    val prog by store.progress.collectAsState()
    val faceErr by store.faceError.collectAsState()
    var selected by remember { mutableStateOf<Long?>(null) }
    var naming by remember { mutableStateOf<PersonUi?>(null) }
    BackHandler(selected != null) { selected = null }

    val visible = remember(persons) { persons.filter { it.name.isNotBlank() || it.count >= 2 } }

    val person = persons.firstOrNull { it.id == selected }
    if (person != null) {
        var merging by remember { mutableStateOf(false) }
        val list = remember(person.id, store.media.collectAsState().value, persons) { store.mediaForPerson(person.id) }
        PhotoGrid(
            person.name.ifBlank { "Unnamed" }, "${person.count} photos", list, open, selection, onBack = { selected = null },
            header = {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FaceCircle(person.cover, 72.dp)
                    FilledTonalButton(onClick = { naming = person }) { Text(if (person.name.isBlank()) "Add name" else "Rename") }
                    OutlinedButton(onClick = { merging = true }) { Text("Merge") }
                }
            },
        )
        if (merging) {
            AlertDialog(
                onDismissRequest = { merging = false }, confirmButton = {},
                dismissButton = { TextButton(onClick = { merging = false }) { Text("Cancel") } },
                title = { Text("Same person as…") },
                text = {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(persons.filter { it.id != person.id }, key = { it.id }) { p ->
                            Column(Modifier.clickable { store.scope.launch { store.merge(person.id, p.id) }; merging = false; selected = p.id }, horizontalAlignment = Alignment.CenterHorizontally) {
                                FaceCircle(p.cover, 60.dp)
                                Text(p.name.ifBlank { "Unnamed" }, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                },
            )
        }
    } else {
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = BottomPad),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.offset(x = (-20).dp)) { LargeTitle("People", "On this phone only"); ProgressPill(prog) }
            }
            if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (faceErr != null) "Face recognition couldn't start: $faceErr" else if (prog.running) "Looking for faces in your photos…" else "No faces found yet.",
                    Modifier.padding(top = 40.dp).fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(visible, key = { it.id }) { p ->
                Column(Modifier.clickable { selected = p.id }, horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FaceCircle(p.cover, maxWidth) }
                    Text(
                        p.name.ifBlank { "Add name" }, Modifier.padding(top = 8.dp).clickable { naming = p }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        color = if (p.name.isBlank()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                    )
                    Text("${p.count}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        item(span = { GridItemSpan(maxLineSpan) }) { Footer() }
        }
    }

    naming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("Who is this?") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    FaceCircle(p.cover, 96.dp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Name") }, shape = RoundedCornerShape(12.dp))
                }
            },
            confirmButton = { TextButton(onClick = { store.rename(p.id, text); naming = null }) { Text("Save", fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Search

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(store: Store, open: OpenViewer, sel: Sel, seed: String = "") {
    var query by remember { mutableStateOf(seed) }
    LaunchedEffect(seed) { if (seed.isNotBlank()) query = seed }
    var results by remember { mutableStateOf<List<Media>>(emptyList()) }
    val persons by store.persons.collectAsState()
    val labels by store.topLabels.collectAsState()
    val ver by store.indexVersion.collectAsState()
    val media by store.media.collectAsState()
    val prog by store.progress.collectAsState()
    val focus = LocalFocusManager.current

    LaunchedEffect(query, ver, media) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(180)
        results = withContext(Dispatchers.Default) { store.search(query) }
    }

    val gridState = rememberLazyGridState()
    val byId = remember(results) { results.associateBy { it.id } }
    LazyVerticalGrid(
        GridCells.Fixed(4), Modifier.fillMaxSize().mediaGestures(gridState, sel, byId) { m -> focus.clearFocus(); open(results, results.indexOfFirst { it.id == m.id }) },
        state = gridState, contentPadding = PaddingValues(bottom = BottomPad),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                LargeTitle("Search")
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Dog on beach, receipt, Maya…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp, maxLines = 1)
                        BasicTextField(
                            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        )
                    }
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }, Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Cancel, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (query.isBlank()) {
                    val named = persons.filter { it.name.isNotBlank() }
                    if (named.isNotEmpty()) {
                        Text("People", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(named, key = { it.id }) { p ->
                                Column(Modifier.clickable { query = p.name }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    FaceCircle(p.cover, 64.dp); Text(p.name, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    Text("Suggestions", Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium)
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf("Videos", "Screenshots") + labels.map { it.replaceFirstChar { c -> c.uppercase() } }).forEach { s ->
                            Text(
                                s, fontSize = 15.sp,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { query = s }.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (prog.running) ProgressPill(prog)
                } else {
                    Text(
                        if (results.isEmpty()) "No results for “$query”" else "${results.size} results",
                        Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                    )
                    if (results.isEmpty() && prog.running) Text(
                        "GalleryKnife is still learning your library, so results will improve.",
                        Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(results, key = { it.id }) { m -> Thumb(m, sel, Modifier.animateItem()) }
        item(span = { GridItemSpan(maxLineSpan) }) { Footer() }
    }
}
