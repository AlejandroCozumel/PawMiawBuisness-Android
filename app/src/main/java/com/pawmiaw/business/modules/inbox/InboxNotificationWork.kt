package com.pawmiaw.business.inbox

import com.pawmiaw.business.*
import com.pawmiaw.business.records.*
import kotlinx.coroutines.CancellationException

//
//  InboxNotificationWork.kt (iOS InboxNotificationWork.swift)
//
//  The work a notification opens rather than its page (web
//  notifications/provider-notification-work.tsx `openInPlace`): a follow-up
//  to schedule opens the booking with its client, pet, day, person and note
//  (the visit is tied to the follow-up); a visit's missing record opens the
//  record sheet on its client, pet, visit and type; an invoice opens the bill;
//  a client's request about a visit opens the visit. Everything else goes to
//  the notification's page.
//

object InboxNotificationWork {
    sealed class Outcome {
        /** Open this screen. */
        data class push(val route: ProviderRoute) : Outcome()
        /** Handled here (a refusal was said): nothing opens. */
        data object stay : Outcome()
        /** Not work to open in place: the notification's page. */
        data object page : Outcome()
    }

    /** Whether this notification is opened in place (it may need a read first). */
    fun handles(n: InboxNotification): Boolean {
        if (n.sourceId.nonEmpty == null) return false
        return when ((n.sourceType ?: "") to (n.category ?: "")) {
            "provider_follow_up" to "provider_follow_ups", "provider_follow_up" to "records_documentation",
            "provider_invoice" to "billing", "provider_appointment_confirmation" to "appointments" -> true
            else -> false
        }
    }

    suspend fun open(n: InboxNotification, workspace: Workspace): Outcome {
        val id = n.sourceId.nonEmpty ?: return Outcome.page
        return when ((n.sourceType ?: "") to (n.category ?: "")) {
            "provider_follow_up" to "provider_follow_ups" -> {
                if (!workspace.can(Permission.appointmentsCreate)) {
                    Toast.error(L("You do not have permission to schedule this follow-up."))
                    return Outcome.stay
                }
                val followUp = followUp(id) ?: return Outcome.page
                Outcome.push(ProviderRoute.bookVisit(booking(followUp)))
            }
            "provider_follow_up" to "records_documentation" -> {
                if (!workspace.can(Permission.recordsCreate)) {
                    Toast.error(L("You do not have permission to create the missing record."))
                    return Outcome.stay
                }
                val followUp = followUp(id) ?: return Outcome.page
                Outcome.push(
                    ProviderRoute.newRecord(
                        RecordPrefill(
                            clientId = followUp.providerClientId,
                            petId = followUp.providerClientPetId,
                            appointmentId = followUp.providerAppointmentId.nonEmpty,
                            recordType = primaryRecordType(followUp.appointmentServiceLabel.nonEmpty ?: followUp.title ?: ""),
                        )
                    )
                )
            }
            "provider_invoice" to "billing" -> Outcome.push(ProviderRoute.invoice(id))
            "provider_appointment_confirmation" to "appointments" -> Outcome.push(ProviderRoute.appointment(id))
            else -> Outcome.page
        }
    }

    private suspend fun followUp(id: String): RecordsFollowUp? {
        val path = "/provider-follow-ups/${android.net.Uri.encode(id, "/:@!$&'()*+,;=-._~")}"
        return try {
            API.get<RecordsFollowUp>(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        }
    }

    /**
     * The follow-up's visit: its client and pet, on its due day (today when that has passed), with the
     * person it is assigned to and a note of what it is for.
     */
    private fun booking(followUp: RecordsFollowUp): BookingPrefill {
        val today = DateText.dayKey()
        val due = followUp.dueAt.nonEmpty?.let { RecordsText.dayKey(it) } ?: today
        val notes = listOfNotNull(
            LF("Clinical follow-up: %@", RecordsText.followUpTitle(followUp.title)),
            followUp.note?.trim().nonEmpty?.let { LF("Note: %@", it) },
            followUp.recordTitle?.trim().nonEmpty?.let { LF("Source record: %@", it) },
        ).joinToString("\n")
        return BookingPrefill(
            clientId = followUp.providerClientId,
            petId = followUp.providerClientPetId,
            day = if (due < today) today else due,
            memberId = followUp.assignedMember?.id.nonEmpty,
            followUpId = followUp.id,
            notes = notes,
        )
    }

    /** The record a visit asks for, from its service (lib/provider-appointment-records.ts). */
    fun primaryRecordType(serviceLabel: String): String {
        val label = serviceLabel.trim { it == ' ' || it == '\t' }.lowercase()
        if (label.contains("vaccination")) return "vaccination"
        if (label.contains("lab") || label.contains("test")) return "lab_result"
        return "vet_visit"
    }
}
