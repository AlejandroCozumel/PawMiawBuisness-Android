package com.pawmiaw.business

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.lang.reflect.Method

//
//  Full lists load 20 at a time with infinite scroll; counts come from the server's `total`,
//  never from what is loaded (the list loading policy; iOS Paging.swift).
//
//      val pager = remember { Pager<Client> { offset, limit ->
//          API.get("/provider-clients", mapOf("offset" to "$offset", "limit" to "$limit", "q" to search))
//      } }
//      …
//      pager.items.forEach { client -> Row(client, Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(client) }) }
//      PagerFooter(pager)
//      LaunchedEffect(Unit) { pager.reload() }
//
//  A row's identity is its `id` (Swift `Identifiable`): read from the item's `id` property
//  unless `id` is given. SwiftUI `.onAppear` on a row of a lazy list (it fires as the row
//  scrolls in) is `Modifier.onScrolledIntoView`.
//

@Stable
class Pager<Item>(
    val pageSize: Int = 20,
    /** The row's identity (Swift `Identifiable.id`); by default the item's `id` property. */
    val id: (Item) -> Any? = { identityOf(it) },
    var fetch: suspend (offset: Int, limit: Int) -> Page<Item>,
) {
    var items: List<Item> by mutableStateOf(emptyList())
        private set
    var total: Int? by mutableStateOf(null)
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var isLoadingMore: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var loadedOnce: Boolean by mutableStateOf(false)
        private set
    var reachedEnd: Boolean = false
        private set

    private var generation = 0
    private val scope = MainScope()

    /** Starts again from the first page (a new search, a new filter, pull to refresh). */
    suspend fun reload() {
        generation += 1
        val mine = generation
        isLoading = true
        error = null
        try {
            val page = fetch(0, pageSize)
            if (mine != generation) return
            items = page.items
            total = page.total
            reachedEnd = page.hasMore == false || page.items.size < pageSize || (page.total?.let { page.items.size >= it } ?: false)
            loadedOnce = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (mine != generation) return
            error = errorText(failure)
            loadedOnce = true
        } finally {
            if (mine == generation) isLoading = false
        }
    }

    fun loadMoreIfNeeded(item: Item) {
        if (reachedEnd || isLoadingMore || isLoading) return
        val threshold = maxOf(0, items.size - 5)
        val key = id(item)
        val index = items.indexOfFirst { id(it) == key }
        if (index < 0 || index < threshold) return
        scope.launch { loadMore() }
    }

    suspend fun loadMore() {
        if (reachedEnd || isLoadingMore) return
        val mine = generation
        isLoadingMore = true
        try {
            val page = fetch(items.size, pageSize)
            if (mine != generation) return
            val known = items.mapTo(HashSet()) { "${id(it)}" }
            items = items + page.items.filter { "${id(it)}" !in known }
            page.total?.let { total = it }
            reachedEnd = page.hasMore == false || page.items.size < pageSize || (total?.let { items.size >= it } ?: false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = errorText(failure)
        } finally {
            isLoadingMore = false
        }
    }

    /** Replaces one row in place after an edit. */
    fun replace(item: Item) {
        val key = id(item)
        val index = items.indexOfFirst { id(it) == key }
        if (index >= 0) items = items.toMutableList().also { it[index] = item }
    }

    fun remove(id: Any?) {
        items = items.filterNot { this.id(it) == id }
        total?.let { total = maxOf(0, it - 1) }
    }
}

private val idGetters = HashMap<Class<*>, Method?>()

/** An item's `id` property (Swift `Identifiable.id`), else the item itself. */
fun identityOf(item: Any?): Any? {
    if (item == null) return null
    val type = item.javaClass
    val getter = synchronized(idGetters) {
        if (idGetters.containsKey(type)) idGetters[type]
        else runCatching { type.getMethod("getId") }.getOrNull().also { idGetters[type] = it }
    }
    return if (getter != null) runCatching { getter.invoke(item) }.getOrNull() ?: item else item
}

/**
 * SwiftUI `.onAppear` on a row of a lazy list: runs each time the view scrolls into sight
 * (any part of it shows inside everything that clips it), not when it is only composed.
 */
fun Modifier.onScrolledIntoView(action: () -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(action)
    val shown = remember { booleanArrayOf(false) }
    this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val visible = coordinates.isAttached && bounds.width > 0f && bounds.height > 0f
        if (visible && !shown[0]) {
            shown[0] = true
            latest()
        } else if (!visible && shown[0]) {
            shown[0] = false
        }
    }
}

/** The end of a paged list: a spinner while the next 20 load, the error with a retry. */
@Composable
fun <Item> PagerFooter(pager: Pager<Item>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val error = pager.error
    if (pager.isLoadingMore) {
        Box(modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Theme.inkSoft, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
    } else if (error != null && pager.items.isNotEmpty()) {
        Column(
            modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(error, style = Theme.smallFont, color = Theme.coralText)
            PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { pager.loadMore() } }
        }
    }
}

/**
 * The whole state of a paged list in one view: loading, error, empty, rows (10 apart, the
 * next 20 asked for as the last rows scroll in). Sits in the page's scrolling column.
 */
@Composable
fun <Item> PagedContent(
    pager: Pager<Item>,
    modifier: Modifier = Modifier,
    empty: @Composable () -> Unit,
    row: @Composable (Item) -> Unit,
) {
    val error = pager.error
    when {
        !pager.loadedOnce && pager.isLoading -> LoadingState(modifier = modifier)
        error != null && pager.items.isEmpty() -> ErrorState(error, retry = { pager.reload() }, modifier = modifier)
        pager.items.isEmpty() -> empty()
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            pager.items.forEach { item ->
                key(pager.id(item)) {
                    Box(Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(item) }) { row(item) }
                }
            }
            PagerFooter(pager)
        }
    }
}

/** A screen's one-shot load (a detail, a dashboard): loading, error with retry, or the value. */
@Stable
class Loader<Value>(private val fetch: suspend () -> Value) {
    var value: Value? by mutableStateOf(null)
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    suspend fun load() {
        isLoading = true
        error = null
        try {
            value = fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = errorText(failure)
        } finally {
            isLoading = false
        }
    }
}

/** Loading / error / content for a `Loader`. */
@Composable
fun <Value> LoadedContent(
    loader: Loader<Value>,
    modifier: Modifier = Modifier,
    content: @Composable (Value) -> Unit,
) {
    val value = loader.value
    val error = loader.error
    when {
        value != null -> content(value)
        error != null -> ErrorState(error, retry = { loader.load() }, modifier = modifier)
        else -> LoadingState(modifier = modifier)
    }
}
