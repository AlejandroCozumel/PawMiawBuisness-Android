package com.pawmiaw.business.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pawmiaw.business.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

//
//  SvcLocation.kt (iOS SvcLocation.swift)
//
//  A walk's route, on the phone that pressed Arrive and only when the business
//  turned it on (Ajustes de Domicilio; backend docs/home-visits-late-and-notices.md
//  and the web's hooks/use-walk-tracker.ts), while the app is open. Families
//  only ever see the route drawn as a line. The place at "I've arrived" and
//  "Finish" is core/VisitLocation.kt.
//

// MARK: - Which walk this phone records

/** This phone pressed Arrive on this walk: it is the one that records (and the route waits here until sent). */
object SvcWalkTracking {
    private fun flag(appointmentId: String): String = "tracking.$appointmentId"
    fun buffer(appointmentId: String): String = "route.v1.$appointmentId"

    fun mark(appointmentId: String) {
        SvcDeviceStore.write(flag(appointmentId), (System.currentTimeMillis() / 1000.0).toString().toByteArray())
    }

    fun isTracking(appointmentId: String): Boolean = SvcDeviceStore.read(flag(appointmentId)) != null

    /** How long ago (seconds) this phone pressed Arrive on the walk; null when it didn't. */
    fun age(appointmentId: String): Double? {
        val data = SvcDeviceStore.read(flag(appointmentId)) ?: return null
        val since = data.toString(Charsets.UTF_8).toDoubleOrNull()
        if (since == null || since <= 0) return Double.POSITIVE_INFINITY
        return max(0.0, System.currentTimeMillis() / 1000.0 - since)
    }

    fun clear(appointmentId: String) {
        SvcDeviceStore.write(flag(appointmentId), null)
        SvcDeviceStore.write(buffer(appointmentId), null)
    }
}

/** One fix of a walk: the device's time (epoch ms), where, and how precise. */
@Serializable
data class SvcWalkPoint(
    val t: Double,
    val lat: Double,
    val lng: Double,
    val acc: Double,
) {
    val json: JSONValue
        get() = JsonObject(mapOf("t" to JsonPrimitive(t), "lat" to JsonPrimitive(lat), "lng" to JsonPrimitive(lng), "acc" to JsonPrimitive(acc)))

    companion object {
        const val accuracyMax: Double = 40.0
        const val minMeters: Double = 8.0
        const val minSeconds: Double = 20.0

        /** Kept when it is precise enough and the walker moved 8 m, or 20 s went by. */
        fun keep(last: SvcWalkPoint?, fix: SvcWalkPoint): Boolean {
            if (!fix.acc.isFinite() || fix.acc > accuracyMax) return false
            if (last == null) return true
            if (fix.t <= last.t) return false
            val moved = FloatArray(1)
            Location.distanceBetween(last.lat, last.lng, fix.lat, fix.lng, moved)
            return moved[0] >= minMeters || fix.t - last.t >= minSeconds * 1000
        }
    }
}

// MARK: - A walk's route, recorded while the app is open

/**
 * Records a walk's route between "I've arrived" and "Finish" on the phone that pressed Arrive — and only
 * there, only while the app is open and in front (it has no background location). Locking the phone or
 * leaving the app leaves a gap, drawn as a break. The screen is kept on while it records (`keepsScreenOn`,
 * applied by the page).
 * Kept fixes wait on the phone until they are handed to the outbox in batches (every 30 s or 30 points,
 * and once more right before Finish).
 */
class SvcWalkTracker {
    enum class Status { off, recording, paused, unavailable }

    var status: Status by mutableStateOf(Status.off)
        private set
    /** Kept on this phone since the page was opened, sent or not. */
    var points: Int by mutableIntStateOf(0)
        private set
    /** iOS `isIdleTimerDisabled`: on while a walk is followed and the app is in front. */
    var keepsScreenOn: Boolean by mutableStateOf(false)
        private set

    var appointmentId: String? = null
        private set
    private var itemId: String? = null
    private var outbox: SvcOutbox? = null

    private val client by lazy { LocationServices.getFusedLocationProviderClient(AppContext.context) }
    private var buffer: MutableList<SvcWalkPoint> = mutableListOf()
    private var last: SvcWalkPoint? = null
    private var lastBatchAt = System.currentTimeMillis()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var asking: Job? = null
    private var updating = false
    private var observer: LifecycleEventObserver? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fixes = result.locations
                .filter { it.latitude.isFinite() && it.longitude.isFinite() && it.hasAccuracy() }
                .map { SvcWalkPoint(t = it.time.toDouble(), lat = it.latitude, lng = it.longitude, acc = it.accuracy.toDouble().roundToLong().toDouble()) }
            fixes.forEach { take(it) }
        }
    }

    companion object {
        private const val batchSize = 30
        private const val batchSeconds = 30L
        private const val bufferMax = 3000
        /** The server takes at most this many points in one call. */
        private const val uploadMax = 200

        private val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun granted(): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(AppContext.context, it) == PackageManager.PERMISSION_GRANTED
    }

    private val inFront: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** The open walk this phone records (null: none). Called as the day changes. */
    fun follow(appointmentId: String?, itemId: String?, outbox: SvcOutbox) {
        if (appointmentId == this.appointmentId) return
        stop()
        if (appointmentId == null || itemId == null) return
        this.appointmentId = appointmentId
        this.itemId = itemId
        this.outbox = outbox
        // A restart on this phone picks up what it had gathered.
        buffer = SvcDeviceStore.read(SvcWalkTracking.buffer(appointmentId))?.let {
            runCatching { API.json.decodeFromString(ListSerializer(SvcWalkPoint.serializer()), it.toString(Charsets.UTF_8)) }.getOrNull()
        }?.toMutableList() ?: mutableListOf()
        last = buffer.lastOrNull()
        points = buffer.size
        lastBatchAt = System.currentTimeMillis()
        status = if (inFront) Status.recording else Status.paused
        keepsScreenOn = true
        if (granted()) {
            startUpdates()
        } else {
            // The first walk asks for the location permission (iOS asks when it is not determined).
            val asked = appointmentId
            asking = scope.launch {
                PermissionRequests.request(permissions)
                if (this@SvcWalkTracker.appointmentId != asked) return@launch
                // Refused (or no screen to ask on): nothing will come.
                if (granted()) startUpdates() else status = Status.unavailable
            }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(5_000)
                if (batchDue()) handOver()
            }
        }
        val lifecycle = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (status != Status.unavailable) {
                        status = Status.paused
                        handOver()
                    }
                }
                // "Paused" stays until the next position arrives, so the walker sees there was a gap.
                Lifecycle.Event.ON_START -> if (this.appointmentId != null) keepsScreenOn = true
                else -> {}
            }
        }
        observer = lifecycle
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycle)
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (updating) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateDistanceMeters(5f)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            updating = true
        }.onFailure {
            if (it is SecurityException) status = Status.unavailable
        }
    }

    /** Hands what is left to the outbox and waits for it, never more than `timeout` seconds. */
    suspend fun flushNow(timeout: Double = 5.0) {
        val itemId = itemId ?: return
        handOver()
        outbox?.settled(itemId = itemId, kind = "route_points", timeout = timeout)
    }

    /** Hands what was gathered to the outbox now (a finish saved with no signal goes right after it). */
    fun handOverNow() = handOver()

    fun stop() {
        if (appointmentId == null) return
        // What was gathered and not sent goes out with the finish (or is dropped by the server once the walk is closed).
        handOver()
        if (updating) runCatching { client.removeLocationUpdates(callback) }
        updating = false
        ticker?.cancel()
        ticker = null
        asking?.cancel()
        asking = null
        observer?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        observer = null
        keepsScreenOn = false
        appointmentId = null
        itemId = null
        buffer = mutableListOf()
        last = null
        status = Status.off
        points = 0
    }

    private fun batchDue(): Boolean =
        buffer.isNotEmpty() && (buffer.size >= batchSize || System.currentTimeMillis() - lastBatchAt >= batchSeconds * 1000)

    private fun handOver() {
        val appointmentId = appointmentId ?: return
        val itemId = itemId ?: return
        val outbox = outbox ?: return
        if (buffer.isEmpty()) return
        var start = 0
        while (start < buffer.size) {
            val batch = buffer.subList(start, min(buffer.size, start + uploadMax)).toList()
            outbox.add(
                kind = "route_points", itemId = itemId, path = "/provider-appointments/$appointmentId/walk-route/points",
                body = mapOf("points" to JsonArray(batch.map { it.json })),
            )
            start += uploadMax
        }
        // From here the outbox keeps them (and lets them go once the server has them).
        buffer = mutableListOf()
        SvcDeviceStore.write(SvcWalkTracking.buffer(appointmentId), null)
        lastBatchAt = System.currentTimeMillis()
    }

    private fun take(fix: SvcWalkPoint) {
        val appointmentId = appointmentId ?: return
        if (inFront) status = Status.recording
        if (!SvcWalkPoint.keep(last, fix)) return
        last = fix
        buffer.add(fix)
        if (buffer.size > bufferMax) buffer = buffer.drop(buffer.size - bufferMax).toMutableList()
        SvcDeviceStore.write(
            SvcWalkTracking.buffer(appointmentId),
            runCatching { API.json.encodeToString(ListSerializer(SvcWalkPoint.serializer()), buffer).toByteArray() }.getOrNull(),
        )
        points += 1
        if (batchDue()) handOver()
    }
}

/** What the phone is recording of the walk right now — said plainly, gaps included (web `RouteRecording`). */
@Composable
fun SvcRouteRecording(tracker: SvcWalkTracker, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    when (tracker.status) {
        SvcWalkTracker.Status.off -> {}
        SvcWalkTracker.Status.unavailable -> {
            Row(
                modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Glyph("footprints", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 1.dp))
                Text(L("The route couldn’t be recorded on this phone."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
            }
        }
        SvcWalkTracker.Status.recording, SvcWalkTracker.Status.paused -> {
            val paused = tracker.status == SvcWalkTracker.Status.paused
            Column(
                modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.aqua, 12.0), shape)
                    .border(1.5.dp, Theme.mix(Theme.aqua, 60.0, Theme.borderSoft), shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(if (paused) "circle-pause" else "radio", 16.dp, tint = if (paused) Theme.inkStrong else Theme.blue)
                    Text(
                        if (paused) L("Paused — the app was in the background") else LF("Recording route · %d points", tracker.points),
                        style = Theme.text(14, FontWeight.ExtraBold),
                        color = Theme.inkStrong,
                    )
                }
                Text(L("Keep the app open and the screen on during the walk."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
            }
        }
    }
}
