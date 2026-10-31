package io.github.jherobred.lodisave

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.coroutineScope
import androidx.compose.foundation.layout.RowScope
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as pinsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Organizing what LodiSave saved. Albums are folders inside LodiSave's, favorites and the trash are
 * MediaStore's own on Android 11 and up, and LodiSave can change the files it saved without asking.
 */
object Library {
    /** Goes up after each change, so screens showing saves load them again. */
    var changes by mutableIntStateOf(0)
        private set

    fun changed() { changes++ }

    /** An album name a folder can take. */
    fun clean(name: String) = name.replace(Regex("""[\\/:*?"<>|\n]"""), " ").trim().take(60).trim('.', ' ')

    /** The albums: folders files are in, and ones made here that have no files yet. */
    fun albums(ctx: Context, media: List<Saved>) =
        (media.map { it.album } + Prefs(ctx).albums).filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }

    /** Changes each file off the main thread, then has screens load saves again. Files other apps
     *  made change only after the user's yes, which Android asks once for all of them. The number
     *  that failed. */
    suspend fun each(items: List<Saved>, change: (Saved) -> Unit): Int {
        val refused = mutableListOf<Saved>()
        var failed = withContext(Dispatchers.IO) {
            items.count { item ->
                runCatching { change(item) }.onFailure { e ->
                    if (e is SecurityException) refused += item else android.util.Log.w("LodiLibrary", "${item.name}: $e")
                }.isFailure
            }
        }
        if (refused.isNotEmpty() && Consent.ask(refused.map { it.uri })) {
            failed -= withContext(Dispatchers.IO) { refused.count { runCatching { change(it) }.isSuccess } }
        }
        changes++
        return failed
    }

    /** Into an album, or out of all of them with "". It stays under Pictures, Movies or Music. */
    fun move(ctx: Context, item: Saved, album: String) =
        update(ctx, item, MediaStore.MediaColumns.RELATIVE_PATH, item.path.substringBefore('/') + "/LodiSave/" + if (album.isEmpty()) "" else "$album/")

    /** The Gallery's albums: the phone's folders that hold photos and videos, as paths like
     *  "DCIM/Camera/", and ones made here that have no files yet. */
    fun folders(ctx: Context, media: List<Saved>) =
        (media.map { it.path } + Prefs(ctx).folders).filter { it.isNotEmpty() }.distinct().sortedBy { folderName(it).lowercase() }

    /** A folder's own name, the last part of its path. */
    fun folderName(folder: String) = folder.trimEnd('/').substringAfterLast('/')

    /** Into another of the phone's folders. Android keeps photos out of Movies and Music, so a photo
     *  goes to a folder of that name in its own top folder instead. */
    fun moveTo(ctx: Context, item: Saved, folder: String) {
        try {
            update(ctx, item, MediaStore.MediaColumns.RELATIVE_PATH, folder)
        } catch (e: IllegalArgumentException) {
            update(ctx, item, MediaStore.MediaColumns.RELATIVE_PATH, item.path.substringBefore('/') + "/" + folderName(folder) + "/")
        }
    }

    /** A Gallery album with no files yet. Its files go under Pictures, where photos and videos may both live. */
    fun addFolder(ctx: Context, name: String) {
        val prefs = Prefs(ctx)
        prefs.folders += "${android.os.Environment.DIRECTORY_PICTURES}/$name/"
        changes++
    }

    /** A new name, keeping the file's extension. */
    fun rename(ctx: Context, item: Saved, name: String) {
        val ext = item.name.substringAfterLast('.', "")
        update(ctx, item, MediaStore.MediaColumns.DISPLAY_NAME, if (ext.isEmpty()) name else "$name.$ext")
    }

    fun favorite(ctx: Context, item: Saved, on: Boolean) {
        // MediaStore keeps favorites on Android 11 and up, where other galleries see them too.
        val kept = Build.VERSION.SDK_INT >= 30 && runCatching { update(ctx, item, MediaStore.MediaColumns.IS_FAVORITE, if (on) 1 else 0) }.isSuccess
        val prefs = Prefs(ctx)
        prefs.favorites = if (on && !kept) prefs.favorites + "${item.id}" else prefs.favorites - "${item.id}"
    }

    fun trash(ctx: Context, item: Saved, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) return update(ctx, item, MediaStore.MediaColumns.IS_TRASHED, if (on) 1 else 0)
        val prefs = Prefs(ctx)
        prefs.trashed = if (on) prefs.trashed + "${item.id}" else prefs.trashed - "${item.id}"
    }

    /** Deletes a file for good. */
    fun delete(ctx: Context, item: Saved) {
        val gone = if (Build.VERSION.SDK_INT >= 30) ctx.contentResolver.delete(item.uri, trashedToo())
        else ctx.contentResolver.delete(item.uri, null, null)
        check(gone > 0) { "not deleted" }
        val prefs = Prefs(ctx)
        val id = "${item.id}"
        prefs.trashed -= id
        prefs.favorites -= id
        prefs.hiddenFromHome -= id
        prefs.blurred -= id
        prefs.unblurred -= id
    }

    /** Blurs files in the grids, or shows them clear. "all" keeps the ones shown clear, "pick" the ones blurred. */
    fun veil(ctx: Context, items: List<Saved>, on: Boolean) {
        val prefs = Prefs(ctx)
        val ids = items.map { "${it.id}" }.toSet()
        if (prefs.blur == "all") prefs.unblurred = if (on) prefs.unblurred - ids else prefs.unblurred + ids
        else prefs.blurred = if (on) prefs.blurred + ids else prefs.blurred - ids
        changes++
    }

    /** Keeps files off home's Recent, or puts them back. Only LodiSave's own setting, not the file. */
    fun hide(ctx: Context, items: List<Saved>, on: Boolean) {
        val prefs = Prefs(ctx)
        val ids = items.map { "${it.id}" }.toSet()
        prefs.hiddenFromHome = if (on) prefs.hiddenFromHome + ids else prefs.hiddenFromHome - ids
        changes++
    }

    fun addAlbum(ctx: Context, name: String) {
        val prefs = Prefs(ctx)
        prefs.albums += name
        changes++
    }

    /** Renames an album: its files move to a folder with the new name. */
    suspend fun renameAlbum(ctx: Context, media: List<Saved>, from: String, to: String): Int {
        val prefs = Prefs(ctx)
        prefs.albums = prefs.albums - from + to
        return each(media.filter { it.album == from }) { move(ctx, it, to) }
    }

    /** Deletes an album, not its files: they move back out to LodiSave's own folders. */
    suspend fun deleteAlbum(ctx: Context, media: List<Saved>, name: String): Int {
        val prefs = Prefs(ctx)
        prefs.albums -= name
        return each(media.filter { it.album == name }) { move(ctx, it, "") }
    }

    private fun update(ctx: Context, item: Saved, column: String, value: Any) {
        val values = ContentValues().apply { if (value is Int) put(column, value) else put(column, value.toString()) }
        val n = if (Build.VERSION.SDK_INT >= 30) ctx.contentResolver.update(item.uri, values, trashedToo())
        else ctx.contentResolver.update(item.uri, values, null, null)
        check(n > 0) { "not updated" }
    }

    /** Lets a change reach files in the trash too, which MediaStore otherwise leaves out. */
    @RequiresApi(30)
    private fun trashedToo() = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
}

/**
 * Android's own "Allow LodiSave to change these?" for files other apps made, which LodiSave may only
 * change after a yes (Android 11 and up). MainActivity shows it. Android 10 asks file by file, which
 * LodiSave doesn't, so changes there reach LodiSave's own files only.
 */
object Consent {
    var asker: (suspend (List<android.net.Uri>) -> Boolean)? = null

    suspend fun ask(uris: List<android.net.Uri>) = Build.VERSION.SDK_INT >= 30 && withContext(Dispatchers.Main) { asker?.invoke(uris) } == true
}

/** Grids as two a row in their own shapes, like Pinterest, instead of three squares: the Settings choice. */
val LocalPins = compositionLocalOf { false }

/** The blur setting: "off", "all" or "pick". */
val LocalBlur = compositionLocalOf { "off" }

/** Whether a save shows blurred in the grids, by the blur setting and the files blurred or unblurred. */
@Composable
fun rememberVeil(): (Saved) -> Boolean {
    val ctx = LocalContext.current
    val mode = LocalBlur.current
    val changes = Library.changes
    return remember(mode, changes) {
        val prefs = Prefs(ctx)
        when (mode) {
            "all" -> prefs.unblurred.let { clear -> { item: Saved -> "${item.id}" !in clear } }
            "pick" -> prefs.blurred.let { blurred -> { item: Saved -> "${item.id}" in blurred } }
            else -> { _: Saved -> false }
        }
    }
}

private val FILTERS = listOf("all", "photo", "video", "gif", "audio", "fav", "trash")

/** A question with what a yes does. */
private class Ask(val question: String, val yes: () -> Unit)

/** Every save, to look through and organize: kinds, favorites and the trash, albums, search and
 *  sort, and long-press to pick several. `seen` is the save the viewer last showed, to scroll to.
 *  As the `gallery`, it shows the phone's photos and videos instead, its albums the phone's folders.
 *  `bottom` leaves room for the tab bar, which `onPicking` hears to make way for the picking bar. */
@Composable
fun Library(
    copy: Copy, media: List<Saved>, bin: List<Saved>, seen: Long?, onOpen: (List<Long>, Int, Boolean) -> Unit,
    gallery: Boolean = false, bottom: Dp = 0.dp, onPicking: (Boolean) -> Unit = {}, top: @Composable () -> Unit = {},
) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    val label = { album: String -> if (gallery) Library.folderName(album) else album }
    var filter by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableStateOf("new") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var entered by rememberSaveable { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) } // "" makes an album, a name renames that one
    var asking by remember { mutableStateOf<Ask?>(null) }
    var moving by remember { mutableStateOf<List<Saved>?>(null) }
    var menu by remember { mutableStateOf<Long?>(null) } // the tile a hold opened the menu of
    val pins = LocalPins.current
    val veiled = rememberVeil()
    val blurring = LocalBlur.current != "off"
    val snack = remember { SnackbarHostState() }
    val trash = filter == "trash"
    val changes = Library.changes
    val albums = remember(media, changes) { if (gallery) Library.folders(ctx, media) else Library.albums(ctx, media) }
    val hidden = remember(changes) { Prefs(ctx).hiddenFromHome }
    val list = remember(media, bin, filter, sort, query, album) {
        (if (trash) bin else media)
            .filter { trash || album == null || (if (gallery) it.path else it.album) == album }
            .filter {
                when (filter) {
                    "fav" -> it.favorite
                    "all", "trash" -> true
                    else -> it.kind == filter
                }
            }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .let { l ->
                when (sort) {
                    "old" -> l.sortedBy { it.added }
                    "name" -> l.sortedBy { it.name.lowercase() }
                    "size" -> l.sortedByDescending { it.bytes }
                    else -> l
                }
            }
    }
    val chosen = list.filter { it.id in picked }
    // A file the viewer moved on to, back into view, before the grid draws so it can fly back into its tile.
    val grid = rememberLazyGridState()
    val board = rememberLazyStaggeredGridState()
    remember(seen) {
        val at = list.indexOfFirst { it.id == seen }
        // Made to be called while composing: it applies at the grid's next measure, before it draws.
        val first = if (pins) board.firstVisibleItemIndex else grid.firstVisibleItemIndex
        if (at >= 0 && (at < first || at > first + if (pins) 5 else 11)) if (pins) board.requestScrollToItem(at) else grid.requestScrollToItem(at)
        at
    }
    LaunchedEffect(Unit) { delay(700); entered = true }
    LaunchedEffect(picked.isNotEmpty()) { onPicking(picked.isNotEmpty()) }
    BackHandler(picked.isNotEmpty()) { picked = emptyList() }
    BackHandler(picked.isEmpty() && searching) { searching = false; query = "" }

    fun toggle(item: Saved) { picked = if (item.id in picked) picked - item.id else picked + item.id }
    var swiping by remember { mutableStateOf(false) }
    val connectDrive = rememberDriveConnect { message -> scope.launch { snack.showSnackbar(message) } }
    fun done(message: String, undo: (suspend () -> Unit)? = null) = scope.launch {
        snack.currentSnackbarData?.dismiss()
        val r = snack.showSnackbar(message, undo?.let { copy.undo }, duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) undo?.invoke()
    }

    Box(Modifier.fillMaxSize().background(t.ground)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (picked.isNotEmpty()) {
                    RoundButton(Glyph.Close, copy.cancel, { picked = emptyList() })
                    Text(copy.selectedN(picked.size), Modifier.weight(1f), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
                    val every = chosen.size == list.size
                    Text(if (every) copy.pickNone else copy.pickAll, color = t.accent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clip(CircleShape).clickable { picked = if (every) emptyList() else list.map { it.id } }.padding(horizontal = 10.dp, vertical = 8.dp))
                } else {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        BasicTextField(
                            query, { query = it },
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape).background(t.surface).border(1.dp, t.line, CircleShape)
                                .padding(horizontal = 16.dp, vertical = 12.dp).focusRequester(focus),
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = Geist, fontSize = 15.sp, color = t.ink),
                            cursorBrush = SolidColor(t.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            decorationBox = { field -> if (query.isEmpty()) Text(copy.search, color = t.muted, fontFamily = Geist, fontSize = 15.sp); field() },
                        )
                    } else Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(if (trash) copy.trash else album?.let(label) ?: if (gallery) copy.gallery else copy.library,
                            fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(copy.files(list.size), fontFamily = GeistMono, fontSize = 12.sp, color = t.muted)
                    }
                    RoundButton(if (searching) Glyph.Close else Glyph.Search, copy.search, { searching = !searching; if (!searching) query = "" })
                    Box {
                        RoundButton(Glyph.Sort, copy.sort, { sorting = true })
                        DropdownMenu(sorting, { sorting = false }, containerColor = t.surface) {
                            for ((key, label) in listOf("new" to copy.sortNew, "old" to copy.sortOld, "name" to copy.sortName, "size" to copy.sortSize)) {
                                DropdownMenuItem(
                                    { Text(label, fontFamily = Geist, fontWeight = if (key == sort) FontWeight.Bold else FontWeight.Normal, color = if (key == sort) t.accent else t.ink) },
                                    { sort = key; sorting = false },
                                    trailingIcon = { if (key == sort) GlyphIcon(Glyph.Check, tint = t.accent, size = 18.dp) },
                                )
                            }
                        }
                    }
                }
            }

            top()
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The Gallery shows photos and videos, so it has no audio.
                items(if (gallery) FILTERS - "audio" else FILTERS) { key ->
                    val label = when (key) {
                        "photo" -> copy.photos
                        "video" -> copy.videos
                        "gif" -> copy.gifs
                        "audio" -> copy.audio
                        "fav" -> copy.favorites
                        "trash" -> copy.trash
                        else -> copy.all
                    }
                    Chip(label, filter == key, { filter = key; picked = emptyList() }, glyph = if (key == "fav") Glyph.Heart else if (key == "trash") Glyph.Trash else null)
                }
            }
            Spacer(Modifier.size(8.dp))
            if (trash) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(copy.trashNote, Modifier.weight(1f), fontFamily = Geist, fontSize = 13.sp, color = t.muted)
                if (bin.isNotEmpty()) Text(copy.emptyTrash, color = t.bad, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clip(CircleShape).clickable {
                        val all = bin
                        asking = Ask(copy.forGood(all.size)) { scope.launch { done(copy.deleted(all.size - Library.each(all) { Library.delete(ctx, it) })) } }
                    }.padding(horizontal = 10.dp, vertical = 8.dp))
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(albums, key = { it }) { name -> Chip(label(name), album == name, { album = if (album == name) null else name; picked = emptyList() }, Modifier.animateItem(), Glyph.Folder) }
                    item(key = "+") { Chip(copy.newAlbum, false, { naming = "" }, Modifier.animateItem(), Glyph.Plus) }
                }
                // The phone's folders belong to the apps that made them, so the Gallery doesn't rename or remove them.
                if (!gallery) album?.let { name ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(copy.rename, color = t.accent, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            modifier = Modifier.clip(CircleShape).clickable { naming = name }.padding(horizontal = 10.dp, vertical = 8.dp))
                        Text(copy.deleteAlbum, color = t.bad, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            modifier = Modifier.clip(CircleShape).clickable {
                                asking = Ask(copy.deleteAlbumSub(name, media.count { it.album == name })) {
                                    album = null
                                    scope.launch { Library.deleteAlbum(ctx, media, name).let { if (it > 0) done(copy.notDone(it)) } }
                                }
                            }.padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
            }
            Spacer(Modifier.size(8.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                @Composable
                fun tile(i: Int, item: Saved, modifier: Modifier) = Tile(
                    item, if (gallery) "gal" else "lib", picked.isNotEmpty(), item.id in picked, "${item.id}" in hidden, if (entered) -1 else i, modifier,
                    pins, veiled(item), copy, menu == item.id, { menu = null },
                    onVeil = { Library.veil(ctx, listOf(item), !veiled(item)) }, onPick = { toggle(item) },
                    // A press that turned into picking by holding isn't a tap as well.
                    onClick = { if (swiping) Unit else if (picked.isNotEmpty()) toggle(item) else onOpen(list.map { it.id }, i, trash) },
                )
                // With blur on, a hold that lets go in place opens the tile's menu; sliding still picks.
                val hold = if (blurring) { item: Saved -> menu = item.id } else null
                if (list.isEmpty()) Empty(copy, filter, album, query, gallery)
                // Pinterest's phone layout: two columns of tiles in their own shapes, close to the screen's edges.
                else if (pins) LazyVerticalStaggeredGrid(
                    StaggeredGridCells.Fixed(2),
                    Modifier.fillMaxSize().dragToPick(board, { p -> board.layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(p.round()) }?.index },
                        list, { picked }, { picked = it }, { swiping = it }, hold),
                    board,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 120.dp + bottom),
                    verticalItemSpacing = 8.dp, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pinsIndexed(list, key = { _, it -> it.id }) { i, item -> tile(i, item, if (motion) Modifier.animateItem() else Modifier.animateItem(null, null, null)) }
                }
                else LazyVerticalGrid(
                    GridCells.Fixed(3),
                    Modifier.fillMaxSize().dragToPick(grid, { p -> grid.layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(p.round()) }?.index },
                        list, { picked }, { picked = it }, { swiping = it }, hold),
                    grid,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp + bottom),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(list, key = { _, it -> it.id }) { i, item -> tile(i, item, if (motion) Modifier.animateItem() else Modifier.animateItem(null, null, null)) }
                }
            }
        }

        // What to do with the picked files, sliding up while some are picked.
        AnimatedVisibility(
            picked.isNotEmpty(), Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
            enter = if (motion) slideInVertically(floaty()) { it } + fadeIn() else EnterTransition.None,
            exit = if (motion) slideOutVertically(snap()) { it } + fadeOut() else ExitTransition.None,
        ) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(t.surface).border(1.dp, t.line, RoundedCornerShape(28.dp)).padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (trash) {
                    Act(Glyph.Restore, copy.restore) {
                        val these = chosen
                        picked = emptyList()
                        scope.launch { val failed = Library.each(these) { Library.trash(ctx, it, false) }; done(if (failed > 0) copy.notDone(failed) else copy.restored(these.size)) }
                    }
                    Act(Glyph.Trash, copy.deleteForever, t.bad) {
                        val these = chosen
                        asking = Ask(copy.forGood(these.size)) {
                            picked = emptyList()
                            scope.launch { done(copy.deleted(these.size - Library.each(these) { Library.delete(ctx, it) })) }
                        }
                    }
                } else {
                    Act(Glyph.Share, copy.share) { share(ctx, chosen) }
                    // Into the LodiSave folder in the user's Google Drive, once Drive is connected.
                    Act(Glyph.Cloud, copy.toDrive) {
                        val these = chosen
                        connectDrive { ok ->
                            if (!ok) return@connectDrive
                            picked = emptyList()
                            Drive.queue(ctx, these)
                            done(copy.queuedToDrive(these.size))
                        }
                    }
                    Act(Glyph.Folder, copy.move) { moving = chosen }
                    val loved = chosen.isNotEmpty() && chosen.all { it.favorite }
                    Act(if (loved) Glyph.HeartFull else Glyph.Heart, if (loved) copy.unfavorite else copy.favorite) {
                        val these = chosen
                        picked = emptyList()
                        scope.launch { Library.each(these) { Library.favorite(ctx, it, !loved) } }
                    }
                    // Home shows LodiSave's own saves only, so the Gallery has nothing to hide from it.
                    val off = chosen.isNotEmpty() && chosen.all { "${it.id}" in hidden }
                    if (!gallery) Act(if (off) Glyph.Eye else Glyph.EyeOff, if (off) copy.showOnHome else copy.hideFromHome) {
                        Library.hide(ctx, chosen, !off)
                        picked = emptyList()
                    }
                    Act(Glyph.Trash, copy.delete) {
                        val these = chosen
                        picked = emptyList()
                        trashWithUndo(scope, ctx, copy, these, ::done)
                    }
                }
            }
        }
        LodiSnackbar(snack, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = if (picked.isNotEmpty()) 96.dp else 16.dp + bottom))
    }

    naming?.let { name ->
        NameDialog(copy, if (name.isEmpty()) copy.newAlbum else copy.rename, name, { naming = null }) { typed ->
            naming = null
            val clean = Library.clean(typed)
            if (clean.isEmpty() || clean == name) return@NameDialog
            if (gallery) { Library.addFolder(ctx, clean); album = "${android.os.Environment.DIRECTORY_PICTURES}/$clean/" }
            else if (name.isEmpty()) { Library.addAlbum(ctx, clean); album = clean }
            else {
                album = clean
                scope.launch { Library.renameAlbum(ctx, media, name, clean).let { if (it > 0) done(copy.notDone(it)) } }
            }
        }
    }
    asking?.let { ask -> Confirm(copy, ask.question, { asking = null }) { asking = null; ask.yes() } }
    moving?.let { these ->
        AlbumSheet(copy, albums, { moving = null }, label, none = !gallery) { to ->
            moving = null
            picked = emptyList()
            scope.launch {
                val failed = Library.each(these) { if (gallery) Library.moveTo(ctx, it, to) else Library.move(ctx, it, to) }
                done(if (failed > 0) copy.notDone(failed) else copy.moved(these.size, if (to.isEmpty()) copy.noAlbum else label(to)))
            }
        }
    }
}

/** Moves files to the trash and offers Undo, which brings them back. */
fun trashWithUndo(scope: CoroutineScope, ctx: Context, copy: Copy, items: List<Saved>, done: (String, (suspend () -> Unit)?) -> Unit) {
    scope.launch {
        val failed = Library.each(items) { Library.trash(ctx, it, true) }
        if (failed > 0) done(copy.notDone(failed), null)
        else done(copy.trashed(items.size)) { Library.each(items) { Library.trash(ctx, it, false) } }
    }
}

/** Holding a tile picks it, and sliding on from there picks every tile between, scrolling at the
 *  grid's edges, as in a phone's own gallery. `at` finds the tile under a point, and `on` hears
 *  whether a slide is going on. With `hold`, a hold that lets go without sliding goes to it instead. */
private fun Modifier.dragToPick(
    grid: ScrollableState, at: (Offset) -> Int?, list: List<Saved>, picked: () -> List<Long>, pick: (List<Long>) -> Unit, on: (Boolean) -> Unit,
    hold: ((Saved) -> Unit)?,
) =
    pointerInput(list, hold != null) {
        val edge = 64.dp.toPx()
        coroutineScope {
            awaitEachGesture {
                // Watched from the first pass: a tile's own tap handling takes the press, which keeps
                // Compose's long-press detectors from ever seeing it.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var c = down
                    while (c.pressed && (c.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                        c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    }
                    true
                }
                // Let go or scrolled before the hold: a tap or a scroll, for the tile and the grid.
                if (moved != null) return@awaitEachGesture
                val start = at(down.position) ?: return@awaitEachGesture
                val before = picked() - list[start].id
                var sliding = hold == null || picked().isNotEmpty()
                if (sliding) pick(before + list[start].id)
                on(true)
                var scroll: kotlinx.coroutines.Job? = null
                try {
                    while (true) {
                        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        // Taken first, so the grid doesn't scroll and the tile doesn't count the hold as a tap.
                        c.consume()
                        if (!c.pressed) {
                            if (!sliding) hold?.invoke(list[start])
                            break
                        }
                        val y = c.position.y
                        val speed = when {
                            y > size.height - edge -> (y - (size.height - edge)) / 3
                            y < edge -> -(edge - y) / 3
                            else -> 0f
                        }
                        scroll?.cancel()
                        if (speed != 0f) scroll = launch { while (true) { grid.scrollBy(speed); delay(16) } }
                        at(c.position)?.let { k ->
                            if (k != start) sliding = true
                            if (sliding) pick((before + list.subList(minOf(start, k), maxOf(start, k) + 1).map { it.id }).distinct())
                        }
                    }
                } finally {
                    scroll?.cancel()
                    on(false)
                }
            }
        }
    }

@Composable
private fun RowScope.Act(glyph: Glyph, label: String, tint: Color = LocalTokens.current.ink, onClick: () -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GlyphIcon(glyph, tint = tint)
        Text(label, color = if (tint == t.ink) t.muted else tint, fontFamily = Geist, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A save in a grid: its thumbnail, or its video or GIF playing silently, with what kind it is, and
 *  a round checkbox while picking. `from` names the grid, for the thumbnail that flies to the viewer.
 *  As a `pin` it takes its own shape; `veiled`, it shows blurred and stays still. `menu` shows what a
 *  hold offers: blur or unblur it, or pick it. */
@Composable
private fun Tile(
    item: Saved, from: String, picking: Boolean, on: Boolean, offHome: Boolean, order: Int, modifier: Modifier,
    pin: Boolean, veiled: Boolean, copy: Copy, menu: Boolean, onMenuDone: () -> Unit, onVeil: () -> Unit, onPick: () -> Unit, onClick: () -> Unit,
) {
    val t = LocalTokens.current
    val thumb = rememberThumb(item, big = pin)
    val press = remember { MutableInteractionSource() }
    val pressed = pressScale(press)
    val shrink by animateFloatAsState(if (on && LocalMotion.current) 0.86f else 1f, snap(), label = "picked")
    // Pinterest keeps very tall and very wide pins from taking over the column.
    val ratio = if (pin && item.width > 0 && item.height > 0) (item.width.toFloat() / item.height).coerceIn(0.5f, 2f) else 1f
    Enter(order, modifier) {
        Hero("$from${item.id}", Modifier.fillMaxWidth().aspectRatio(ratio).graphicsLayer { scaleX = pressed * shrink; scaleY = pressed * shrink }, RoundedCornerShape(if (on) 22.dp else 16.dp)) {
            Box(Modifier.fillMaxSize().background(t.soft).clickable(press, null, onClick = onClick)) {
                if (thumb != null) Image(thumb, item.name, if (veiled) Modifier.fillMaxSize().blur(24.dp, BlurredEdgeTreatment.Rectangle) else Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (item.kind == "audio") GlyphIcon(Glyph.Music, Modifier.align(Alignment.Center), t.muted, 34.dp)
                if (!veiled) Loop(item, Modifier.fillMaxSize())
                // Android 11 and older can't blur, so the tile is covered instead.
                else Box(Modifier.fillMaxSize().background(if (Build.VERSION.SDK_INT < 31) t.soft else Color.Transparent)) {
                    GlyphIcon(Glyph.EyeOff, Modifier.align(Alignment.Center).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(8.dp), Color.White, 22.dp)
                }
                Badges(item, offHome)
                DropdownMenu(menu, onMenuDone, containerColor = t.surface) {
                    DropdownMenuItem({ Text(if (veiled) copy.unblur else copy.blurIt, fontFamily = Geist, color = t.ink) }, { onMenuDone(); onVeil() },
                        leadingIcon = { GlyphIcon(if (veiled) Glyph.Eye else Glyph.EyeOff, size = 20.dp) })
                    DropdownMenuItem({ Text(copy.pickIt, fontFamily = Geist, color = t.ink) }, { onMenuDone(); onPick() },
                        leadingIcon = { GlyphIcon(Glyph.Check, size = 20.dp) })
                }
                if (picking) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp).clip(CircleShape)
                    .background(if (on) t.pick else Color.Black.copy(alpha = 0.3f)).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    if (on) GlyphIcon(Glyph.Check, tint = Color.White, size = 16.dp)
                }
            }
        }
    }
}

/** What a tile says over its picture: a video's length, GIF, an audio file's name, a heart, hidden from home. */
@Composable
private fun BoxScope.Badges(item: Saved, offHome: Boolean) {
    val shade = Color.Black.copy(alpha = 0.55f)
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (item.kind) {
            "video" -> Text(if (item.duration > 0) clock(item.duration) else "▶", Modifier.clip(CircleShape).background(shade).padding(horizontal = 7.dp, vertical = 2.dp),
                color = Color.White, fontFamily = GeistMono, fontSize = 11.sp)
            "gif" -> Text("GIF", Modifier.clip(CircleShape).background(shade).padding(horizontal = 7.dp, vertical = 2.dp), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            "audio" -> Text(item.name, Modifier.weight(1f, fill = false).clip(CircleShape).background(shade).padding(horizontal = 7.dp, vertical = 2.dp),
                color = Color.White, fontFamily = Geist, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        if (offHome) GlyphIcon(Glyph.EyeOff, Modifier.clip(CircleShape).background(shade).padding(3.dp), Color.White, 18.dp)
        if (item.favorite) GlyphIcon(Glyph.HeartFull, Modifier.clip(CircleShape).background(shade).padding(3.dp), Color.White, 18.dp)
    }
}

@Composable
private fun Empty(copy: Copy, filter: String, album: String?, query: String, gallery: Boolean) {
    val (title, sub) = when {
        query.isNotBlank() -> copy.emptySearch(query.trim()) to ""
        filter == "trash" -> copy.emptyBin to ""
        filter == "fav" -> copy.emptyFav to copy.emptyFavSub
        album != null -> copy.emptyAlbum to if (gallery) "" else copy.emptyAlbumSub
        filter != "all" -> copy.emptyKind to ""
        gallery -> copy.emptyGallery to ""
        else -> copy.emptyAll to copy.none
    }
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Lodi(Pose.Idle, Modifier.size(96.dp), LocalMotion.current)
        Text(title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = LocalTokens.current.ink)
        if (sub.isNotEmpty()) Text(sub, fontFamily = Geist, fontSize = 13.sp, color = LocalTokens.current.muted)
    }
}

@Composable
fun LodiSnackbar(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    SnackbarHost(state, modifier.padding(horizontal = 16.dp)) { data ->
        // Inverted, so the action takes the other theme's accent.
        Snackbar(data, shape = RoundedCornerShape(20.dp), containerColor = t.ink, contentColor = t.ground, actionColor = if (t == Dark) Light.accent else Dark.accent)
    }
}

/** Asks for a name: a new album's, an album's new one, or a file's. */
@Composable
fun NameDialog(copy: Copy, title: String, start: String, onDismiss: () -> Unit, hint: String = copy.albumName, onDone: (String) -> Unit) {
    val t = LocalTokens.current
    var text by remember { mutableStateOf(start) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismiss,
        confirmButton = { Pill(copy.done, { onDone(text) }, enabled = text.isNotBlank()) },
        dismissButton = { Pill(copy.cancel, onDismiss, primary = false) },
        title = { Text(title, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink) },
        text = {
            BasicTextField(
                text, { text = it },
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape).background(t.soft).border(1.dp, t.line, CircleShape)
                    .padding(horizontal = 18.dp, vertical = 14.dp).focusRequester(focus),
                singleLine = true,
                textStyle = TextStyle(fontFamily = Geist, fontSize = 15.sp, color = t.ink),
                cursorBrush = SolidColor(t.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onDone(text) }),
                decorationBox = { field -> if (text.isEmpty()) Text(hint, color = t.muted, fontFamily = Geist, fontSize = 15.sp); field() },
            )
        },
        containerColor = t.surface,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
fun Confirm(copy: Copy, question: String, onDismiss: () -> Unit, yes: String = copy.delete, onYes: () -> Unit) {
    val t = LocalTokens.current
    AlertDialog(
        onDismiss,
        confirmButton = { Pill(yes, onYes) },
        dismissButton = { Pill(copy.cancel, onDismiss, primary = false) },
        text = { Text(question, fontFamily = Geist, fontSize = 15.sp, color = t.ink) },
        containerColor = t.surface,
        shape = RoundedCornerShape(28.dp),
    )
}

/** Where to move files: no album (`none`), one of the albums, shown by `label`, or a new one. */
@Composable
fun AlbumSheet(copy: Copy, albums: List<String>, onDismiss: () -> Unit, label: (String) -> String = { it }, none: Boolean = true, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    var naming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.surface) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text(copy.moveTo, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = t.ink)
            @Composable
            fun Line(glyph: Glyph, text: String, onClick: () -> Unit) = Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlyphIcon(glyph, tint = if (glyph == Glyph.Plus) t.accent else t.ink)
                Text(text, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (glyph == Glyph.Plus) t.accent else t.ink)
            }
            if (none) Line(Glyph.Close, copy.noAlbum) { onPick("") }
            for (name in albums) Line(Glyph.Folder, label(name)) { onPick(name) }
            Line(Glyph.Plus, copy.newAlbum) { naming = true }
        }
    }
    if (naming) NameDialog(copy, copy.newAlbum, "", { naming = false }) { typed ->
        naming = false
        val clean = Library.clean(typed)
        // The Gallery's albums are the phone's folders: a new one goes in Pictures.
        if (clean.isNotEmpty() && !none) { Library.addFolder(ctx, clean); onPick("${android.os.Environment.DIRECTORY_PICTURES}/$clean/") }
        else if (clean.isNotEmpty()) { Library.addAlbum(ctx, clean); onPick(clean) }
    }
}
