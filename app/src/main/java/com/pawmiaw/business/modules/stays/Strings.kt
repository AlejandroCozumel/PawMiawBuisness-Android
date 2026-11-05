package com.pawmiaw.business.stays

import com.pawmiaw.business.LocalizationManager
import com.pawmiaw.business.localized

// The Stays module's own table first (iOS reads Stays.strings by #fileID), then the shared one.
fun L(key: String): String = localized(key, "Stays")
fun LF(key: String, vararg arguments: Any?): String = LocalizationManager.format(L(key), arguments)
fun LP(count: Int, one: String, other: String): String = LocalizationManager.format(L(if (count == 1) one else other), arrayOf(count))
