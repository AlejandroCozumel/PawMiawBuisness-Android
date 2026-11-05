package com.pawmiaw.business.records

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pawmiaw.business.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

//
//  RecordsListModel.kt (iOS RecordsListModel.swift)
//
//  What the Records page shows (web routes/provider.records.tsx): the view
//  (To complete, Records, Follow-ups), its filters, the header's numbers and
//  the three lists, each from the server. The page opens on To complete when
//  a finished visit still waits on the reader, else on the view used last on
//  this device; the records open on the drafts to sign when the reader signs
//  and has some. Both are decided once per visit to the page.
//

enum class RecordsListView(val rawValue: String) {
    toComplete("to-complete"),
    records("records"),
    followUps("follow-ups");

    companion object {
        fun fromRaw(raw: String?): RecordsListView? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Records: drafts waiting for a signature, signed ones, or all of them. */
enum class RecordsSignChoice { draft, signed, all }

/** Follow-ups: late, due this week, or later. */
enum class RecordsDue { overdue, week, later }

/** What the reader's role lets them do here (web `hasProviderPermission` calls on the page). */
data class RecordsAccess(
    val viewRecords: Boolean = false,
    val createRecords: Boolean = false,
    val editRecords: Boolean = false,
    val signRecords: Boolean = false,
    val assignSelf: Boolean = false,
    val assignAnyone: Boolean = false,
    val viewFollowUps: Boolean = false,
    val completeFollowUps: Boolean = false,
    val remindFollowUps: Boolean = false,
    val followUpsSelf: Boolean = false,
    val followUpsAnyone: Boolean = false,
    val viewClients: Boolean = false,
    val viewAppointments: Boolean = false,
    val isManager: Boolean = false,
) {
    constructor(w: Workspace) : this(
        viewRecords = w.can(Permission.recordsView),
        createRecords = w.can(Permission.recordsCreate),
        editRecords = w.can(Permission.recordsEdit),
        signRecords = w.can(Permission.recordsApprove),
        assignSelf = w.can(Permission.recordsAssignSelf),
        assignAnyone = w.can(Permission.recordsAssignAnyone),
        viewFollowUps = w.can(Permission.followupsView),
        completeFollowUps = w.can(Permission.followupsComplete),
        remindFollowUps = w.can(Permission.followupsEdit),
        followUpsSelf = w.can(Permission.followupsAssignSelf),
        followUpsAnyone = w.can(Permission.followupsAssignAnyone),
        viewClients = w.can(Permission.clientsView),
        viewAppointments = w.can(Permission.appointmentsView),
        isManager = w.isOwnerOrAdmin,
    )

    val records: Boolean get() = viewRecords && (assignSelf || assignAnyone)
    val followUps: Boolean get() = viewFollowUps && (followUpsSelf || followUpsAnyone)
    /** The page itself (and its "To complete" view): records or follow-ups. */
    val seesPage: Boolean get() = records || followUps
    val writeRecords: Boolean get() = createRecords && (assignSelf || assignAnyone)
    /** A signed record reaches the family: only someone who can sign changes it. */
    fun canEdit(record: RecordsRecord): Boolean = editRecords && (record.isDraft || signRecords)
}

/** How to reach a family: WhatsApp needs a phone, PawMiaw a linked household. Unknown lets the server decide. */
data class RecordsReach(
    val phone: String?,
    val onPawMiaw: Boolean,
)

/**
 * iOS `RecordsListModel` (an `ObservableObject`). `scope` runs what Swift starts with `Task { }`
 * (the screen's `rememberCoroutineScope()`).
 */
@Stable
class RecordsListModel(private val scope: CoroutineScope) {
    var access by mutableStateOf(RecordsAccess())
        private set
    var members by mutableStateOf<List<RecordsMember>>(emptyList())
        private set
    var view by mutableStateOf(RecordsListView.records)
        private set
    var opened by mutableStateOf(false)
        private set

    // Filters
    var signChoice by mutableStateOf(RecordsSignChoice.all)
        private set
    var search by mutableStateOf("")
        private set
    var type by mutableStateOf<RecordsKind?>(null)
        private set
    /** A staff member's id, or "unassigned". */
    var who by mutableStateOf<String?>(null)
        private set
    /** null = to do (open or reminded); "contacted", "done" or "all". */
    var followUpState by mutableStateOf<String?>(null)
        private set
    var due by mutableStateOf<RecordsDue?>(null)
        private set
    var from by mutableStateOf<String?>(null)
        private set
    var to by mutableStateOf<String?>(null)
        private set
    var pet by mutableStateOf<RecordsPetFilter?>(null)
        private set

    // The header's numbers
    var drafts by mutableStateOf<Int?>(null)
        private set
    var signedTotal by mutableStateOf<Int?>(null)
        private set
    var overdue by mutableStateOf<Int?>(null)
        private set
    var week by mutableStateOf<Int?>(null)
        private set

    // To complete
    var paperwork by mutableStateOf<List<RecordsFollowUp>>(emptyList())
        private set
    var paperworkTotal by mutableStateOf(0)
        private set
    var toSign by mutableStateOf<List<RecordsRecord>>(emptyList())
        private set
    var toSignTotal by mutableStateOf(0)
        private set
    var unfinished by mutableStateOf<List<RecordsRecord>>(emptyList())
        private set
    var unfinishedTotal by mutableStateOf(0)
        private set
    var toCompleteLoaded by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set

    var clientsTotal by mutableStateOf<Int?>(null)
        private set
    var reach by mutableStateOf<Map<String, RecordsReach>>(emptyMap())
        private set

    val recordsPager = Pager<RecordsRecord> { _, _ -> Page(items = emptyList(), total = 0) }
    val followUpsPager = Pager<RecordsFollowUp> { _, _ -> Page(items = emptyList(), total = 0) }

    private var clinicId: String? = null
    private var location: String? = null

    init {
        recordsPager.fetch = { offset, limit -> fetchRecords(offset, limit) }
        followUpsPager.fetch = { offset, limit -> fetchFollowUps(offset, limit) }
    }

    // MARK: Derived

    val currentMemberId: String? get() = members.firstOrNull { it.isCurrentMember == true }?.id
    val toCompleteCount: Int get() = paperworkTotal + toSignTotal + unfinishedTotal
    val hasMoreToComplete: Boolean get() = paperwork.size < paperworkTotal || toSign.size < toSignTotal || unfinished.size < unfinishedTotal
    val seesAnyone: Boolean get() = if (view == RecordsListView.records) access.assignAnyone else access.followUpsAnyone

    val filtered: Boolean
        get() = search.isNotEmpty() || type != null || who != null || pet != null || from != null || to != null ||
            (if (view == RecordsListView.records) signChoice != RecordsSignChoice.all else (followUpState != null || due != null))

    /** Only "To sign", nothing else narrowed: its empty list says everything is signed. */
    val onlyDrafts: Boolean get() = signChoice == RecordsSignChoice.draft && pet == null && from == null && to == null && type == null && who == null

    /** Whose lists: anyone's (a filter) for those who may see all, else only the reader's own. (member, unassigned) */
    private fun scope(seesAnyone: Boolean): Pair<String?, Boolean> {
        if (!seesAnyone) return currentMemberId to false
        if (who == "unassigned") return null to true
        return who to false
    }

    private class Days(val yesterday: String, val today: String, val weekEnd: String, val afterWeek: String)

    private val days: Days
        get() {
            val today = LocalDate.now(Workspace.shared.timeZone)
            fun key(offset: Long): String = today.plusDays(offset).toString()
            return Days(yesterday = key(-1), today = key(0), weekEnd = key(6), afterWeek = key(7))
        }

    // MARK: Loading

    suspend fun load(workspace: Workspace) {
        access = RecordsAccess(workspace)
        location = workspace.listLocationFilter
        if (!access.seesPage) return
        val clinic = workspace.current?.clinicId
        if (clinic != clinicId) {
            clinicId = clinic
            opened = false
            resetFilters()
            members = if (clinic != null) (recordsStart { RecordsAPI.members(clinic) }.await() ?: emptyList()) else emptyList()
        }
        loadCounts()
        if (!opened) {
            val stored = RecordsListView.fromRaw(AppContext.defaults.getString(VIEW_KEY, null))
            view = if (toCompleteCount > 0) RecordsListView.toComplete else (stored ?: RecordsListView.records)
            signChoice = if (access.signRecords && access.records && (drafts ?: 0) > 0) RecordsSignChoice.draft else RecordsSignChoice.all
            opened = true
        }
        normalizeView()
        reloadList()
    }

    private fun normalizeView() {
        if (view == RecordsListView.toComplete) return
        if (!access.followUps) view = RecordsListView.records else if (!access.records) view = RecordsListView.followUps
    }

    private fun resetFilters() {
        signChoice = RecordsSignChoice.all; search = ""; type = null; who = null; followUpState = null; due = null; from = null; to = null; pet = null
    }

    /** The header's numbers, To complete's three parts and the first clients (to know there are any). */
    suspend fun loadCounts() {
        val a = access
        val records = scope(a.assignAnyone)
        val followUps = scope(a.followUpsAnyone)
        val clients = scope(a.assignAnyone || a.followUpsAnyone)
        val mine = currentMemberId
        val d = days
        val page = TO_COMPLETE_PAGE

        val draftsPage = recordsStart(a.records) { RecordsAPI.records(limit = 1, offset = 0, status = "draft", readyToSign = true, member = records.first, unassigned = records.second) }
        val signedPage = recordsStart(a.records) { RecordsAPI.records(limit = 1, offset = 0, status = "active", member = records.first, unassigned = records.second) }
        val overduePage = recordsStart(a.followUps) { RecordsAPI.followUps(limit = 1, offset = 0, kind = "care", member = followUps.first, unassigned = followUps.second, dueTo = d.yesterday) }
        val weekPage = recordsStart(a.followUps) { RecordsAPI.followUps(limit = 1, offset = 0, kind = "care", member = followUps.first, unassigned = followUps.second, dueFrom = d.today, dueTo = d.weekEnd) }
        val paperworkPage = recordsStart(a.followUps) { RecordsAPI.followUps(limit = page, offset = 0, kind = "paperwork", member = if (a.followUpsAnyone) null else mine) }
        val toSignPage = recordsStart(a.records && a.signRecords) { RecordsAPI.records(limit = page, offset = 0, status = "draft", readyToSign = true, member = if (a.assignAnyone) null else mine) }
        val unfinishedPage = recordsStart(a.records) { RecordsAPI.records(limit = page, offset = 0, status = "draft", unfinished = true, member = if (a.assignAnyone) null else mine) }
        val clientsPage = recordsStart { RecordsAPI.clients(limit = 50, member = clients.first, unassigned = clients.second) }

        drafts = draftsPage.await()?.total
        signedTotal = signedPage.await()?.total
        overdue = overduePage.await()?.total
        week = weekPage.await()?.total
        val paperworkResult = paperworkPage.await()
        paperwork = paperworkResult?.items ?: emptyList()
        paperworkTotal = paperworkResult?.total ?: paperwork.size
        val toSignResult = toSignPage.await()
        toSign = toSignResult?.items ?: emptyList()
        toSignTotal = toSignResult?.total ?: toSign.size
        val unfinishedResult = unfinishedPage.await()
        unfinished = unfinishedResult?.items ?: emptyList()
        unfinishedTotal = unfinishedResult?.total ?: unfinished.size
        toCompleteLoaded = true
        val clientsResult = clientsPage.await()
        if (clientsResult != null) {
            clientsTotal = clientsResult.total ?: clientsResult.items.size
            val next = LinkedHashMap<String, RecordsReach>()
            for (client in clientsResult.items) {
                if (!next.containsKey(client.id)) next[client.id] = RecordsReach(phone = client.ownerPhone.nonEmpty, onPawMiaw = client.householdId.nonEmpty != null)
            }
            reach = next
        }
    }

    suspend fun reloadList() {
        when (view) {
            RecordsListView.records -> recordsPager.reload()
            RecordsListView.followUps -> followUpsPager.reload()
            RecordsListView.toComplete -> Unit
        }
    }

    /** The next 50 of each part of To complete that has more. */
    suspend fun loadMoreToComplete() {
        if (loadingMore) return
        loadingMore = true
        try {
            val a = access
            val mine = currentMemberId
            val page = TO_COMPLETE_PAGE
            val paperworkOffset = paperwork.size
            val toSignOffset = toSign.size
            val unfinishedOffset = unfinished.size
            val morePaperwork = a.followUps && paperworkOffset < paperworkTotal
            val moreToSign = a.records && a.signRecords && toSignOffset < toSignTotal
            val moreUnfinished = a.records && unfinishedOffset < unfinishedTotal

            val paperworkPage = recordsStart(morePaperwork) { RecordsAPI.followUps(limit = page, offset = paperworkOffset, kind = "paperwork", member = if (a.followUpsAnyone) null else mine) }
            val toSignPage = recordsStart(moreToSign) { RecordsAPI.records(limit = page, offset = toSignOffset, status = "draft", readyToSign = true, member = if (a.assignAnyone) null else mine) }
            val unfinishedPage = recordsStart(moreUnfinished) { RecordsAPI.records(limit = page, offset = unfinishedOffset, status = "draft", unfinished = true, member = if (a.assignAnyone) null else mine) }

            paperworkPage.await()?.let { more ->
                val known = paperwork.map { it.id }.toSet()
                paperwork = paperwork + more.items.filter { !known.contains(it.id) }
                more.total?.let { paperworkTotal = it }
            }
            toSignPage.await()?.let { more ->
                val known = toSign.map { it.id }.toSet()
                toSign = toSign + more.items.filter { !known.contains(it.id) }
                more.total?.let { toSignTotal = it }
            }
            unfinishedPage.await()?.let { more ->
                val known = unfinished.map { it.id }.toSet()
                unfinished = unfinished + more.items.filter { !known.contains(it.id) }
                more.total?.let { unfinishedTotal = it }
            }
        } finally {
            loadingMore = false
        }
    }

    private suspend fun fetchRecords(offset: Int, limit: Int): Page<RecordsRecord> {
        val status: String? = when (signChoice) {
            RecordsSignChoice.draft -> "draft"
            RecordsSignChoice.signed -> "active"
            RecordsSignChoice.all -> null
        }
        val scope = scope(access.assignAnyone)
        return RecordsAPI.records(
            limit = limit, offset = offset,
            status = status, readyToSign = status == "draft",
            search = search, recordType = type?.rawValue,
            member = scope.first, unassigned = scope.second,
            location = location, pet = pet?.id, from = from, to = to,
        ).page
    }

    private suspend fun fetchFollowUps(offset: Int, limit: Int): Page<RecordsFollowUp> {
        val scope = scope(access.followUpsAnyone)
        val d = days
        val dueFrom: String? = when (due) {
            RecordsDue.week -> d.today
            RecordsDue.later -> d.afterWeek
            RecordsDue.overdue -> null
            null -> from
        }
        val dueTo: String? = when (due) {
            RecordsDue.overdue -> d.yesterday
            RecordsDue.week -> d.weekEnd
            RecordsDue.later -> null
            null -> to
        }
        return RecordsAPI.followUps(
            limit = limit, offset = offset,
            status = followUpState ?: "to_do", kind = "care",
            search = search, recordType = type?.rawValue,
            member = scope.first, unassigned = scope.second,
            pet = pet?.id, dueFrom = dueFrom, dueTo = dueTo,
        ).page
    }

    // MARK: Changing what is shown

    private fun refreshList() {
        scope.launch { reloadList() }
    }

    fun switchView(next: RecordsListView) {
        // To complete is chosen by what waits, never remembered.
        if (next != RecordsListView.toComplete) AppContext.defaults.edit().putString(VIEW_KEY, next.rawValue).apply()
        // The days mean a record's date in one view and a due date in the other: they don't cross over.
        if (next != view) { from = null; to = null }
        view = next
        refreshList()
    }

    @JvmName("setSignFilter")
    fun setSign(choice: RecordsSignChoice) { signChoice = choice; refreshList() }
    @JvmName("setTypeFilter")
    fun setType(kind: RecordsKind?) { type = kind; refreshList() }
    @JvmName("setFollowUpStateFilter")
    fun setFollowUpState(state: String?) { followUpState = state; refreshList() }
    @JvmName("setPetFilter")
    fun setPet(filter: RecordsPetFilter?) { pet = filter; refreshList() }

    @JvmName("setDueFilter")
    fun setDue(value: RecordsDue?) {
        due = value
        from = null; to = null
        refreshList()
    }

    fun setRange(from: String?, to: String?) {
        this.from = from; this.to = to
        if (view == RecordsListView.followUps) due = null
        refreshList()
    }

    /** Whose lists changes the header's numbers too. */
    @JvmName("setWhoFilter")
    fun setWho(value: String?) {
        who = value
        scope.launch {
            loadCounts()
            reloadList()
        }
    }

    fun applySearch(text: String) {
        val trimmed = text.trim()
        if (trimmed == search) return
        search = trimmed
        refreshList()
    }

    /** The header's Signed card: the records, signed (again: all). */
    fun showSigned() {
        AppContext.defaults.edit().putString(VIEW_KEY, RecordsListView.records.rawValue).apply()
        val next = if (view == RecordsListView.records && signChoice == RecordsSignChoice.signed) RecordsSignChoice.all else RecordsSignChoice.signed
        if (view != RecordsListView.records) { from = null; to = null }
        view = RecordsListView.records
        signChoice = next
        refreshList()
    }

    /** The header's follow-up cards: late, or due this week (again: off). */
    fun showFollowUps(due: RecordsDue) {
        AppContext.defaults.edit().putString(VIEW_KEY, RecordsListView.followUps.rawValue).apply()
        val next: RecordsDue? = if (view == RecordsListView.followUps && this.due == due) null else due
        view = RecordsListView.followUps
        followUpState = null; from = null; to = null
        this.due = next
        refreshList()
    }

    fun clearFilters() {
        val whoChanged = who != null
        search = ""; type = null; who = null; followUpState = null; due = null; from = null; to = null; pet = null
        // "All" is said out loud, so the records don't fall back to the drafts.
        if (view == RecordsListView.records) signChoice = RecordsSignChoice.all
        if (whoChanged) {
            scope.launch { loadCounts(); reloadList() }
        } else {
            refreshList()
        }
    }

    companion object {
        private const val VIEW_KEY = "pawmiaw.provider.records.view"
        /** To complete shows this many of each part, then "Show more". */
        const val TO_COMPLETE_PAGE = 50
        /** Swift `RecordsListModel.toCompletePage`. */
        val toCompletePage: Int get() = TO_COMPLETE_PAGE
    }
}
