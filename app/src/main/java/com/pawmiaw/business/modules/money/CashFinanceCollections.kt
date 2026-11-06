package com.pawmiaw.business.money

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import com.pawmiaw.business.clients.ClientsDisconnectedMark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

//
//  CashFinanceCollections.kt (iOS CashFinanceCollections.swift)
//
//  "Te deben" (web: finance/finance-collections.tsx, finance-client-sheet.tsx):
//  who owes, how late, the promises to pay and the last contact; one client's
//  account — what is open, the movements, collect a payment (oldest debt
//  first), take a deposit, a note or a promise, a reminder, the credit limit.
//  The list is the front desk's (level 2); one account is level 1.
//
//  The account is a modal on the web; here it is a page of its own with the
//  modal's head as its module header (pink, HandCoins) and its sections as cards.
//

/** SwiftUI `.font(.system(size:, weight:, design: .monospaced))`: a folio. */
private fun collectionsMono(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = weight)

/** Swift `.onChange(of:)`: runs on a change of `value`, not on the first composition. */
@Composable
private fun <T> CollectionsOnChange(value: T, action: suspend (T) -> Unit) {
    val latest by rememberUpdatedState(action)
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(value) {
        if (first[0]) first[0] = false else latest(value)
    }
}

// MARK: - Who owes (level 2)

@Composable
fun CashFinanceCollections(
    /** Who is listed: all, an aging bucket, or a follow-up filter (the overview's figures set it). */
    filter: MutableState<String>,
    modifier: Modifier = Modifier,
    /** A client's account; `true` opens it on "Collect" (a row's button). */
    onClient: (String, Boolean) -> Unit,
) {
    val workspace = Workspace.shared
    val wide = LocalSizeClass.current == SizeClass.regular
    var data by remember { mutableStateOf<CashCollections?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val query = remember { mutableStateOf("") }
    var shown by remember { mutableIntStateOf(20) }
    val canRecord = workspace.can(Permission.billingRecordPayment)

    suspend fun load() {
        try {
            data = API.get("/provider-finance/collections", mapOf("filter" to filter.value, "q" to query.value.nonEmpty, "locationId" to workspace.listLocationFilter))
            error = null
            shown = 20
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (data == null) error = errorText(failure)
        }
    }

    fun toggle(next: String) {
        filter.value = if (filter.value == next) "all" else next
    }

    Debounced(query.value) { load() }
    CollectionsOnChange(filter.value) { load() }
    ReloadsOnScope { load() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        data?.summary?.let { summary ->
            CashFinGrid(
                items = CashText.agingBuckets.map { bucket -> @Composable { CollectionsBucketTile(bucket, summary, filter.value == bucket) { toggle(bucket) } } },
                columns = if (wide) 4 else 2,
                spacing = 8.dp,
            )
        }
        SearchField(prompt = L("Client, pet or phone"), text = query)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val summary = data?.summary
            ToneChip(L("Overdue"), icon = "triangle-alert", tone = ChipTone.state(ProviderState.problem), count = summary?.overdueClients, isOn = filter.value == "overdue") { toggle("overdue") }
            ToneChip(
                L("Promises"), icon = "handshake", tone = ChipTone.state(ProviderState.upcoming),
                count = summary?.let { it.promisesDue + it.promisesBroken + it.promisesPending }, isOn = filter.value == "promises",
            ) { toggle("promises") }
            ToneChip(L("Not contacted"), icon = "bell-ring", tone = ChipTone.state(ProviderState.waiting), count = summary?.uncontacted, isOn = filter.value == "uncontacted") { toggle("uncontacted") }
            val over = summary?.overLimit
            if (over != null && over > 0) {
                ToneChip(L("Over their limit"), icon = "shield-alert", tone = ChipTone.state(ProviderState.problem), count = over, isOn = filter.value == "over_limit") { toggle("over_limit") }
            }
        }
        Row {
            CashCSVButton(disabled = data?.items?.isEmpty() ?: true) {
                val loaded = data ?: throw IllegalStateException("Nothing to export")
                CashCSV.fileName(L("receivables"), from = loaded.today, to = loaded.today) to CashFinanceCSV.collections(loaded)
            }
        }
        val loaded = data
        val failed = error
        if (loaded != null) {
            if (loaded.items.isEmpty()) {
                val everyone = filter.value == "all" && query.value.isEmpty()
                ListCard {
                    EmptyState(
                        systemImage = "circle-check",
                        title = if (everyone) L("Nobody owes you anything.") else L("No one here."),
                        message = if (everyone) L("Sales and bills left unpaid show up here.") else L("Try another filter or search."),
                        actionTitle = if (everyone) null else L("Show everyone who owes"),
                        action = { filter.value = "all"; query.value = "" },
                        accent = Theme.lime,
                    )
                }
            } else {
                ListCard {
                    loaded.items.take(shown).forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        CashCollectionRow(
                            item = item,
                            today = loaded.today,
                            canRecord = canRecord,
                            modifier = Modifier.onScrolledIntoView { if (index >= shown - 3 && shown < loaded.items.size) shown += 20 },
                        ) { collect -> onClient(item.providerClientId, collect) }
                    }
                }
                val owed = loaded.items.sumOf { it.owed }
                CashFinListCount(
                    text = LP(loaded.items.size, "%d client owes", "%d clients owe"),
                    trailing = if (filter.value == "all" && query.value.isEmpty()) {
                        LF("%@ owed in total", cashMoney(owed, loaded.summary.currency))
                    } else {
                        LF("%@ owed by these clients · %@ by everyone", cashMoney(owed, loaded.summary.currency), cashMoney(loaded.summary.owed, loaded.summary.currency))
                    },
                )
            }
            val credits = loaded.credits
            if (!credits.isNullOrEmpty()) CollectionsCredits(credits, loaded.summary.currency, onClient)
        } else if (failed != null) {
            CashFinError(title = L("Who owes could not be loaded."), message = failed) { load() }
        } else {
            LoadingState()
        }
    }
}

/** One aging bucket (web: the tiles over the list): its dot, name, amount and clients; a filter. */
@Composable
private fun CollectionsBucketTile(bucket: String, summary: CashCollections.Summary, active: Boolean, onTap: () -> Unit) {
    val amount = summary.aging?.get(bucket)
    val clients = summary.agingClients?.get(bucket) ?: 0
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxSize()
            .then(if (active) Modifier.shadow(1.5.dp, box, clip = false, ambientColor = Theme.navy.copy(alpha = 0.1f), spotColor = Theme.navy.copy(alpha = 0.1f)) else Modifier)
            .clip(box)
            .semantics { selected = active }
            .plainButton(enabled = amount != null, onClick = onTap)
            .background(Theme.surface, box)
            .border(2.dp, if (active) Theme.inkStrong else Theme.borderSoft, box)
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(CashFinAging.tone(bucket), CircleShape))
            Text(CashText.aging(bucket).uppercase(), style = brandText(10.6, FontWeight.ExtraBold).tracking(0.85), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FittedText(
            amount?.let { cashMoney(it, summary.currency) } ?: "—",
            Modifier.padding(top = 6.dp),
            style = Theme.heading(18).monospacedDigit(),
            color = if (bucket == "d61_plus" && (amount ?: 0) > 0) Theme.destructive else Theme.inkStrong,
            minimumScaleFactor = 0.6f,
        )
        Text(LP(clients, "%d client", "%d clients"), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The other side of the ledger: clients who paid ahead. */
@Composable
private fun CollectionsCredits(credits: List<CashCollections.Credit>, currency: String, onClient: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 4.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("piggy-bank", 16.dp, tint = Theme.inkSoft)
                Text(L("Credit on account"), style = Theme.heading(18), color = Theme.inkStrong)
            }
            Text(L("Deposits paid ahead and not spent yet. You owe these clients the work, or the money back."), style = Theme.text(14), color = Theme.inkSoft)
        }
        ListCard {
            credits.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().plainButton { onClient(row.providerClientId, false) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name = row.name, imageURL = row.photoUrl, size = 36.dp, tint = ProviderState.done.color)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(row.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.owed > 0) {
                            Text(LF("Also owes %@ — the credit can pay it", cashMoney(row.owed, currency)), style = Theme.text(12), color = Theme.inkSoft)
                        }
                    }
                    Text(cashMoney(row.credit, currency), style = Theme.heading(18).monospacedDigit(), color = Theme.inkStrong)
                }
            }
        }
        CashFinListCount(
            text = LP(credits.size, "%d client holds credit", "%d clients hold credit"),
            trailing = LF("%@ held in total", cashMoney(credits.sumOf { it.credit }, currency)),
        )
    }
}

/**
 * One client who owes (web `CollectionRow`): their face, the pets and open bills, the chips (how
 * late, the promise, over the limit, credit to apply, the last contact), what they owe and "Collect".
 */
@Composable
fun CashCollectionRow(
    item: CashCollectionItem,
    today: String,
    canRecord: Boolean = false,
    modifier: Modifier = Modifier,
    /** `true`: open straight on "Collect". */
    onOpen: (Boolean) -> Unit,
) {
    val money = { cents: Int -> cashMoney(cents, item.currency) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().plainButton { onOpen(false) }, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(name = item.name, imageURL = item.photoUrl, size = 44.dp, tint = Theme.pink)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.walkIn == true) L("Walk-in sales") else item.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = Theme.text(16, FontWeight.Bold),
                        color = Theme.inkStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.onPawMiaw == true) {
                        val onPawMiaw = L("On PawMiaw")
                        Glyph("smartphone", 14.dp, tint = Theme.inkSoft, modifier = Modifier.semantics { contentDescription = onPawMiaw })
                    }
                }
                val pets = item.petNames ?: emptyList()
                Text(
                    listOfNotNull(if (pets.isEmpty()) null else pets.joinToString(", "), LP(item.documents, "%d open bill", "%d open bills")).joinToString(" · "),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                )
                FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 4.dp)) { CollectionChips(item, today, money) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(money(item.owed), style = Theme.heading(20).monospacedDigit(), color = Theme.inkStrong)
                if (item.overdue > 0 && item.overdue < item.owed) {
                    Text(LF("%@ overdue", money(item.overdue)), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
                }
            }
            if (canRecord) {
                val label = LF("Collect from %@", item.name)
                PMButton(L("Collect"), systemImage = "hand-coins", modifier = Modifier.semantics { contentDescription = label }) { onOpen(true) }
            }
        }
    }
}

@Composable
private fun CollectionChips(item: CashCollectionItem, today: String, money: (Int) -> String) {
    val due = item.oldestDueDay
    if (item.daysOverdue > 0) {
        Chip(text = CashText.daysLate(item.daysOverdue), tone = Chip.Tone.danger)
    } else if (due != null) {
        Chip(text = if (due == today) L("Due today") else LF("Due %@", CashDay.short(due)), tone = Chip.Tone.info)
    }
    item.promise?.let { promise ->
        CashPromiseChip(status = promise.status, day = promise.promisedOn, amount = promise.promisedAmount?.let(money))
    }
    val limit = item.creditLimitAmount
    if (item.overLimit == true && limit != null) {
        Chip(text = LF("Over %@ limit", money(limit)), tone = Chip.Tone.danger)
    }
    val credit = item.credit
    if (credit != null && credit > 0) {
        Chip(text = LF("%@ of credit to apply", money(credit)), tone = Chip.Tone.good, icon = "piggy-bank")
    }
    // They still owe it after an unlink (docs/family-money.md §1): the family keeps seeing these bills.
    ClientsDisconnectedMark(disconnectedAt = item.disconnectedAt, disconnectedBy = item.disconnectedBy)
    Chip(text = collectionLastContact(item), tone = Chip.Tone.muted)
}

private fun collectionLastContact(item: CashCollectionItem): String {
    val at = DateText.parse(item.lastContactAt) ?: return L("Never contacted")
    val zone = Workspace.shared.timeZone
    val days = ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), LocalDate.now(zone)).toInt()
    val whenText = if (days == 0) L("today") else if (days == 1) L("yesterday") else LF("%d days ago", days)
    if (item.lastContactKind == "reminder") {
        val channel = if (item.lastContactChannel == "whatsapp") "WhatsApp" else if (item.lastContactChannel == "email") L("email") else L("the app")
        return LF("Reminded by %@ %@", channel, whenText)
    }
    return LF("Note %@", whenText)
}

/** A promise to pay on a row or in the history (web `PromiseChip`). */
@Composable
fun CashPromiseChip(status: String, day: String, amount: String?, modifier: Modifier = Modifier) {
    val what = amount?.let { LF("%@ by %@", it, CashDay.short(day)) } ?: LF("by %@", CashDay.short(day))
    when (status) {
        "kept" -> Chip(text = LF("Kept: %@", what), tone = Chip.Tone.good, icon = "calendar-check", modifier = modifier)
        "broken" -> Chip(text = LF("Broken: %@", what), tone = Chip.Tone.danger, icon = "handshake", modifier = modifier)
        "due" -> Chip(text = amount?.let { LF("Promised %@ today", it) } ?: L("Promised to pay today"), tone = Chip.Tone.warn, icon = "handshake", modifier = modifier)
        else -> Chip(text = LF("Promised %@", what), tone = Chip.Tone.info, icon = "handshake", modifier = modifier)
    }
}

// MARK: - One client's account (level 1)

@Serializable
private data class CashRemindSent(val channel: String, val message: String? = null, val link: String? = null, val sentTo: Int? = null)

@Composable
fun CashAccountScreen(
    clientId: String,
    /** Opened from a row's "Collect": straight to collecting. */
    collect: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    val loader = remember(clientId) { Loader<CashAccount> { API.get("/provider-finance/clients/$clientId/account") } }
    var collecting by remember { mutableStateOf(false) }
    var depositing by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    var limiting by remember { mutableStateOf(false) }
    var reminding by remember { mutableStateOf<String?>(null) }
    var allMovements by remember { mutableStateOf(false) }
    var focusTaken by remember { mutableStateOf(false) }
    val canRecord = workspace.can(Permission.billingRecordPayment)

    fun send(account: CashAccount, channel: String) {
        reminding = channel
        scope.launch {
            try {
                val sent: CashRemindSent = API.post(
                    "/provider-finance/clients/${account.client.id}/remind",
                    mapOf("channel" to channel, "locale" to LocalizationManager.currentLanguage.rawValue),
                )
                val link = sent.link.nonEmpty
                if (channel == "whatsapp" && link != null) {
                    openURL(link)
                    Toast.show(L("WhatsApp is open with the message. Send it from there."))
                } else if (channel == "app") {
                    Toast.show(LF("Reminder sent to %@’s PawMiaw.", account.client.firstName ?: account.client.name))
                } else {
                    Toast.show(LF("Reminder emailed to %@.", account.client.email ?: ""))
                }
                loader.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Nobody left on PawMiaw for these bills (after an unlink): said in words, with what to do instead.
                if ((failure as? APIError)?.code == "not_on_pawmiaw") Toast.error(LF("%@ is not on PawMiaw — send it by email or WhatsApp", account.client.name))
                else Toast.error(failure)
            } finally {
                reminding = null
            }
        }
    }

    val title = loader.value?.let { if (it.client.walkIn == true) L("Walk-in sales") else it.client.name } ?: L("Account")
    providerPage(title, modifier = modifier) {
        PageScroll(refresh = { loader.load() }) {
            if (workspace.moneyLevel < MoneyLevel.charging) {
                NoAccessState()
            } else {
                LoadedContent(loader) { account ->
                    val money = { cents: Int -> cashMoney(cents, account.currency) }
                    AccountHeader(account, money, canRecord, onCollect = { collecting = true }, onOpenClient = { nav.push(ProviderRoute.client(clientId)) })
                    if (account.owed > 0 && account.credit > 0) {
                        NoticeCard(tone = NoticeCard.Tone.success, title = LF("They hold %@ of credit: it can pay what they owe — choose “Credit on account” when collecting.", money(account.credit)))
                    }
                    if (account.open.isNotEmpty()) AccountOpenBills(account, money)
                    AccountMovements(account, money, allMovements) { allMovements = !allMovements }
                    if (account.owed > 0 && workspace.moneyLevel >= MoneyLevel.frontDesk) {
                        AccountRemind(account, reminding) { channel -> send(account, channel) }
                    }
                    if (workspace.moneyLevel >= MoneyLevel.frontDesk) {
                        // MARK: Follow up
                        SectionCard(L("Follow up"), subtitle = L("What you talked about, and when they said they would pay.")) {
                            Row(Modifier.padding(16.dp)) {
                                PMButton(L("Write a note or a promise"), systemImage = "notebook-pen", variant = ButtonVariant.outline, size = ButtonSize.compact) { noting = true }
                            }
                        }
                    }
                    AccountTimeline(account, money)
                    if (account.client.walkIn != true && (account.credit > 0 || canRecord)) {
                        // MARK: Credit and its limit
                        SectionCard(L("Credit on account"), subtitle = L("Deposits they can spend at checkout.")) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Glyph("piggy-bank", 20.dp, tint = Theme.inkSoft)
                                Text(money(account.credit), modifier = Modifier.weight(1f), style = Theme.heading(20).monospacedDigit(), color = Theme.inkStrong)
                                if (canRecord) {
                                    PMButton(L("Take a deposit"), systemImage = "hand-coins", variant = ButtonVariant.outline, size = ButtonSize.compact) { depositing = true }
                                }
                            }
                        }
                    }
                    if (account.client.walkIn != true) AccountCreditLimit(account, money) { limiting = true }
                }
            }
        }
        ReloadsOnScope { loader.load() }
        LaunchedEffect(loader.value?.client?.id) {
            // A row's "Collect": once the account is here, collecting opens.
            val account = loader.value
            if (!collect || focusTaken || account == null) return@LaunchedEffect
            focusTaken = true
            if (account.owed > 0 && canRecord) collecting = true
        }
        val account = loader.value
        if (account != null) {
            if (collecting) {
                AppSheet(onDismissRequest = { collecting = false }) {
                    CashCollectSheet(account = account, onDone = { scope.launch { loader.load() } })
                }
            }
            if (depositing) {
                AppSheet(onDismissRequest = { depositing = false }) {
                    DepositSheet(clientId = account.client.id, clientName = account.client.name, onTaken = { loader.load() })
                }
            }
            if (noting) {
                AppSheet(onDismissRequest = { noting = false }) {
                    CashNoteSheet(account = account, onDone = { scope.launch { loader.load() } })
                }
            }
            if (limiting) {
                AppSheet(onDismissRequest = { limiting = false }) {
                    CashCreditLimitSheet(account = account, onDone = { scope.launch { loader.load() } })
                }
            }
        }
    }
}

/** The modal's head as the page's header: what they owe, the three figures, the account's actions. */
@Composable
private fun AccountHeader(account: CashAccount, money: (Int) -> String, canRecord: Boolean, onCollect: () -> Unit, onOpenClient: () -> Unit) {
    val workspace = Workspace.shared
    val subtitle = if (account.owed > 0) {
        if (account.overdue > 0) LF("Owes %@ · %@ overdue", money(account.owed), money(account.overdue)) else LF("Owes %@", money(account.owed))
    } else if (account.credit > 0) {
        LF("Owes nothing · %@ of credit", money(account.credit))
    } else {
        L("Nothing owed")
    }
    val pets = account.open.mapNotNull { it.petName.nonEmpty }.toSet().sorted()
    ModuleHeader(
        icon = "hand-coins",
        title = if (account.client.walkIn == true) L("Walk-in sales") else account.client.name,
        subtitle = subtitle,
        accent = Theme.pink,
        stats = listOf(
            ModuleStat("owes", label = L("Owes"), value = money(account.owed), icon = "hand-coins", accent = Theme.pink),
            ModuleStat("overdue", label = L("Overdue"), value = money(account.overdue), icon = "alarm-clock", accent = ProviderState.problem.color, attention = account.overdue > 0),
            ModuleStat("credit", label = L("Credit on account"), value = money(account.credit), icon = "piggy-bank", accent = ProviderState.done.color),
        ),
        actionsOnRow = true,
        storageKey = "finance-account",
        actions = {
            if (canRecord && account.owed > 0) {
                PMButton(L("Collect"), systemImage = "hand-coins") { onCollect() }
            }
            DocumentButton(
                title = L("Download PDF"),
                systemImage = "file-down",
                path = "/provider-finance/clients/${account.client.id}/statement/pdf",
                fileName = "${account.client.name} ${account.today}",
            )
            if (workspace.can(Permission.clientsView) && account.client.walkIn != true) {
                PMButton(L("Open client"), systemImage = "user-round", variant = ButtonVariant.outline) { onOpenClient() }
            }
        },
        footer = {
            val disconnected = account.client.disconnectedAt != null
            if (account.client.walkIn != true && (pets.isNotEmpty() || account.client.onPawMiaw == true || disconnected)) {
                Column(Modifier.fillMaxWidth()) {
                    RowDivider()
                    FlowLayout(spacing = 6.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        for (pet in pets) Chip(text = pet, tone = Chip.Tone.muted, icon = "paw-print")
                        if (account.client.onPawMiaw == true) Chip(text = L("On PawMiaw"), tone = Chip.Tone.info, icon = "smartphone")
                    }
                    // Unlinked: what they owe from before stays theirs (docs/family-money.md §1).
                    ClientsDisconnectedMark(
                        disconnectedAt = account.client.disconnectedAt, disconnectedBy = account.client.disconnectedBy, withDetail = true,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        },
    )
}

// MARK: What is open

@Composable
private fun AccountOpenBills(account: CashAccount, money: (Int) -> String) {
    val nav = LocalNavigator.current
    SectionCard(L("What is open"), subtitle = L("Oldest first — the order a payment settles them.")) {
        Column(Modifier.fillMaxWidth()) {
            account.open.forEachIndexed { index, bill ->
                if (index > 0) RowDivider()
                // A store sale opens its sale; a bill opens the bill (its refunds live there).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainButton { nav.push(if (bill.source == "store") ProviderRoute.sale(bill.id) else ProviderRoute.invoice(bill.id)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(bill.folio, style = collectionsMono(14, FontWeight.Bold), color = Theme.inkStrong)
                        Text(
                            listOfNotNull(CashText.source(bill.source), bill.petName, bill.dueDay?.let { LF("Due %@", CashDay.short(it)) }).mapNotNull { it.nonEmpty }.joinToString(" · "),
                            style = Theme.text(12),
                            color = Theme.inkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(money(bill.balanceDueAmount), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                        if (bill.daysOverdue > 0) {
                            Text(CashText.daysLate(bill.daysOverdue), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
                        } else if (bill.amountPaid > 0) {
                            Text(LF("of %@", money(bill.totalAmount - (bill.refundedAmount ?: 0))), style = Theme.text(12), color = Theme.inkSoft)
                        }
                    }
                    Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
                }
            }
            ListFooter(count = LP(account.open.size, "%d open bill", "%d open bills"), detail = LF("%@ owed in total", money(account.owed)))
        }
    }
}

// MARK: Movements

@Composable
private fun AccountMovements(account: CashAccount, money: (Int) -> String, allMovements: Boolean, onToggle: () -> Unit) {
    val entries = account.statement.reversed()
    if (entries.isEmpty()) return
    val shown = if (allMovements) entries else entries.take(6)
    SectionCard(L("Movements"), subtitle = L("Charges add to the balance; payments and deposits take from it. A balance below zero is credit they hold.")) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            shown.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                AccountMovementRow(entry, account, money)
            }
            ListFooter(count = if (shown.size < entries.size) LF("Showing %d of %d", shown.size, entries.size) else LP(entries.size, "%d movement", "%d movements")) {
                if (entries.size > 6) {
                    Text(
                        if (allMovements) L("Show fewer") else L("Show all"),
                        modifier = Modifier.plainButton(onClick = onToggle),
                        style = Theme.text(14, FontWeight.Bold),
                        color = Theme.blue,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountMovementRow(entry: CashAccount.Entry, account: CashAccount, money: (Int) -> String) {
    val charge = entry.type == "charge"
    val deposit = entry.label == "Deposit" || entry.label == "Deposit returned"
    val what = when {
        charge -> L("Charge")
        entry.label == "Deposit" -> L("Deposit")
        entry.label == "Deposit returned" -> L("Deposit returned")
        entry.type == "refund" -> L("Refund")
        else -> L("Payment")
    }
    val zone = account.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    val day = DateText.parse(entry.at)?.let { CashDay.short(DateText.dayKey(it, zone)) }
    val line = listOf(day, if (charge) entry.source?.let { CashText.source(it) } else entry.method?.let { CashText.method(it) }, if (deposit) null else entry.label).mapNotNull { it.nonEmpty }
    val soft = Theme.inkSoft
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(Theme.text(14, FontWeight.Bold).toSpanStyle()) { append(what) }
                    entry.folio.nonEmpty?.let { withStyle(collectionsMono(13).toSpanStyle().copy(color = soft)) { append(" · $it") } }
                },
                color = Theme.inkStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(line.joinToString(" · "), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("${if (entry.type == "payment") "−" else "+"}${money(abs(entry.amount))}", style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
            Text(
                if (entry.balance < 0) LF("Credit %@", money(-entry.balance)) else LF("Balance %@", money(entry.balance)),
                style = Theme.text(12).monospacedDigit(),
                color = Theme.inkSoft,
            )
        }
    }
}

// MARK: Send a reminder

@Composable
private fun AccountRemind(account: CashAccount, reminding: String?, onSend: (String) -> Unit) {
    SectionCard(L("Send a reminder"), subtitle = L("The message says what they owe. Every reminder is written down as the last contact.")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val phone = account.client.phone != null
            val email = account.client.email
            val onApp = account.client.onPawMiaw == true
            // After an unlink the reminder still reaches the family the bills belong to; the server says `not_on_pawmiaw` when nobody is left.
            val disconnected = account.client.disconnectedAt != null
            AccountReminderButton("whatsapp", "WhatsApp", if (phone) L("A message with the amount") else L("No phone on file"), "message-circle", phone, reminding, onSend)
            AccountReminderButton("email", L("Email"), email ?: L("No email on file"), "mail", email != null, reminding, onSend)
            AccountReminderButton(
                "app", L("In PawMiaw"),
                if (onApp) L("A notification on their phone") else if (disconnected) L("For the bills from before they unlinked") else L("Not on PawMiaw"),
                "smartphone", onApp || disconnected, reminding, onSend,
            )
        }
    }
}

@Composable
private fun AccountReminderButton(channel: String, title: String, hint: String, icon: String, available: Boolean, reminding: String?, onSend: (String) -> Unit) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (available) 1f else 0.5f)
            .clip(box)
            .plainButton(enabled = available && reminding == null) { onSend(channel) }
            .background(Theme.surface, box)
            .border(2.dp, Theme.borderSoft, box)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
            if (reminding == channel) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkStrong, strokeWidth = 2.dp)
            } else {
                Glyph(icon, 16.dp, tint = Theme.inkStrong)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(hint, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// MARK: History

@Composable
private fun AccountTimeline(account: CashAccount, money: (Int) -> String) {
    if (account.notes.isEmpty()) return
    val zone = account.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    SectionCard(L("History")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (note in account.notes) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(32.dp).background(Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
                        Glyph(if (note.kind == "promise") "handshake" else if (note.kind == "reminder") "bell-ring" else "file-text", 16.dp, tint = Theme.inkSoft)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            listOfNotNull(note.author, CashDay.`when`(note.createdAt, zone = zone), note.balanceAmount?.let { LF("owed %@", money(it)) }).mapNotNull { it.nonEmpty }.joinToString(" · "),
                            style = Theme.text(12),
                            color = Theme.inkSoft,
                        )
                        if (note.kind == "reminder") {
                            Text(
                                if (note.channel == "whatsapp") L("Reminder by WhatsApp") else if (note.channel == "email") L("Reminder by email") else L("Reminder in PawMiaw"),
                                style = Theme.text(14, FontWeight.Bold),
                                color = Theme.inkStrong,
                            )
                        }
                        val day = note.promisedOn
                        val status = note.promiseStatus
                        if (note.kind == "promise" && day != null && status != null) {
                            CashPromiseChip(status = status, day = day, amount = note.promisedAmount?.let(money))
                        }
                        val body = note.body.nonEmpty
                        if (body != null && note.kind != "reminder") {
                            Text(body, style = Theme.text(14), color = Theme.inkStrong)
                        }
                    }
                }
            }
        }
    }
}

// MARK: Credit limit

@Composable
private fun AccountCreditLimit(account: CashAccount, money: (Int) -> String, onEdit: () -> Unit) {
    val workspace = Workspace.shared
    val manages = workspace.can(Permission.billingManage)
    SectionCard(
        title = L("Credit limit"),
        subtitle = L("The most they can owe. The store will not sell on account past it."),
        trailing = {
            if (manages) PMButton(L("Edit"), variant = ButtonVariant.outline, size = ButtonSize.compact) { onEdit() }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val limit = account.client.creditLimitAmount
            FlowLayout(spacing = 8.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("shield-check", 16.dp, tint = Theme.inkSoft)
                    Text(limit?.let { LF("Up to %@", money(it)) } ?: L("No limit"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                }
                if (limit != null && account.owed > limit) Chip(text = L("Over the limit"), tone = Chip.Tone.danger)
            }
            if (!manages) {
                Text(L("Only billing managers can change it."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

// MARK: - Sheets on an account

/** One payment, applied to the oldest debt first (web: the account's "Collect" step). */
@Composable
fun CashCollectSheet(account: CashAccount, onDone: () -> Unit = {}, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val device = remember { CashDeviceRegister() }
    val form = remember { FormState() }
    // iOS fills what they owe in on appear; here it starts there.
    val amount = remember { mutableStateOf<Int?>(account.owed) }
    val method = remember { mutableStateOf("cash") }
    val reference = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var requestId by remember { mutableStateOf(cashRequestId()) }
    var seed by remember { mutableIntStateOf(0) }

    val methods = CashText.methods + (if (account.credit > 0) listOf("credit") else emptyList())
    val money = { cents: Int -> cashMoney(cents, account.currency) }

    /** How the amount settles the open bills, oldest first, and what would be left over. */
    data class Part(val folio: String, val amount: Int, val settles: Boolean)
    var left = amount.value ?: 0
    val parts = ArrayList<Part>()
    for (bill in account.open) {
        if (left <= 0) break
        val take = min(left, bill.balanceDueAmount)
        parts.add(Part(folio = bill.folio, amount = take, settles = take >= bill.balanceDueAmount))
        left -= take
    }
    val leftover = max(0, left)

    fun submit() {
        val value = amount.value ?: 0
        val problem = when {
            value <= 0 -> L("Enter the amount received")
            value > account.owed -> LF("That is %@ more than they owe.", money(value - account.owed))
            method.value == "credit" && value > account.credit -> LF("They have %@ of credit", money(account.credit))
            else -> null
        }
        if (!form.check(listOf("amount" to problem))) return
        if (method.value == "cash" && device.sessionId == null) {
            Toast.error(L("Open the register to take cash"))
            return
        }
        working = true
        scope.launch {
            try {
                val credit = method.value == "credit"
                val result: CashCollectResult = API.post(
                    "/provider-finance/clients/${account.client.id}/collect",
                    mapOf(
                        "amount" to value, "method" to method.value, "reference" to (if (credit) null else reference.value.nonEmpty),
                        "registerSessionId" to (if (credit) null else device.sessionId), "clientRequestId" to requestId,
                    ),
                )
                if (result.replayed == true) {
                    Toast.show(LF("That payment was already recorded: %@ collected, nothing taken twice.", money(result.collected)))
                } else {
                    Toast.show(
                        if (result.owedAfter > 0) LF("%@ collected. %@ still owed.", money(result.collected), money(result.owedAfter))
                        else LF("%@ collected. The account is paid up.", money(result.collected)),
                    )
                }
                CashDeviceRegister.changed.tryEmit(Unit)
                announceDataChanged()
                onDone()
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                form.apply(failure, mapOf("amount" to "amount"))
            } finally {
                working = false
            }
        }
    }

    CashKeepsRegister(device)
    val pets = account.open.mapNotNull { it.petName.nonEmpty }.toSet().sorted()
    ProviderModal(
        title = L("Collect a payment"),
        subtitle = L("One payment, applied to the oldest debt first."),
        systemImage = "hand-coins",
        accent = ModalAccent.pink,
        isDirty = amount.value != account.owed || method.value != "cash",
        form = form,
        primaryTitle = L("Collect"),
        isWorking = working,
        cancelTitle = L("Back"),
        primaryIcon = "hand-coins",
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        // Who pays and what they owe (web `ModalContextPills` with the "Owes" pill).
        ContextPills(client = if (account.client.walkIn == true) null else account.client.name, pets = pets) {
            Chip(text = LF("Owes %@", money(account.owed)), tone = Chip.Tone.muted, size = Chip.Size.large)
        }
        ModalSection(title = L("Amount received")) {
            key(seed) {
                MoneyField(label = L("Amount received"), cents = amount, key = "amount", form = form)
            }
            PMButton(L("All of it"), variant = ButtonVariant.outline, size = ButtonSize.compact) {
                amount.value = if (method.value == "credit") min(account.owed, account.credit) else account.owed
                seed += 1
                requestId = cashRequestId()
            }
            if ((amount.value ?: 0) > 0) {
                Column(
                    Modifier.fillMaxWidth().background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (leftover > 0) {
                        Text(LF("That is %@ more than they owe.", money(leftover)), style = Theme.text(14, FontWeight.Bold), color = Theme.destructive)
                    } else {
                        for (part in parts) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(part.folio, modifier = Modifier.weight(1f), style = collectionsMono(14), color = Theme.inkStrong)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (part.settles) LF("%@ · paid in full", money(part.amount)) else LF("%@ on account", money(part.amount)),
                                    style = Theme.text(14),
                                    color = Theme.inkSoft,
                                )
                            }
                        }
                    }
                }
            }
        }
        ModalSection(title = L("How they paid")) {
            FlowLayout(spacing = 8.dp) {
                for (entry in methods) {
                    CashMethodPill(title = CashText.method(entry), isOn = method.value == entry) {
                        method.value = entry
                        requestId = cashRequestId()
                    }
                }
            }
            if (method.value == "credit") {
                val over = (amount.value ?: 0) > account.credit
                Text(
                    LF("From their deposits: %@ available.", money(account.credit)),
                    style = Theme.text(14, if (over) FontWeight.Bold else FontWeight.Normal),
                    color = if (over) Theme.destructive else Theme.inkSoft,
                )
            } else if (method.value != "cash") {
                PMTextField(L("Reference"), text = reference, prompt = L("Reference (authorization, transfer…)"))
            }
            CashRegisterGate(device = device, wantsCash = method.value == "cash")
        }
    }
}

@Serializable
private data class CashNoteSaved(val id: String)

/** What was talked about, and when they said they would pay (web: the account's "Follow up"). */
@Composable
fun CashNoteSheet(account: CashAccount, onDone: () -> Unit = {}, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val body = remember { mutableStateOf("") }
    val promising = remember { mutableStateOf(false) }
    // A week from the business's today, never the device's (iOS sets it on appear).
    val promisedOn = remember { mutableStateOf(CashDay.shift(account.today, 7)) }
    val promised = remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }

    fun submit() {
        if (!form.check(listOf("body" to (if (!promising.value && body.value.nonEmpty == null) L("Write the note") else null)))) return
        working = true
        scope.launch {
            try {
                API.post<CashNoteSaved>(
                    "/provider-finance/clients/${account.client.id}/notes",
                    mapOf(
                        "kind" to (if (promising.value) "promise" else "note"), "body" to body.value.nonEmpty,
                        "promisedAmount" to (if (promising.value) promised.value else null), "promisedOn" to (if (promising.value) promisedOn.value else null),
                    ),
                )
                Toast.show(if (promising.value) L("Promise saved. It shows as kept once they pay.") else L("Note saved."))
                announceDataChanged()
                onDone()
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                form.apply(failure, mapOf("body" to "body"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Follow up"),
        subtitle = L("What you talked about, and when they said they would pay."),
        systemImage = "notebook-pen",
        accent = ModalAccent.pink,
        isDirty = body.value.isNotEmpty() || promising.value,
        form = form,
        primaryTitle = if (promising.value) L("Save promise") else L("Save note"),
        isWorking = working,
        primaryIcon = "notebook-pen",
        onPrimary = { submit() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        PMTextField(L("Note"), text = body, key = "body", form = form, prompt = L("Called, will pay on Friday…"), optional = promising.value, multiline = true)
        ToggleRow(title = L("They promised to pay"), isOn = promising)
        if (promising.value) {
            DayField(label = L("By@day"), day = promisedOn, min = account.today)
            MoneyField(label = L("Amount (optional)"), cents = promised, optional = true)
        }
    }
}

@Serializable
private data class CashLimitSaved(val creditLimitAmount: Int? = null)

/** The most they can owe (web: the account's "Credit limit"). */
@Composable
fun CashCreditLimitSheet(account: CashAccount, onDone: () -> Unit = {}, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val current = account.client.creditLimitAmount
    // iOS sets the limit on appear; here it starts there.
    val limit = remember { mutableStateOf(current) }
    var working by remember { mutableStateOf(false) }

    fun save(value: Int?) {
        working = true
        scope.launch {
            try {
                API.put<CashLimitSaved>("/provider-finance/clients/${account.client.id}/credit-limit", mapOf("creditLimitAmount" to value))
                Toast.show(value?.let { LF("Limit set to %@.", cashMoney(it, account.currency)) } ?: L("No limit now."))
                announceDataChanged()
                onDone()
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                form.apply(failure, mapOf("creditLimitAmount" to "limit"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Credit limit"),
        subtitle = L("The most they can owe. The store will not sell on account past it."),
        systemImage = "shield-check",
        accent = ModalAccent.pink,
        isDirty = limit.value != current,
        form = form,
        primaryTitle = L("Save limit"),
        isWorking = working,
        secondaryActions = if (current != null) listOf(ModalAction(L("Remove")) { save(null) }) else emptyList(),
        onPrimary = { save(limit.value) },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium)),
    ) {
        MoneyField(
            label = L("Credit limit"),
            cents = limit,
            key = "limit",
            form = form,
            hint = current?.let { LF("Up to %@", cashMoney(it, account.currency)) } ?: L("No limit"),
            optional = true,
        )
        if (current != null && account.owed > current) {
            Chip(text = L("Over the limit"), tone = Chip.Tone.danger, size = Chip.Size.medium)
        }
    }
}
