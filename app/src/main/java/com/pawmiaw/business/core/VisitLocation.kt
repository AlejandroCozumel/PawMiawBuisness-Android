package com.pawmiaw.business

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

//
//  Where the phone is at the two presses of a home visit — "I've arrived" and "Finish" /
//  "Leave" — on a visit the person started, and only when the business turned it on (Ajustes
//  de Domicilio, `locationStamps`; backend docs/home-visits-late-and-notices.md, the web's
//  lib/home-visits.ts; iOS VisitLocation.swift). Never in between: a walk's route is Services'
//  walk tracker, not this. Used by Domicilio (Services) and a home stay's visit, from Domicilio
//  or the stay (Stays). The app asks for the location permission at the first press, never before.
//

/** What the phone gave at a press: a place, or why there is none (the server's `PositionInput`). */
@Serializable
data class VisitPosition(
    val status: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val capturedAt: String? = null,
) {
    val json: JSONValue
        get() = buildJsonObject {
            put("status", status)
            latitude?.let { put("latitude", it) }
            longitude?.let { put("longitude", it) }
            accuracyM?.let { put("accuracyM", it) }
            capturedAt?.let { put("capturedAt", it) }
        }

    val body: Map<String, Any?>
        get() = buildMap {
            put("status", status)
            latitude?.let { put("latitude", it) }
            longitude?.let { put("longitude", it) }
            accuracyM?.let { put("accuracyM", it) }
            capturedAt?.let { put("capturedAt", it) }
        }
}

object VisitLocation {
    @Serializable
    private data class Settings(val locationStamps: Boolean? = null)

    /** Whether the business records the place at arrive and finish (read by everyone who works home visits or stays). */
    suspend fun stampsOn(): Boolean {
        val settings: Settings? = try {
            API.get("/provider-home-visits/settings")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return settings?.locationStamps == true
    }

    /** The place of a press when the business records it (null when it doesn't): asked at the press, 8 s at most. */
    fun stamp(on: Boolean): (suspend () -> VisitPosition)? =
        if (on) ({ VisitLocator.shared.position() }) else null
}

/**
 * Where the phone is right now, for the stamp of Arrive / Finish. It always answers — a position,
 * or why there is none — within 8 seconds of the permission being settled, and never throws.
 */
class VisitLocator private constructor() {
    companion object {
        val shared = VisitLocator()

        /** The longest Arrive or Finish waits for a position. */
        val timeout: Duration = 8.seconds

        /** The server takes an accuracy up to this; a coarser fix is sent as this ("too imprecise" either way). */
        private const val accuracyMax: Double = 100_000.0

        /** How long the permission question may stay open before the press goes on without a place. */
        private val askTimeout: Duration = 30.seconds

        fun position(latitude: Double, longitude: Double, accuracy: Double, at: Instant): VisitPosition {
            val measured = if (accuracy >= 0) accuracy else accuracyMax
            return VisitPosition(
                status = "ok",
                latitude = latitude,
                longitude = longitude,
                accuracyM = min(accuracyMax, max(0.0, measured.roundToLong().toDouble())),
                capturedAt = DateText.iso(at),
            )
        }

        private val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun granted(): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(AppContext.context, it) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun position(): VisitPosition {
        if (!granted()) {
            // The first press asks for the permission; the 8 seconds start once it's answered (30 s at most).
            // Android itself stops showing the question after it was refused twice (the answer is then "denied").
            val answer = withTimeoutOrNull(askTimeout) { PermissionRequests.request(permissions) }
                ?: return VisitPosition(status = "timeout")
            if (answer.values.none { it } && !granted()) return VisitPosition(status = "denied")
        }
        if (!granted()) return VisitPosition(status = "denied")
        return fix()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fix(): VisitPosition {
        val client = LocationServices.getFusedLocationProviderClient(AppContext.context)
        val cancel = CancellationTokenSource()
        return try {
            var answered = false
            val location: Location? = withTimeoutOrNull(timeout) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token).await().also { answered = true }
            }
            when {
                !answered -> VisitPosition(status = "timeout").also { cancel.cancel() }
                // No fix at all (location turned off on the phone).
                location == null -> VisitPosition(status = "unavailable")
                !location.latitude.isFinite() || !location.longitude.isFinite() -> VisitPosition(status = "unavailable")
                else -> position(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0,
                    at = Instant.ofEpochMilli(location.time),
                )
            }
        } catch (e: CancellationException) {
            cancel.cancel()
            throw e
        } catch (e: SecurityException) {
            VisitPosition(status = "denied")
        } catch (e: Exception) {
            VisitPosition(status = "unavailable")
        }
    }
}
