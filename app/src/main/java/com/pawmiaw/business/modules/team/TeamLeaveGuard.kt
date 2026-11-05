package com.pawmiaw.business.team

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pawmiaw.business.*

//
//  TeamLeaveGuard.kt (iOS TeamLeaveGuard.swift)
//
//  "¿Salir sin guardar?" (web: the blocker on routes/provider.settings.tsx
//  and provider.availability.tsx): while a page has changes that aren't
//  saved, its back button asks first, in the app's sheet, and the swipe back
//  waits. Every `TeamSaveSection` says by its title when it has unsaved
//  changes (`TeamUnsavedKey`, in TeamShared.kt), so a page knows which parts
//  would be lost.
//
//  iOS `.modifier(TeamLeaveGuard(dirty: …, message: …))` → call `TeamLeaveGuard(dirty = …, message = …)`
//  inside the page's `providerPage { }` content: the bar's back arrow and the system back
//  (the shell's `PageBackHandler`) ask while `dirty`.
//

/** While `dirty`, the back of a pushed page (or a sheet's root page) asks before leaving. */
@Composable
fun TeamLeaveGuard(
    dirty: Boolean,
    message: String,
    /** Availability asks with its clock (web `ProviderConfirmModal`, Clock3); Settings with the triangle. */
    systemImage: String? = null,
) {
    val dismiss = LocalDismiss.current
    // iOS `@Environment(\.isPresented)`: a page pushed on a stack, or shown in a sheet.
    val isPresented = LocalIsPushedPage.current || LocalAppSheet.current != null
    var asking by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val guarding = dirty && isPresented

    // iOS hides the bar's back button and puts its own "‹ Back" that asks first.
    PageBackHandler(enabled = guarding) { asking = true }

    if (asking) {
        AppSheet(onDismissRequest = {
            asking = false
            if (leaving) {
                leaving = false
                dismiss()
            }
        }) {
            ConfirmSheet(
                title = L("Leave without saving?"),
                message = message,
                confirmTitle = L("Leave without saving"),
                destructive = true,
                cancelTitle = L("Keep editing"),
                icon = systemImage ?: "triangle-alert",
            ) { _ ->
                leaving = true
            }
        }
    }
}
