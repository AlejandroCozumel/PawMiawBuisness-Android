package com.pawmiaw.business

//
//  What a person may do in a business, as the server decides it (`effectivePermissions`
//  on the workspace; iOS Permissions.swift, the web's lib/provider-permissions.ts key for key).
//  The app only hides what a person can't do; the server refuses it anyway.
//

enum class Permission(val rawValue: String) {
    appointmentsView("appointments.view"),
    appointmentsCreate("appointments.create"),
    appointmentsEdit("appointments.edit"),
    appointmentsAssignSelf("appointments.assign_self"),
    appointmentsAssignAnyone("appointments.assign_anyone"),
    appointmentsCheckIn("appointments.check_in"),
    appointmentsCancel("appointments.cancel"),
    appointmentsComplete("appointments.complete"),
    staysView("stays.view"),
    staysManage("stays.manage"),
    staysCare("stays.care"),
    hospitalView("hospital.view"),
    hospitalCare("hospital.care"),
    hospitalManage("hospital.manage"),
    salesSell("sales.sell"),
    salesRefund("sales.refund"),
    salesOverridePrice("sales.override_price"),
    registerManage("register.manage"),
    pharmacyDispenseControlled("pharmacy.dispense_controlled"),
    inventoryReceive("inventory.receive"),
    inventoryCount("inventory.count"),
    catalogView("catalog.view"),
    catalogCreate("catalog.create"),
    catalogEdit("catalog.edit"),
    inventoryView("inventory.view"),
    inventoryEdit("inventory.edit"),
    inventoryAdjust("inventory.adjust"),
    clientsView("clients.view"),
    clientsCreate("clients.create"),
    clientsEdit("clients.edit"),
    clientsAssignSelf("clients.assign_self"),
    clientsAssignAnyone("clients.assign_anyone"),
    petsCreate("pets.create"),
    petsEdit("pets.edit"),
    recordsView("records.view"),
    recordsCreate("records.create"),
    recordsEdit("records.edit"),
    recordsAssignSelf("records.assign_self"),
    recordsAssignAnyone("records.assign_anyone"),
    recordsApprove("records.approve"),
    olfatoChat("olfato.chat"),
    followupsView("followups.view"),
    followupsCreate("followups.create"),
    followupsEdit("followups.edit"),
    followupsAssignSelf("followups.assign_self"),
    followupsAssignAnyone("followups.assign_anyone"),
    followupsComplete("followups.complete"),
    notificationsAppointments("notifications.appointments"),
    notificationsProviderFollowups("notifications.provider_followups"),
    notificationsRecordsDocumentation("notifications.records_documentation"),
    notificationsBilling("notifications.billing"),
    notificationsClientMessages("notifications.client_messages"),
    notificationsClientFacingReminders("notifications.client_facing_reminders"),
    inboxView("inbox.view"),
    inboxReply("inbox.reply"),
    staffView("staff.view"),
    staffInvite("staff.invite"),
    staffEditRoles("staff.edit_roles"),
    staffRemove("staff.remove"),
    workspaceEdit("workspace.edit"),
    billingView("billing.view"),
    financeDesk("finance.desk"),
    financeView("finance.view"),
    billingCreateInvoice("billing.create_invoice"),
    billingEditInvoice("billing.edit_invoice"),
    billingFinalizeInvoice("billing.finalize_invoice"),
    billingRecordPayment("billing.record_payment"),
    billingApplyDiscount("billing.apply_discount"),
    billingWaiveVisit("billing.waive_visit"),
    billingManage("billing.manage");
}

/**
 * Money reading in three levels (backend docs/money-access-levels.md):
 * 0 none · 1 `billing.view` the bill in front of you · 2 `finance.desk` the front desk
 * · 3 `finance.view` the business's finances. A higher level includes the lower ones.
 */
enum class MoneyLevel(val rawValue: Int) {
    none(0), charging(1), frontDesk(2), finances(3);

    companion object {
        fun of(permissions: Set<String>): MoneyLevel = when {
            Permission.financeView.rawValue in permissions -> finances
            Permission.financeDesk.rawValue in permissions -> frontDesk
            Permission.billingView.rawValue in permissions -> charging
            else -> none
        }

        /** "Recent" for the front desk: the last 7 days, today included. */
        const val frontDeskDays = 7
    }
}

enum class WorkspaceRole(val rawValue: String) {
    owner("owner"), admin("admin"), vet("vet"), supportStaff("support_staff"), receptionist("receptionist");

    val id: String get() = rawValue

    val label: String
        get() = when (this) {
            owner -> L("Owner")
            admin -> L("Admin")
            vet -> L("Vet")
            supportStaff -> L("Support staff")
            receptionist -> L("Receptionist")
        }

    val summary: String
        get() = when (this) {
            owner -> L("Full control over staff, billing, workspace settings, and clinic workflows.")
            admin -> L("Manages staff, money and clinic workflows; only the owner changes the workspace plan.")
            vet -> L("Handles clinical work, appointments, clients, pets and records, and charges their own visits.")
            supportStaff -> L("Keeps the day moving: check-ins, visits, drafts of notes for the vet to sign, and the desk.")
            receptionist -> L("Focuses on booking, check-in, client contact, and front-desk coordination.")
        }

    companion object {
        fun from(raw: String?): WorkspaceRole? = entries.firstOrNull { it.rawValue == raw }
        fun label(raw: String?): String = from(raw)?.label ?: (raw ?: "")
    }
}

/** The kinds of business a workspace runs (`enabledCategories`). */
enum class BusinessCategory(val rawValue: String) {
    vet("vet"), emergency("emergency"), groomer("groomer"), boarding("boarding"), daycare("daycare"),
    sitter("sitter"), walker("walker"), trainer("trainer"), other("other");

    val label: String
        get() = when (this) {
            vet -> L("Veterinary clinic")
            emergency -> L("Emergency")
            groomer -> L("Grooming")
            boarding -> L("Boarding")
            daycare -> L("Daycare")
            sitter -> L("Pet sitting")
            walker -> L("Dog walking")
            trainer -> L("Training")
            other -> L("Other")
        }

    companion object {
        fun from(raw: String?): BusinessCategory? = entries.firstOrNull { it.rawValue == raw }
    }
}
