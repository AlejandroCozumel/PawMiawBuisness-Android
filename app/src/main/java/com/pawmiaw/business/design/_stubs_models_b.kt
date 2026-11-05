package com.pawmiaw.business

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

// Stand-ins for the design kit symbols the models-b files use (Design/*.swift, ported by the
// design agents). No real code: deleted at merge, the real ones take their place.

// STUB: Design/Forms.swift
class FormState {
    var errors: Map<String, String> by mutableStateOf(emptyMap())
    var scrollTarget: String? by mutableStateOf(null)
    fun error(key: String): String? = TODO("STUB: Forms.swift")
    fun clear(key: String): Unit = TODO("STUB: Forms.swift")
}

// STUB: Design/Forms.swift (FieldChrome, `.fieldChrome(invalid:multiline:)`)
@Composable
fun Modifier.fieldChrome(invalid: Boolean = false, multiline: Boolean = false): Modifier = TODO("STUB: Forms.swift")

// STUB: Design/Forms.swift (FormScroll's target: SwiftUI `.id(key)` read by `ScrollViewReader.scrollTo`)
fun Modifier.scrollID(id: String): Modifier = TODO("STUB: Forms.swift")

// STUB: Design/Components.swift (Tick)
@Composable
fun Tick(on: Boolean, modifier: Modifier = Modifier): Unit = TODO("STUB: Components.swift")

// STUB: Design/Components.swift (StatusChip, `init(text:state:outlined:small:)`)
@Composable
fun StatusChip(text: String, state: ProviderState, outlined: Boolean = false, small: Boolean = false, modifier: Modifier = Modifier): Unit =
    TODO("STUB: Components.swift")

// STUB: Design/ConsentsCard.swift
data class ConsentAct(
    var appointmentId: String? = null,
    var stayId: String? = null,
    var recordId: String? = null,
    var providerClientPetId: String? = null,
)

// STUB: Design/ConsentsCard.swift
data class ActPet(
    val id: String,
    val name: String,
)

// STUB: Design/ConsentsCard.swift
data class ConsentContext(
    val act: ConsentAct,
    var businessName: String? = null,
    val clientName: String,
    val familyOnApp: Boolean,
    var clientEmail: String? = null,
    var clientPhone: String? = null,
    var pets: List<ActPet> = emptyList(),
    var actLabel: String? = null,
    var started: Boolean = false,
    var currency: String? = null,
)
