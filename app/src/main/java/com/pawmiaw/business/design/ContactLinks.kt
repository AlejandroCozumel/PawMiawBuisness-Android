package com.pawmiaw.business

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

//
//  Calling a client and opening WhatsApp with them (iOS ContactLinks.swift). The app never
//  sends a message by itself: these open the phone or WhatsApp, the person sends. The links
//  are strings for `rememberOpenURL()` (iOS `openURL(url)`).
//

object ContactLinks {
    /** `tel:` for a phone number, null when there is none. */
    fun phone(number: String?): String? {
        val text = number.nonEmpty ?: return null
        val dialable = text.filter { it.isDigit() || it == '+' }
        if (dialable.count { it.isDigit() } < 7) return null
        return "tel:$dialable"
    }

    /** `https://wa.me/…` for a phone number; a 10-digit Mexican number gets +52. With `text`, WhatsApp opens with it written. */
    fun whatsApp(number: String?, text: String? = null): String? {
        val trimmed = number?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 10) return null
        val international = if (trimmed.startsWith("+")) digits else if (digits.length == 10) "52$digits" else digits
        val builder = Uri.parse("https://wa.me/$international").buildUpon()
        text.nonEmpty?.let { builder.appendQueryParameter("text", it) }
        return builder.build().toString()
    }

    /** Maps directions to an address (iOS opens Apple Maps; here Google Maps, the app or the web). */
    fun maps(address: String?): String? {
        val place = address.nonEmpty ?: return null
        return Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("destination", place)
            .build()
            .toString()
    }
}

/** Call and WhatsApp, as two round icon controls. */
@Composable
fun ContactButtons(phone: String?, modifier: Modifier = Modifier) {
    val openURL = rememberOpenURL()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ContactLinks.phone(phone)?.let { tel ->
            IconCircleButton(systemImage = "phone.fill", label = L("Call")) { openURL(tel) }
        }
        ContactLinks.whatsApp(phone)?.let { whatsapp ->
            IconCircleButton(systemImage = "message.fill", label = "WhatsApp") { openURL(whatsapp) }
        }
    }
}

/** A phone number that calls when tapped. */
@Composable
fun PhoneLink(phone: String, modifier: Modifier = Modifier) {
    val url = ContactLinks.phone(phone)
    if (url != null) {
        val openURL = rememberOpenURL()
        Row(
            modifier.plainButton(role = Role.Button) { openURL(url) },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("phone.fill", 14.dp, tint = Theme.blueText)
            Text(phone, style = Theme.text(14, FontWeight.Bold), color = Theme.blueText)
        }
    } else {
        Text(phone, style = Theme.text(14), color = Theme.inkStrong, modifier = modifier)
    }
}
