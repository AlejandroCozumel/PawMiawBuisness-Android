package com.pawmiaw.business.services

import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

//
//  SvcOutbox.kt (iOS SvcOutbox.swift)
//
//  Domicilio without signal (web: hooks/use-home-visit-outbox.ts, the outbox
//  and "the day kept" in lib/home-visits.ts). Every Arrive, Finish, home-stay
//  arrival and leave, and a walk's route, is written to this phone first — so
//  the card flips at once and a dead zone loses nothing — then sent in the
//  order it was pressed, with the time it was pressed. Today's list as it last
//  loaded is kept too, to read the day with no signal. Both are kept per
//  business and person, emptied as the server takes each press, and dropped at
//  sign-out. The app sends while it is open (no background sending).
//

// MARK: - What this phone keeps (the app's no-backup folder)

object SvcDeviceStore {
    private val folder: File?
        get() {
            val base = runCatching { AppContext.context.noBackupFilesDir }.getOrNull() ?: return null
            val dir = File(base, "PawMiawNegocios/HomeVisits")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private fun file(name: String): File? {
        val safe = name.map { if (it.isLetterOrDigit() || "-_.".contains(it)) it else '_' }.joinToString("")
        return folder?.let { File(it, safe) }
    }

    fun read(name: String): ByteArray? {
        val file = file(name) ?: return null
        return runCatching { if (file.exists()) file.readBytes() else null }.getOrNull()
    }

    fun write(name: String, data: ByteArray?) {
        val file = file(name) ?: return
        if (data != null) {
            // Atomic: a temporary file renamed over the old one.
            runCatching {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeBytes(data)
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }
        } else {
            runCatching { file.delete() }
        }
    }

    /** Every file this page leaves on a staff phone (sign-out). */
    fun clearAll() {
        val dir = folder ?: return
        runCatching { dir.deleteRecursively() }
    }
}

// MARK: - Whether the phone has signal

object SvcConnectivity {
    var online: Boolean by mutableStateOf(true)
        private set

    private var started = false
    private val main = Handler(Looper.getMainLooper())

    /** Starts listening (the first read of `shared`). */
    val shared: SvcConnectivity
        get() {
            start()
            return this
        }

    private fun start() {
        if (started) return
        started = true
        val manager = runCatching { AppContext.context.getSystemService(ConnectivityManager::class.java) }.getOrNull() ?: return
        online = manager.activeNetwork?.let { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val capable = manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
                    main.post { update(capable) }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val capable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    main.post { update(capable) }
                }

                override fun onLost(network: Network) {
                    main.post { update(false) }
                }
            })
        }
    }

    private fun update(satisfied: Boolean) {
        if (satisfied == online) return
        online = satisfied
        // With the signal back, a press doesn't sit out the wait its last failed try left it.
        if (satisfied) SvcOutbox.wakeAll()
    }
}

// MARK: - One press waiting to be sent

@Serializable
data class SvcOutboxEntry(
    val id: String,
    /** arrive, finish, stay_arrive, stay_leave, route_points. */
    val kind: String,
    /** The card it belongs to (`a_…` / `s_…`). */
    val itemId: String,
    val path: String,
    val body: Map<String, JSONValue>,
    /** When the button was pressed: what the server stamps. */
    val pressedAt: String,
    /** locating, queued, sending, failed. */
    val state: String,
    val attempts: Int,
    /** Not before this moment (the wait after a failed try), epoch milliseconds. */
    val nextAt: Long? = null,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val isPress: Boolean get() = kind != "route_points"
}

/** A card's unsent press: it waits for its position, it is saved, or it is on its way. */
enum class SvcPending {
    locating, saved, sending;

    /**
     * Only the seconds a press takes to get its position or its answer hold the card's next button:
     * a walk that starts and ends in a dead zone is still finished on time.
     */
    val holdsNextPress: Boolean get() = this != saved
}

class SvcOutbox private constructor(private val key: String?) {
    var entries: List<SvcOutboxEntry> by mutableStateOf(emptyList())
        private set

    private var flushing = false
    private var timer: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        val saved = key?.let { SvcDeviceStore.read(it) }?.let {
            runCatching { API.json.decodeFromString(ListSerializer(SvcOutboxEntry.serializer()), it.toString(Charsets.UTF_8)) }.getOrNull()
        }
        entries = saved?.let { recover(it) } ?: emptyList()
    }

    companion object {
        /** A press older than this isn't tried again on its own (12 h, in seconds). */
        const val maxAge: Long = 12 * 3600

        private val stores = HashMap<String, SvcOutbox>()
        private var watchingSignOut = false
        /** Kept strongly: SharedPreferences holds its listeners weakly. */
        private var signOutListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

        /** One queue per business and person, shared by everything that reads it. */
        fun store(clinicId: String?, userId: String?): SvcOutbox {
            watchSignOut()
            if (clinicId == null || userId == null) {
                stores["none"]?.let { return it }
                val loose = SvcOutbox(null)
                stores["none"] = loose
                return loose
            }
            val key = "outbox.v1.$clinicId.$userId"
            stores[key]?.let { return it }
            val store = SvcOutbox(key)
            stores[key] = store
            store.flushSoon()
            return store
        }

        fun wakeAll() { stores.values.forEach { it.wake() } }
        fun flushAll() { stores.values.forEach { it.flushSoon() } }

        /** The session ended: whatever this page kept on the phone goes with it. */
        private fun watchSignOut() {
            if (watchingSignOut) return
            watchingSignOut = true
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, changed ->
                if (changed != null && changed != "pawmiaw_negocios_user") return@OnSharedPreferenceChangeListener
                if (prefs.contains("pawmiaw_negocios_user")) return@OnSharedPreferenceChangeListener
                if (stores.isEmpty()) return@OnSharedPreferenceChangeListener
                stores.clear()
                SvcDeviceStore.clearAll()
            }
            signOutListener = listener
            AppContext.defaults.registerOnSharedPreferenceChangeListener(listener)
            // Back in front: whatever was waiting goes.
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) flushAll()
            })
        }

        /** What a restart finds: a press still waiting for its position goes without one; one caught mid-send is sent again. */
        private fun recover(saved: List<SvcOutboxEntry>): List<SvcOutboxEntry> = saved.map { entry ->
            when (entry.state) {
                "locating" -> entry.copy(state = "queued", body = entry.body + ("position" to VisitPosition(status = "timeout").json))
                "sending" -> entry.copy(state = "queued")
                else -> entry
            }
        }

        /** Why a press could not be saved, in the reader's language. */
        fun reason(entry: SvcOutboxEntry): String {
            val code = entry.errorCode
            if (code != null) {
                val sentence = SvcRefusals.homeVisits[code] ?: SvcRefusals.common[code]
                if (sentence != null) return L(sentence)
            }
            entry.error.nonEmpty?.let { return it }
            if (ageSeconds(entry.pressedAt) >= maxAge) return L("It was pressed more than 12 hours ago")
            return L("Something went wrong. Try again.")
        }

        private fun ageSeconds(pressedAt: String): Long {
            val at = DateText.parse(pressedAt) ?: Instant.now()
            return (Instant.now().toEpochMilli() - at.toEpochMilli()) / 1000
        }
    }

    // MARK: Reading

    /** Presses on this phone not yet with the server (route batches aren't presses). */
    val waiting: List<SvcOutboxEntry> get() = entries.filter { it.isPress && it.state != "failed" }
    val failed: List<SvcOutboxEntry> get() = entries.filter { it.isPress && it.state == "failed" }

    val pending: Map<String, SvcPending>
        get() {
            val out = HashMap<String, SvcPending>()
            for (entry in waiting) {
                out[entry.itemId] = when (entry.state) {
                    "locating" -> SvcPending.locating
                    "sending" -> SvcPending.sending
                    else -> SvcPending.saved
                }
            }
            return out
        }

    /**
     * The day as the person left it: a press not sent yet already shows on its card at the time it was pressed.
     * A failed press shows nothing — the card is as the server has it.
     */
    fun apply(items: List<SvcHomeVisitItem>): List<SvcHomeVisitItem> {
        val presses = waiting
        if (presses.isEmpty()) return items
        return items.map { item ->
            var next = item
            for (entry in presses) {
                if (entry.itemId != item.id) continue
                if ((entry.kind == "arrive" || entry.kind == "stay_arrive") && (next.phase == "upcoming" || next.phase == "on_my_way")) {
                    next = next.copy(
                        phase = "in_progress",
                        arrivedAt = entry.pressedAt,
                        late = false,
                        lateMinutes = 0,
                        actions = SvcHomeActions(onMyWay = false, arrive = false, finish = true, reassign = false),
                    )
                } else if ((entry.kind == "finish" || entry.kind == "stay_leave") && next.phase == "in_progress") {
                    next = next.copy(
                        phase = "done",
                        finishedAt = entry.pressedAt,
                        runningLong = false,
                        actions = SvcHomeActions(onMyWay = next.actions?.onMyWay, arrive = next.actions?.arrive, finish = false, reassign = next.actions?.reassign),
                    )
                }
            }
            next
        }
    }

    // MARK: Writing

    /** A press: written first, sent after. With `position` it waits for it (8 s at most — the locator's own limit). */
    fun add(
        kind: String,
        itemId: String,
        path: String,
        body: Map<String, JSONValue> = emptyMap(),
        pressedAt: Instant = Instant.now(),
        position: (suspend () -> VisitPosition)? = null,
    ) {
        val id = "${Instant.now().toEpochMilli()}-${UUID.randomUUID().toString().uppercase().take(8)}"
        val at = DateText.iso(pressedAt)
        val payload = body.toMutableMap()
        // The press time is the visit's time, however late it is sent.
        if (kind != "route_points") payload["at"] = JsonPrimitive(at)
        val entry = SvcOutboxEntry(
            id = id, kind = kind, itemId = itemId, path = path, body = payload, pressedAt = at,
            state = if (position == null) "queued" else "locating", attempts = 0,
        )
        set(entries + entry)
        if (position != null) {
            scope.launch {
                val answer = position()
                set(entries.map { current ->
                    if (current.id != id || current.state != "locating") current
                    else current.copy(state = "queued", body = current.body + ("position" to answer.json))
                })
                flushSoon()
            }
        } else {
            flushSoon()
        }
    }

    fun retry(id: String) {
        set(entries.map { entry ->
            if (entry.id != id) entry else entry.copy(state = "queued", nextAt = null, error = null, errorCode = null)
        })
        flushSoon()
    }

    /** "Discard": the press is forgotten, with whatever of the same visit was waiting behind it. */
    fun discard(id: String) {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = entries[index]
        set(entries.filterIndexed { offset, entry ->
            entry.id != id && !(entry.itemId == target.itemId && offset > index && entry.isPress)
        })
    }

    /** The press was settled another way (the start gate sent it): only that entry goes. */
    fun resolve(id: String) {
        set(entries.filter { it.id != id })
        flushSoon()
    }

    /** The signal is back: whatever was waiting out a failed try may go now. */
    fun wake() {
        set(entries.map { entry ->
            if (entry.state != "queued" || entry.nextAt == null) entry else entry.copy(nextAt = null)
        })
        flushSoon()
    }

    /** Resolves when nothing of this visit's kind is waiting any more, or after `timeout` seconds. */
    suspend fun settled(itemId: String, kind: String, timeout: Double) {
        val limit = System.currentTimeMillis() + (timeout * 1000).toLong()
        while (System.currentTimeMillis() < limit) {
            if (entries.none { it.itemId == itemId && it.kind == kind && it.state != "failed" }) return
            delay(200)
        }
    }

    // MARK: Sending

    fun flushSoon() {
        scope.launch { flush() }
    }

    /** Sends what may go now, one at a time, in the order pressed; a visit's failed press holds the rest of it. */
    suspend fun flush() {
        if (flushing) return
        flushing = true
        try {
            while (true) {
                val entry = nextToSend(System.currentTimeMillis()) ?: break
                set(entries.map { if (it.id == entry.id) it.copy(state = "sending") else it })
                val outcome = send(entry)
                settle(entry.id, outcome)
                if (!coroutineContext.isActive) break
            }
        } finally {
            flushing = false
            schedule()
        }
    }

    private sealed class Outcome {
        data object sent : Outcome()
        data object retry : Outcome()
        data class failed(val message: String?, val code: String?) : Outcome()
    }

    private fun nextToSend(now: Long): SvcOutboxEntry? {
        val seen = HashSet<String>()
        for (entry in entries) {
            if (entry.itemId in seen) continue
            seen.add(entry.itemId)
            if (entry.state == "queued" && (entry.nextAt ?: Long.MIN_VALUE) <= now) return entry
        }
        return null
    }

    private suspend fun send(entry: SvcOutboxEntry): Outcome {
        val fresh = ageSeconds(entry.pressedAt) < maxAge
        return try {
            val data = JsonObject(entry.body).toString().toByteArray()
            API.data("POST", entry.path, emptyMap(), data)
            if (entry.isPress) announceDataChanged()
            Outcome.sent
        } catch (error: APIError) {
            // A stay visit already arrived (or already left) is done.
            if (entry.kind == "stay_arrive" && error.status == 409 && error.code == "visit_closed") {
                announceDataChanged()
                return Outcome.sent
            }
            if (entry.kind == "stay_leave" && error.status == 409 && error.code == "visit_not_started") {
                announceDataChanged()
                return Outcome.sent
            }
            val transient = error.isOffline || error.status >= 500 || error.status == 408 || error.status == 429
            if (transient) return if (fresh) Outcome.retry else Outcome.failed(L("It was pressed more than 12 hours ago"), null)
            // A route batch the server won't take (the walk closed, routes off): nobody can act on it.
            if (!entry.isPress) return Outcome.sent
            Outcome.failed(error.message, error.code)
        } catch (error: CancellationException) {
            Outcome.retry
        } catch (error: Exception) {
            if (fresh) Outcome.retry else Outcome.failed(null, null)
        }
    }

    private fun settle(id: String, outcome: Outcome) {
        set(entries.mapNotNull { entry ->
            if (entry.id != id) return@mapNotNull entry
            val attempts = entry.attempts + 1
            when (outcome) {
                Outcome.sent -> null
                // 2 s, 4 s, 8 s … up to a minute.
                Outcome.retry -> entry.copy(
                    attempts = attempts,
                    state = "queued",
                    nextAt = System.currentTimeMillis() + (min(60.0, 2 * 2.0.pow(max(0, attempts - 1).toDouble())) * 1000).toLong(),
                )
                is Outcome.failed -> entry.copy(attempts = attempts, state = "failed", nextAt = null, error = outcome.message, errorCode = outcome.code)
            }
        })
    }

    /** Never later than 20 s: a signal that came back unnoticed is still found. */
    private fun schedule() {
        timer?.cancel()
        val waits = entries.filter { it.state == "queued" }.map { it.nextAt ?: Long.MIN_VALUE }
        val soonest = waits.minOrNull() ?: return
        val inSeconds = if (soonest == Long.MIN_VALUE) 0.0 else (soonest - System.currentTimeMillis()) / 1000.0
        val delaySeconds = min(20.0, max(0.25, inSeconds))
        timer = scope.launch {
            delay((delaySeconds * 1000).toLong())
            flush()
        }
    }

    private fun set(next: List<SvcOutboxEntry>) {
        entries = next
        val key = key ?: return
        SvcDeviceStore.write(
            key,
            if (next.isEmpty()) null else runCatching {
                API.json.encodeToString(ListSerializer(SvcOutboxEntry.serializer()), next).toByteArray()
            }.getOrNull(),
        )
    }
}

// MARK: - The day kept for when the signal drops

@Serializable
data class SvcCachedDay(
    val savedAt: String,
    val day: SvcHomeVisitsDay,
)

object SvcDayCache {
    private fun key(clinicId: String, userId: String): String = "day.v1.$clinicId.$userId"

    /** Only today is kept, and only the list as the server sent it (it carries no access values). */
    fun save(day: SvcHomeVisitsDay, clinicId: String?, userId: String?) {
        if (day.isToday != true || clinicId == null || userId == null) return
        val data = runCatching {
            API.json.encodeToString(SvcCachedDay.serializer(), SvcCachedDay(savedAt = DateText.iso(Instant.now()), day = day)).toByteArray()
        }.getOrNull()
        SvcDeviceStore.write(key(clinicId, userId), data)
    }

    /** A kept day is shown only while it is still its day. */
    fun read(clinicId: String?, userId: String?): SvcCachedDay? {
        if (clinicId == null || userId == null) return null
        val data = SvcDeviceStore.read(key(clinicId, userId)) ?: return null
        val kept = runCatching { API.json.decodeFromString(SvcCachedDay.serializer(), data.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        val today = DateText.dayKey(Instant.now(), SvcTime.zone(kept.day.timeZone))
        return if (kept.day.date == today) kept else null
    }
}

// MARK: - What the card says about its presses

/** A press on this phone not yet with the server: said quietly, on its card (web `PendingLine`). */
@Composable
fun SvcPendingLine(pending: SvcPending, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.mango, 20.0), RoundedCornerShape(Theme.boxRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pending == SvcPending.saved) {
            Glyph("cloud-off", 16.dp, tint = Theme.inkStrong)
        } else {
            CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkStrong, strokeWidth = 2.dp)
        }
        Text(
            when (pending) {
                SvcPending.saved -> L("Saved · will send when there’s signal")
                SvcPending.locating -> L("Getting your location…")
                SvcPending.sending -> L("Sending…")
            },
            style = Theme.text(14, FontWeight.Bold),
            color = Theme.inkStrong,
        )
    }
}

/**
 * A press the server refused (web `OutboxRow`): what it said, and what can be done — try again,
 * forget it, or (a missing consent, a deposit) settle that first. The card is back to how the server has it.
 */
@Composable
fun SvcOutboxRow(
    entry: SvcOutboxEntry,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onOpenVisit: (() -> Unit)? = null,
    onReviewConsents: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val gate = entry.errorCode == "consent_required" || entry.errorCode == "deposit_required"
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.destructive, 8.0), shape)
            .border(1.5.dp, Theme.mix(Theme.destructive, 45.0, Theme.borderSoft), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Glyph("triangle-alert", 16.dp, tint = Theme.destructive, modifier = Modifier.padding(top = 2.dp))
            Text(LF("Couldn’t be saved: %@", SvcOutbox.reason(entry)), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        }
        FlowLayout(spacing = 8.dp) {
            if (gate && onReviewConsents != null) {
                PMButton(L("Review consents"), systemImage = "signature", action = onReviewConsents)
            } else {
                PMButton(L("Try again"), systemImage = "rotate-cw", action = onRetry)
            }
            if (gate && onOpenVisit != null) {
                PMButton(L("Open visit"), systemImage = "external-link", variant = ButtonVariant.outline, action = onOpenVisit)
            }
            PMButton(L("Discard"), systemImage = "trash-2", variant = ButtonVariant.outline, action = onDiscard)
        }
    }
}

/** Said at the top of the list when the phone has no signal or presses are still waiting (web `OfflineBanner`). */
@Composable
fun SvcOfflineBanner(
    online: Boolean,
    waiting: Int,
    failed: Int,
    /** The time of the list shown from this phone's copy; null when it is live. */
    cachedAt: String?,
    modifier: Modifier = Modifier,
) {
    if (online && waiting == 0 && failed == 0 && cachedAt == null) return
    val offline = !online || cachedAt != null
    val shape = RoundedCornerShape(Theme.boxRadius)
    CompositionLocalProvider(LocalContentColor provides Theme.inkStrong) {
        Column(
            modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.mango, 22.0), shape)
                .border(1.5.dp, Theme.mix(Theme.mango, 70.0, Theme.borderSoft), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowLayout(spacing = 8.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        offline -> Glyph("cloud-off", 16.dp)
                        failed > 0 && waiting == 0 -> Glyph("triangle-alert", 16.dp)
                        else -> CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkStrong, strokeWidth = 2.dp)
                    }
                    Text(
                        cachedAt?.let { LF("Offline · showing what was loaded at %@", it) }
                            ?: if (offline) L("You’re offline") else if (waiting > 0) L("Sending…") else LF("%d couldn’t be saved", failed),
                        style = Theme.text(14, FontWeight.ExtraBold),
                        color = Theme.inkStrong,
                    )
                }
                if (waiting > 0) BannerPill(LF("%d waiting to send", waiting))
                if (failed > 0 && (offline || waiting > 0)) BannerPill(LF("%d couldn’t be saved", failed))
            }
            if (offline) {
                Text(
                    L("The app keeps working without signal: arrivals and finishes are saved and sent when the signal is back. Keep the app open until they go."),
                    style = Theme.text(12, FontWeight.SemiBold),
                    color = Theme.inkStrong.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun BannerPill(text: String) {
    Text(
        text,
        style = brandText(12, FontWeight.ExtraBold).copy(fontFeatureSettings = "tnum"),
        color = Theme.inkStrong,
        modifier = Modifier
            .background(Theme.surface, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
