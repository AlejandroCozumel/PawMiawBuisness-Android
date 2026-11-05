package com.pawmiaw.business.team

import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

//
//  TeamRoles.kt (iOS TeamRoles.swift)
//
//  Roles and permissions as the web's staff pages read them
//  (components/app/staff/staff-model.ts, lib/provider-permissions.ts): each
//  role's colour, icon and plain line; every permission grouped by the part
//  of the workspace it opens, in a manager's words; and the small rules the
//  pages share (overrides, "sees only their own…", open invitations).
//

object TeamRole {
    val all = listOf("owner", "admin", "vet", "support_staff", "receptionist")
    /** Roles someone can be invited as or moved to: the owner is only the workspace's creator. */
    val assignable = listOf("admin", "vet", "support_staff", "receptionist")

    fun label(role: String?): String = when (role) {
        "owner" -> L("Owner")
        "admin" -> L("Admin")
        "vet" -> L("Vet")
        "support_staff" -> L("Support staff")
        "receptionist" -> L("Receptionist")
        else -> L("Member")
    }

    fun summary(role: String?): String = when (role) {
        "owner" -> L("Everything, including the plan. There is one owner: whoever created the workspace.")
        "admin" -> L("Runs the place: the team, money, settings and every visit. Only the owner changes the plan.")
        "vet" -> L("Clinical work: their own visits and clients, writing and signing records, starting the bills.")
        "support_staff" -> L("Keeps the day moving: check-ins, visits for anyone, drafts of notes for a vet to sign, the desk.")
        "receptionist" -> L("The front desk: books for anyone, checks pets in, charges, runs the register and stays. No medical records.")
        else -> ""
    }

    /** The web's ROLE_META icon. */
    fun icon(role: String?): String = when (role) {
        "owner" -> "crown"
        "admin" -> "shield-check"
        "vet" -> "stethoscope"
        "support_staff" -> "heart-handshake"
        "receptionist" -> "headset"
        else -> "user-round"
    }

    /** lib/provider-colors.ts ROLE_HUE. */
    fun hue(role: String?): Hue = when (role) {
        "owner" -> Hue.butter
        "admin" -> Hue.sky
        "vet" -> Hue.mint
        "support_staff" -> Hue.peach
        "receptionist" -> Hue.lavender
        else -> Hue.grey
    }
}

data class TeamPermissionInfo(
    val key: String,
    val label: String,
    val description: String,
)

data class TeamPermissionGroup(
    val key: String,
    val title: String,
    val permissions: List<TeamPermissionInfo>,
)

object TeamPermissions {
    private fun p(key: String, label: String, description: String) = TeamPermissionInfo(key, label, description)

    /** PROVIDER_PERMISSION_GROUPS, in order, with their English words (Spanish in Team.json). */
    val groups: List<TeamPermissionGroup> = listOf(
        TeamPermissionGroup("appointments", "Schedule and visits", listOf(
            p("appointments.view", "See the Schedule", "See booked visits and how each one is going."),
            p("appointments.create", "Book visits", "Book new visits for clients and their pets."),
            p("appointments.edit", "Change visits", "Move a visit or change its details."),
            p("appointments.assign_self", "Book visits for themselves", "Put their own name on a visit they book or change."),
            p("appointments.assign_anyone", "Book visits for anyone", "Put any person of the team on a visit."),
            p("appointments.check_in", "Check pets in", "Record that a pet arrived, or that it didn’t show up."),
            p("appointments.cancel", "Cancel visits", "Cancel a booked visit when plans change."),
            p("appointments.complete", "Start and finish visits", "Take visits: start them and finish them. Whoever has this gets a column in the Schedule and can be booked."),
        )),
        TeamPermissionGroup("stays", "Stays", listOf(
            p("stays.view", "View stays", "See the occupancy board, reservations, and pets staying in."),
            p("stays.manage", "Manage stays", "Book, change, check in and check out stays; set up rooms and rates."),
            p("stays.care", "Care and report cards", "Tick off the day's care and send report cards to owners."),
        )),
        TeamPermissionGroup("hospital", "Hospital", listOf(
            p("hospital.view", "View the hospital", "See the ward, the patients in, and families on their way with an emergency."),
            p("hospital.care", "Treatment sheet and updates", "Tick doses on the sheet, record vitals and send updates to the family."),
            p("hospital.manage", "Manage hospitalizations", "Admit, write the treatment plan, sign rounds, discharge, transfer, record a death, set up the hospital and on-call."),
        )),
        TeamPermissionGroup("store", "Store", listOf(
            p("sales.sell", "Sell", "Use the store: sell products and services at the counter."),
            p("sales.refund", "Returns and cancels", "Take goods back, refund them, and cancel a sale made by mistake."),
            p("sales.override_price", "Change prices at the counter", "Sell something for a different price than the catalog’s."),
            p("register.manage", "Run the register", "Put cash in or take it out, add registers and see past shifts and what the drawer should hold. Anyone who sells or takes payments opens and closes a shift."),
        )),
        TeamPermissionGroup("pharmacy", "Pharmacy", listOf(
            p("pharmacy.dispense_controlled", "Hand over controlled medicines", "Sell or bill a controlled medicine against its prescription. Without it the Store and the visit’s bill ask for a vet."),
        )),
        TeamPermissionGroup("catalog", "Catalog", listOf(
            p("catalog.view", "See the Catalog", "Open the Catalog of services and products."),
            p("catalog.create", "Add to the Catalog", "Add new services or products."),
            p("catalog.edit", "Change the Catalog", "Change prices, details and whether an item is offered."),
        )),
        TeamPermissionGroup("inventory", "Inventory", listOf(
            p("inventory.view", "View inventory", "See stock levels, reorder points, and product inventory status."),
            p("inventory.edit", "Edit inventory setup", "Update inventory settings like quantity, reorder points, and shelf details."),
            p("inventory.adjust", "Adjust inventory", "Record stock movements, corrections, restocks, and waste."),
            p("inventory.receive", "Receive stock", "Record deliveries with their cost, lot, and expiry date."),
            p("inventory.count", "Count and write off", "Record stock counts and write off expired or damaged stock."),
        )),
        TeamPermissionGroup("clients", "Clients and pets", listOf(
            p("clients.view", "See clients", "Open the client list and each client’s page."),
            p("clients.create", "Add clients", "Add new clients."),
            p("clients.edit", "Change clients", "Change a client’s details and photo."),
            p("clients.assign_self", "Their own clients", "See and add the clients assigned to them. With only this, they see no one else’s clients."),
            p("clients.assign_anyone", "Every client", "See every client and assign them to anyone on the team."),
            p("pets.create", "Add pets", "Add a client’s first pet and more pets later."),
            p("pets.edit", "Change pets", "Change a pet’s profile, photo, chip number and notes."),
        )),
        TeamPermissionGroup("records", "Records", listOf(
            p("records.view", "See records", "See medical records and each pet’s history."),
            p("records.create", "Write records", "Add new medical records."),
            p("records.edit", "Change records", "Change records already written."),
            p("records.assign_self", "Their own records", "See and write the records assigned to them. With only this, they see no one else’s records."),
            p("records.assign_anyone", "Every record", "See every record and assign them to anyone on the team."),
            p("records.approve", "Sign records and prescriptions", "Sign records and prescriptions, and approve a draft someone else wrote."),
        )),
        TeamPermissionGroup("olfato", "Olfato AI", listOf(
            p("olfato.chat", "Use Olfato AI", "Ask Olfato AI about a pet or about the business. It answers only from what this person can already see."),
        )),
        TeamPermissionGroup("followups", "Follow-ups", listOf(
            p("followups.view", "See follow-ups", "See the lists of calls, rechecks and care follow-ups."),
            p("followups.create", "Add follow-ups", "Add a follow-up from a record or by hand."),
            p("followups.edit", "Change follow-ups", "Change the due date, notes, status and who has it."),
            p("followups.assign_self", "Their own follow-ups", "See and add the follow-ups assigned to them. With only this, they see no one else’s follow-ups."),
            p("followups.assign_anyone", "Every follow-up", "See every follow-up and assign them to anyone on the team."),
            p("followups.complete", "Finish follow-ups", "Mark a follow-up done."),
        )),
        TeamPermissionGroup("notifications", "Notices they get", listOf(
            p("notifications.appointments", "Notices about visits", "Bookings, arrivals, changes of time, cancellations and no-shows."),
            p("notifications.provider_followups", "Notices about follow-ups", "Reminders of calls, rechecks, lab results and medication reviews."),
            p("notifications.records_documentation", "Notices about records", "Reminders of records left to write or sign."),
            p("notifications.billing", "Notices about money", "Reminders of bills left to charge and of payments."),
            p("notifications.client_messages", "Notices about client messages", "When a client writes, and when a message waits for an answer."),
            p("notifications.client_facing_reminders", "Notices about reminders to clients", "Reminders that go out, or could go out, to clients."),
        )),
        TeamPermissionGroup("inbox", "Messages", listOf(
            p("inbox.view", "See messages", "See conversations with clients and families."),
            p("inbox.reply", "Answer messages", "Reply to clients and families."),
        )),
        TeamPermissionGroup("staff", "Team", listOf(
            p("staff.view", "See the team", "Open the team list, each person’s page and the invitations."),
            p("staff.invite", "Invite people", "Invite someone to the team, send an invitation again or cancel it."),
            p("staff.edit_roles", "Change roles and access", "Change a person’s role, permissions, profile, hours and days off."),
            p("staff.remove", "Suspend and remove people", "Pause someone’s access, give it back, or take them off the team."),
        )),
        TeamPermissionGroup("billing", "Bills and payments", listOf(
            p("billing.view", "See and charge bills", "The bill in front of them: a visit’s, stay’s or hospital bill, one client’s account and balance, one pet’s bills, selling at the counter."),
            p("finance.desk", "Front desk money", "Who owes, today’s cash and the day close, the bills list, and the last 7 days of sales."),
            p("finance.view", "Business finances", "Period totals, reports, margins, costs, payment history and every location."),
            p("billing.create_invoice", "Start bills", "Start a bill from a client, a visit or a record."),
            p("billing.edit_invoice", "Change bills", "Change what a bill in draft charges, and its notes."),
            p("billing.finalize_invoice", "Close bills", "Take a bill out of draft so it can be charged."),
            p("billing.record_payment", "Take payments", "Charge and record payments on open bills."),
            p("billing.apply_discount", "Give discounts", "Give a discount on a bill, with its reason."),
            p("billing.waive_visit", "Finish visits without charging", "Close a visit with “No charge” (a courtesy or a warranty recheck), with its reason. Without it, the visit goes to the desk to be charged."),
            p("billing.manage", "Money settings and the plan", "Set credit limits and the store’s, the receipt’s and money settings. Turned on by hand for one person, it also lets them change the workspace’s plan — only the owner can do that."),
        )),
        TeamPermissionGroup("workspace", "Business", listOf(
            p("workspace.edit", "Change the business", "Change how the business is set up: its details, locations, rooms, opening hours, closed dates and booking rules."),
        )),
    )

    /** Inviting, changing a role and removing someone still need an Admin or the Owner. */
    val teamAdmin: Set<String> = setOf("staff.invite", "staff.edit_roles", "staff.remove")

    fun label(key: String): String {
        for (group in groups) group.permissions.firstOrNull { it.key == key }?.let { return L(it.label) }
        return key
    }

    /** What a role gives before any fine-tuning; null while that isn't loaded. */
    fun roleDefaults(roles: List<TeamRolePermissions>?, role: String): List<String>? =
        roles?.firstOrNull { it.role == role }?.permissions

    fun effective(defaults: List<String>, allow: List<String>, deny: List<String>): List<String> {
        val set = (defaults.toSet() + allow).toMutableSet()
        for (permission in deny) set.remove(permission)
        return orderedKeys(set)
    }

    /** The two lists the server keeps for a person, from what they should be able to do: (allow, deny). */
    fun overrides(defaults: List<String>, wanted: List<String>): Pair<List<String>, List<String>> {
        val base = defaults.toSet()
        val want = wanted.toSet()
        return orderedKeys(want - base) to defaults.filter { !want.contains(it) }
    }

    fun orderedKeys(set: Set<String>): List<String> {
        val order = groups.flatMap { group -> group.permissions.map { it.key } }
        val known = order.filter { set.contains(it) }
        return known + (set - order.toSet()).sorted()
    }

    /** "Sees only their own clients": the "their own" permission of an area without its "every…" one. */
    fun limits(effective: List<String>): List<String> {
        val set = effective.toSet()
        // (view, own, anyone, text)
        val scopes = listOf(
            listOf("clients.view", "clients.assign_self", "clients.assign_anyone", "Sees only their own clients"),
            listOf("records.view", "records.assign_self", "records.assign_anyone", "Sees only their own records"),
            listOf("followups.view", "followups.assign_self", "followups.assign_anyone", "Sees only their own follow-ups"),
            listOf("appointments.create", "appointments.assign_self", "appointments.assign_anyone", "Books visits only for themselves"),
        )
        return scopes.filter { set.contains(it[0]) && set.contains(it[1]) && !set.contains(it[2]) }.map { L(it[3]) }
    }

    /** Each area with what is on and off in it: (group, on, off). */
    fun byGroup(effective: List<String>): List<Triple<TeamPermissionGroup, List<TeamPermissionInfo>, List<TeamPermissionInfo>>> {
        val set = effective.toSet()
        return groups.map { group ->
            Triple(group, group.permissions.filter { set.contains(it.key) }, group.permissions.filter { !set.contains(it.key) })
        }
    }

    /** Permissions whose name or description says what was typed; ignores case and accents. */
    fun search(needle: String): List<TeamPermissionGroup> {
        val wanted = teamPlain(needle.trim())
        if (wanted.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val permissions = if (teamPlain(L(group.title)).contains(wanted)) group.permissions else group.permissions.filter {
                teamPlain(L(it.label)).contains(wanted) || teamPlain(L(it.description)).contains(wanted)
            }
            if (permissions.isEmpty()) null else TeamPermissionGroup(key = group.key, title = group.title, permissions = permissions)
        }
    }

    /** "The role's access" or "1 added · 2 taken away". */
    fun accessLine(member: TeamMember): String {
        if (member.roleKey == "owner") return L("Full access")
        if (!member.hasCustomAccess) return L("The role’s access")
        val parts = mutableListOf<String>()
        if (member.allow.isNotEmpty()) parts.add(LP(member.allow.size, "1 added", "%d added"))
        if (member.deny.isNotEmpty()) parts.add(LP(member.deny.size, "1 taken away", "%d taken away"))
        return parts.joinToString(" · ")
    }
}

object TeamText {
    /** "Centro, Norte"; null when it is every location (owners and admins work everywhere). */
    fun locationNames(role: String?, ids: List<String>?, locations: List<ProviderLocation>): String? {
        if (role == "owner" || role == "admin") return null
        val names = (ids ?: emptyList()).mapNotNull { id -> locations.firstOrNull { it.id == id }?.name }
        if (names.isEmpty() || names.size == locations.size) return null
        return names.joinToString(", ")
    }

    /** The invitations still worth showing: one line per email, the newest; waiting first. */
    fun openInvitations(invitations: List<TeamInvitation>, memberEmails: Set<String>, now: Instant = Instant.now()): List<TeamOpenInvitation> {
        val byEmail = HashMap<String, TeamOpenInvitation>()
        val order = mutableListOf<String>()
        val sorted = invitations.sortedByDescending { DateText.parse(it.expiresAt) ?: Instant.MIN }
        for (invitation in sorted) {
            if (invitation.status != "pending") continue
            val email = invitation.email.trim().lowercase()
            if (memberEmails.contains(email) || byEmail[email] != null) continue
            val expired = DateText.parse(invitation.expiresAt)?.let { it <= now } ?: false
            byEmail[email] = TeamOpenInvitation(invitation = invitation, expired = expired)
            order.add(email)
        }
        val items = order.mapNotNull { byEmail[it] }
        return items.filter { !it.expired } + items.filter { it.expired }
    }

    /** What to say to the person invited, in the owner's own words. */
    fun invitationMessage(name: String?, email: String, workspace: String): String {
        val url = APIConfig.webOrigin.trim('/') + "/provider?invite=team"
        return LF("Hi %@! I added you to %@ on PawMiaw. Open %@, sign in or create your account with %@ and accept the invitation. It lasts 48 hours.", name.nonEmpty ?: "", workspace, url, email)
            .replace(" !", "!")
            .replace("¡Hola !", "¡Hola!")
    }

    /** "Active 3 hours ago"; "Not seen yet". */
    fun lastActive(iso: String?): String {
        val date = DateText.parse(iso) ?: return L("Not seen yet")
        return LF("Active %@", relative(date))
    }

    /** "in 2 days", "3 hours ago" — numbers, in the reader's language. */
    fun relative(date: Instant, now: Instant = Instant.now()): String {
        val f = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(appLocale), null, RelativeDateTimeFormatter.Style.LONG, android.icu.text.DisplayContext.CAPITALIZATION_NONE)
        val diff = (date.toEpochMilli() - now.toEpochMilli()) / 1000.0
        val (amount, unit) = when {
            abs(diff) < 3600 -> (diff / 60).roundToInt() to RelativeDateTimeFormatter.RelativeUnit.MINUTES
            abs(diff) < 86_400 -> (diff / 3600).roundToInt() to RelativeDateTimeFormatter.RelativeUnit.HOURS
            else -> (diff / 86_400).roundToInt() to RelativeDateTimeFormatter.RelativeUnit.DAYS
        }
        // Swift's `localizedString(from: DateComponents)`: 0 and more ahead, less behind.
        val direction = if (amount < 0) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
        return f.format(abs(amount).toDouble(), direction, unit)
    }

    /** "4 nov", with the year only when it isn't this one. */
    fun shortDate(iso: String?): String {
        val date = DateText.parse(iso) ?: return ""
        val zone = ZoneId.systemDefault()
        val sameYear = date.atZone(zone).year == Instant.now().atZone(zone).year
        return DateText.format(date, if (sameYear) "dMMM" else "dMMMyyyy", zone)
    }
}

/**
 * A role as a chip (web `RoleChip`): the role's hue at 18% with a 55% edge, its icon and name;
 * `large` = the member page's 28.
 */
@Composable
fun teamRoleChip(role: String?, large: Boolean = false, modifier: Modifier = Modifier) {
    val tone = TeamRole.hue(role).color
    Chip(
        text = TeamRole.label(role),
        tone = Chip.Tone.custom(tone),
        icon = TeamRole.icon(role),
        percent = 18.0,
        border = 55.0,
        size = if (large) Chip.Size.wide else Chip.Size.medium,
        modifier = modifier,
    )
}
