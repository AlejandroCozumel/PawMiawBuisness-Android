package com.pawmiaw.business.money

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

//
//  CashServiceDefaults.kt (iOS CashServiceDefaults.swift)
//
//  "Lo que cobra cada visita" (web: catalog-page/service-defaults-panel.tsx):
//  which catalog service each kind of visit starts its bill with. A booking
//  for "Baño y cepillado" starts its bill with the item chosen here; it can
//  still be changed on the visit. One optional item per kind, grouped by lane.
//

/** The kinds of visit the business offers (`/appointment-services?scope=provider`). */
@Serializable
data class CashServiceCatalog(
    val categories: List<Lane>,
    val services: List<Service>,
) {
    @Serializable
    data class Lane(val id: String, val label: String, val description: String? = null)

    @Serializable
    data class DefaultItem(val id: String, val name: String, val currency: String? = null, val defaultUnitAmount: Int, val active: Boolean)

    @Serializable
    data class Service(val id: String, val category: String, val label: String, val description: String? = null, val defaultBillingItem: DefaultItem? = null)
}

@Composable
fun CashServiceDefaultsPanel(
    /** The kinds of visit, read by the Catalog page; null while loading. */
    catalog: CashServiceCatalog?,
    /** Active services that can be a default. */
    billingItems: List<CashCatalogItem>,
    canEdit: Boolean,
    /** Reads the kinds of visit again after a change. */
    reload: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(setOf<String>()) }

    val groups: List<Pair<CashServiceCatalog.Lane, List<CashServiceCatalog.Service>>> = catalog?.let { known ->
        known.categories.mapNotNull { lane ->
            val services = known.services.filter { it.category == lane.id }
            if (services.isEmpty()) null else lane to services
        }
    } ?: emptyList()

    fun choose(service: CashServiceCatalog.Service, item: CashServiceCatalog.DefaultItem?) {
        if (!canEdit || item?.id == service.defaultBillingItem?.id) return
        saving = service.id
        scope.launch {
            try {
                API.patch<EmptyResponse>("/provider-catalog/service-defaults/${service.id}", mapOf("providerCatalogItemId" to item?.id))
                Toast.show(item?.let { LF("%@ now starts its bill with %@.", service.label, it.name) } ?: LF("%@ no longer has a default item.", service.label))
                reload()
                announceDataChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                Toast.error(failure, fallback = L("Could not change what this visit charges."))
            } finally {
                saving = null
            }
        }
    }

    val all = groups.flatMap { it.second }
    val mapped = all.count { it.defaultBillingItem != null }
    // The first lane starts open (iOS `.onAppear` and `.onChange(of: groups.first?.lane.id)`).
    val firstLane = groups.firstOrNull()?.first?.id
    LaunchedEffect(firstLane) {
        if (open.isEmpty() && firstLane != null) open = setOf(firstLane)
    }

    // Web `ServiceDefaultsPanel`: a card, its header with "3 of 5 set", one folding box per lane.
    SectionCard(
        title = L("What each visit charges"),
        subtitle = L("The item a booking starts its bill with. Staff can still change it on the visit."),
        icon = "calendar-check-2",
        accent = Theme.blue,
        modifier = modifier,
        trailing = {
            if (all.isNotEmpty()) {
                Box(
                    Modifier
                        .height(28.dp)
                        .background(Theme.wash(Theme.blue, 10.0), CapsuleShape)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(LF("%d of %d set", mapped, all.size), style = Theme.text(12, FontWeight.ExtraBold), color = Theme.inkStrong, maxLines = 1, softWrap = false)
                }
            }
        },
    ) {
        if (catalog == null) {
            LoadingState()
        } else if (groups.isEmpty()) {
            EmptyState(
                systemImage = "calendar-check-2", title = L("No visit types yet"),
                message = L("Once the workspace offers a kind of visit, its services show up here so each can start its bill with an item."),
                accent = Theme.mango,
            )
        } else {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { (lane, services) ->
                    key(lane.id) {
                        CashServiceLane(
                            lane = lane,
                            services = services,
                            isOpen = lane.id in open,
                            billingItems = billingItems,
                            canEdit = canEdit,
                            saving = saving,
                            toggle = { open = if (lane.id in open) open - lane.id else open + lane.id },
                            choose = { service, item -> choose(service, item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CashServiceLane(
    lane: CashServiceCatalog.Lane,
    services: List<CashServiceCatalog.Service>,
    isOpen: Boolean,
    billingItems: List<CashCatalogItem>,
    canEdit: Boolean,
    saving: String?,
    toggle: () -> Unit,
    choose: (CashServiceCatalog.Service, CashServiceCatalog.DefaultItem?) -> Unit,
) {
    val assigned = services.count { it.defaultBillingItem != null }
    val hue = Hue.category(lane.id)
    val shape = RoundedCornerShape(Theme.boxRadius)
    val turn by animateFloatAsState(if (isOpen) 180f else 0f, tween(200, easing = EaseOut), label = "lane chevron")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Theme.borderSoft, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (isOpen) Theme.wash(Theme.surfaceMuted, 45.0) else Theme.surface)
                .plainButton(label = lane.label, onClick = toggle)
                .semantics { selected = isOpen }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(Theme.wash(if (hue == Hue.grey) Theme.blue else hue.color, 24.0), RoundedCornerShape(Theme.chipRadius)),
                contentAlignment = Alignment.Center,
            ) { Glyph(laneIcon(lane.id), 16.dp, tint = Theme.inkStrong) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(lane.label, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (assigned == services.size) {
                        Glyph("circle-check", 16.dp, tint = Theme.blue, modifier = Modifier.semantics { contentDescription = L("All set") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProgressBar(
                        value = assigned.toDouble() / maxOf(1, services.size).toDouble(),
                        height = 6.dp,
                        track = Theme.borderSoft,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 160.dp),
                    )
                    Text(LF("%d of %d set", assigned, services.size), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.width(8.dp))
            Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(
            visible = isOpen,
            enter = expandVertically(tween(200, easing = EaseOut)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200, easing = EaseOut)) + fadeOut(tween(200)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp)) {
                services.forEachIndexed { index, service ->
                    key(service.id) {
                        if (index > 0) RowDivider()
                        CashServiceDefaultRow(service, billingItems, canEdit, saving == service.id, choose)
                    }
                }
            }
        }
    }
}

/** A lane's icon (web `PROVIDER_CATEGORY_ICONS`; the vet, groomer and walker marks drawn with Lucide's nearest). */
private fun laneIcon(lane: String): String = when (lane) {
    "vet" -> "stethoscope"
    "groomer" -> "scissors"
    "walker" -> "footprints"
    "sitter" -> "house"
    "trainer" -> "dumbbell"
    "daycare", "boarding" -> "building-2"
    "pharmacy" -> "package"
    "emergency" -> "siren"
    "other" -> "ellipsis"
    else -> "calendar-check-2"
}

@Composable
private fun CashServiceDefaultRow(
    service: CashServiceCatalog.Service,
    billingItems: List<CashCatalogItem>,
    canEdit: Boolean,
    isSaving: Boolean,
    choose: (CashServiceCatalog.Service, CashServiceCatalog.DefaultItem?) -> Unit,
) {
    val current = service.defaultBillingItem
    val listed = billingItems.map { CashServiceCatalog.DefaultItem(id = it.id, name = it.name, currency = it.currency, defaultUnitAmount = it.defaultUnitAmount, active = it.active) }
    val options = current?.let { item -> if (listed.any { it.id == item.id }) listed else listOf(item) + listed } ?: listed
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(service.label, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(
                    if (current != null) L("Set@default") else L("Not set@default"),
                    style = brandText(10.6, FontWeight.ExtraBold),
                    color = if (current != null) Theme.inkStrong else Theme.inkSoft,
                    maxLines = 1,
                    modifier = Modifier
                        .background(if (current != null) Theme.wash(Theme.lime, 32.0) else Color.Transparent, CapsuleShape)
                        .then(if (current == null) Modifier.border(1.dp, Theme.borderSoft, CapsuleShape) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            service.description.nonEmpty?.let {
                Text(it, style = Theme.text(14), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        AppMenu(
            label = {
                // Web TOOLBAR_SELECT: a 40 capsule, semibold, the chevron at the right.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Theme.inputFill, CapsuleShape)
                        .border(1.dp, Theme.borderSoft, CapsuleShape)
                        .padding(start = 16.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        current?.let { "${it.name} · ${cashMoney(it.defaultUnitAmount, it.currency)}" } ?: L("No default item"),
                        style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSaving) CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                    Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = canEdit && !isSaving,
            accessibilityLabel = LF("Default item for %@", service.label),
        ) {
            item(L("No default item")) { choose(service, null) }
            options.forEach { option ->
                item("${option.name} · ${cashMoney(option.defaultUnitAmount, option.currency)}${if (option.active) "" else " · " + L("archived")}") { choose(service, option) }
            }
        }
        if (current != null && !current.active) {
            Text(L("This item is archived — choose an active one."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.destructive)
        } else if (current == null) {
            Text(L("Bookings use the visit name until you pick one."), style = Theme.text(12), color = Theme.inkSoft)
        }
    }
}
