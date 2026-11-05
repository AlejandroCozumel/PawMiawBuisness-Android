package com.pawmiaw.business.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.pawmiaw.business.*
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

//
//  HomeText.kt (iOS HomeText.swift)
//
//  Home's small rules, as the web's today-board.tsx, visit-row.tsx,
//  home-hub.tsx and lib/home-hub.ts word them: a lane's state, the clock on
//  a row, a bill in a few words, the activities and their counts.
//

object HomeText {
    /** "20 min", "1 h", "1 h 56" (visits-copy `shortDuration`). */
    fun shortDuration(minutes: Int): String {
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0) "$hours h" else "$hours h $rest"
    }

    /** A clinic that only sees patients says "Con el veterinario"; as soon as it grooms, boards or walks, "En servicio". */
    fun clinicOnly(categories: List<String>): Boolean =
        categories.isEmpty() || categories.all { it in listOf("vet", "emergency", "pharmacy", "other") }

    fun inServiceTitle(categories: List<String>): String =
        if (clinicOnly(categories)) L("With the vet") else L("In service")

    /** The row's one-word state; the in-visit one follows the business. */
    fun laneLabel(lane: HomeLane, categories: List<String>): String = when (lane) {
        HomeLane.upcoming -> L("Next up")
        HomeLane.waiting -> L("Waiting@home")
        HomeLane.inService ->
            if (categories.isEmpty() || categories.contains("vet") || categories.contains("emergency")) L("In the visit") else L("In service")
        HomeLane.toPay -> L("To pay")
        HomeLane.done -> L("Done@home")
        HomeLane.missed -> L("Missed@home")
    }

    /** The stripe and state colour of a row (visit-row.tsx `stateTone`): late and missed in red. */
    @Composable
    @ReadOnlyComposable
    fun laneColor(visit: HomeVisit): Color {
        val late = visit.laneValue == HomeLane.upcoming && (visit.lateMinutes ?: 0) > 0
        if (late || visit.laneValue == HomeLane.missed) return Theme.destructive
        return when (visit.laneValue) {
            HomeLane.inService -> Theme.blue
            HomeLane.waiting -> Theme.mango
            HomeLane.toPay -> Theme.pink
            HomeLane.done -> Theme.borderSoft
            else -> Theme.aqua
        }
    }

    /** "Espera 12 min" / "Lleva 1 h" / "20 min tarde" under the time, in its colour. */
    @Composable
    @ReadOnlyComposable
    fun clock(visit: HomeVisit): Pair<String, Color>? {
        val waiting = visit.waitingMinutes
        if (visit.laneValue == HomeLane.waiting && waiting != null) {
            return LF("Waits %@", shortDuration(waiting)) to (if (waiting >= 20) Theme.destructive else Theme.mango)
        }
        val inService = visit.inServiceMinutes
        if (visit.laneValue == HomeLane.inService && inService != null) {
            return LF("In for %@", shortDuration(inService)) to Theme.blue
        }
        val late = visit.lateMinutes
        if (visit.laneValue == HomeLane.upcoming && late != null && late > 0) {
            return LF("%@ late", shortDuration(late)) to Theme.destructive
        }
        return null
    }

    /** The bill's stage on a Home card (lib/visit-bill.ts `billStage`, without the lines). */
    fun billStage(visit: HomeVisit): String {
        val invoice = visit.invoice
        if (invoice == null) {
            if (visit.billing == "no_charge") return "no_charge"
            return when (visit.status) {
                "scheduled", "checked_in" -> "not_started"
                "cancelled", "canceled", "no_show" -> "canceled"
                else -> "no_bill"
            }
        }
        val total = invoice.totalAmount ?: 0
        if (invoice.status == "canceled") return if (visit.billing == "no_charge") "no_charge" else "canceled"
        if (invoice.status == "paid" || (invoice.status != "draft" && (invoice.balanceDueAmount ?: 0) <= 0)) {
            return if (total == 0) "covered" else "paid"
        }
        if (invoice.status == "draft") return if (total > 0 && (invoice.unpricedLines ?: 0) == 0) "building" else "needs_price"
        if ((invoice.amountPaid ?: 0) > 0) return "part_paid"
        return "ready"
    }

    fun billStageLabel(stage: String): String = when (stage) {
        "not_started" -> L("Not started")
        "no_charge" -> L("No charge")
        "canceled" -> L("Canceled@bill")
        "no_bill" -> L("No bill yet")
        "needs_price" -> L("Needs a price")
        "building" -> L("Being built")
        "ready" -> L("Ready to charge")
        "part_paid" -> L("Part paid")
        "paid" -> L("Paid@bill")
        else -> L("Nothing to charge")
    }

    /** The row's bill in a few words: "Lista para cobrar · Dr. Ricardo · $500.00 MXN", "Falta precio". */
    fun billChip(visit: HomeVisit): Pair<String, Chip.Tone>? {
        if (visit.laneValue != HomeLane.toPay && visit.laneValue != HomeLane.done) return null
        if (visit.billing == "needs_price" || visit.billing == "needs_bill") return L("Needs a price") to Chip.Tone.warn
        val invoice = visit.invoice ?: return null
        val stage = billStage(visit)
        val amount = Money.format(if (stage == "building") invoice.totalAmount else invoice.balanceDueAmount, invoice.currency)
        return when (stage) {
            "building" -> "${billStageLabel(stage)} · $amount" to Chip.Tone.info
            "ready" -> listOf(billStageLabel(stage), invoice.sentBy?.name, amount).mapNotNull { it.nonEmpty }.joinToString(" · ") to Chip.Tone.warn
            "part_paid" -> LF("Part paid · %@ to pay", amount) to Chip.Tone.warn
            "covered" -> billStageLabel(stage) to Chip.Tone.good
            else -> null
        }
    }

    /** "Luna", "Luna y Coco", "Luna, Coco y Kira". */
    fun names(names: List<String>): String {
        if (names.size <= 1) return names.firstOrNull() ?: "—"
        return names.dropLast(1).joinToString(", ") + " " + L("and") + " " + (names.lastOrNull() ?: "")
    }

    /** "Hoy 10:30" for today's, "lun 20 oct" for an earlier day's. */
    fun dayLabel(day: String?): String {
        val date = DateText.parse(day) ?: return "—"
        return DateText.day(date)
    }

    fun confirmationWord(kind: String?): String = when (kind) {
        "reschedule_requested" -> L("asked to reschedule")
        "cancellation_requested" -> L("asked to cancel")
        "delivery_failed" -> L("confirmation not delivered")
        "declined" -> L("declined the visit")
        "expired" -> L("did not confirm")
        else -> kind ?: ""
    }

    fun leftOpenWord(status: String?): String = when (status) {
        "scheduled" -> L("never marked")
        "checked_in" -> L("still waiting")
        "in_progress" -> L("still in progress")
        else -> status ?: ""
    }

    /** "en 3 horas", "en 15 min": when a held time is released. */
    fun releaseIn(iso: String?): String? {
        val date = DateText.parse(iso) ?: return null
        val minutes = (Duration.between(Instant.now(), date).toMillis() / 60_000.0).roundToInt()
        if (minutes <= 1) return L("now@release")
        if (minutes < 60) return LF("in %d min", minutes)
        val hours = (minutes / 60.0).roundToInt()
        return if (hours == 1) L("in 1 hour") else LF("in %d hours", hours)
    }

    /** The groom's stage words (lib/groom-stages.ts). */
    fun groomStage(stage: String?, simple: Boolean = false): String = when (stage) {
        "scheduled" -> L("Scheduled@groom")
        "waiting" -> L("Waiting@groom")
        "bath" -> if (simple) L("In progress@groom") else L("Bath@groom")
        "styling" -> L("Cut & dry")
        "ready" -> L("Ready for pickup")
        "done" -> L("Done@groom")
        else -> stage ?: ""
    }

    fun groomState(stage: String?): ProviderState = ProviderState.groom(stage)
}

/** Home as the day's hub (lib/home-hub.ts): visits, grooms, stays, the hospital and home visits, by activity. */
enum class HomeActivity(val rawValue: String) {
    all("all"), appointments("appointments"), grooming("grooming"), stays("stays"), hospital("hospital"), home("home");

    val id: String get() = rawValue

    val label: String
        get() = when (this) {
            all -> L("All@activity")
            appointments -> L("Appointments@activity")
            grooming -> L("Grooming")
            stays -> L("Stays")
            hospital -> L("Hospital")
            home -> L("At home")
        }

    /** The web's `ACTIVITY_ICON` (Lucide). */
    val icon: String
        get() = when (this) {
            all -> "layout-grid"
            appointments -> "calendar-clock"
            grooming -> "scissors"
            stays -> "bed-double"
            hospital -> "hospital"
            home -> "house"
        }

    /** The activity's colour (lib/provider-colors.ts `ACTIVITY_HUE`; Todos is the page's blue). */
    val accent: Color
        @Composable @ReadOnlyComposable get() = when (this) {
            all -> Theme.blue
            appointments -> Hue.sky.color
            grooming -> Hue.lavender.color
            stays -> Hue.periwinkle.color
            hospital -> Hue.teal.color
            home -> Hue.mint.color
        }

    /** Where its "Ver todo" goes. */
    val section: ProviderSection
        get() = when (this) {
            all, appointments -> ProviderSection.schedule
            grooming -> ProviderSection.grooming
            stays -> ProviderSection.stays
            hospital -> ProviderSection.hospital
            home -> ProviderSection.homeVisits
        }

    fun shows(module: HomeActivity): Boolean = this == all || this == module

    companion object {
        val order: List<HomeActivity> = listOf(appointments, grooming, stays, hospital, home)

        fun from(raw: String?): HomeActivity? = entries.firstOrNull { it.rawValue == raw }

        /** The chips: "Todos" and each module this person has, only when there is more than one. */
        fun options(modules: List<HomeActivity>): List<HomeActivity> {
            val own = order.filter { modules.contains(it) }
            return if (own.size > 1) listOf(all) + own else own
        }
    }
}

/** Home's remembered activity, per business and member on this device. */
object HomeActivityStore {
    fun key(clinicId: String?, memberId: String?): String? {
        if (clinicId == null) return null
        return "pawmiaw_negocios_home_activity_${clinicId}_${memberId ?: "me"}"
    }

    fun read(key: String?): HomeActivity? {
        if (key == null) return null
        val raw = AppContext.defaults.getString(key, null) ?: return null
        return HomeActivity.from(raw)
    }

    fun save(key: String?, activity: HomeActivity) {
        if (key == null) return
        if (activity == HomeActivity.all) {
            AppContext.defaults.edit().remove(key).apply()
        } else {
            AppContext.defaults.edit().putString(key, activity.rawValue).apply()
        }
    }
}
