package com.pawmiaw.business.stock

import com.pawmiaw.business.LocalizationManager
import com.pawmiaw.business.localized

// The Stock module's own table first (iOS reads Stock.strings by #fileID), then the shared one.
fun L(key: String): String = localized(key, "Stock")
fun LF(key: String, vararg arguments: Any?): String = LocalizationManager.format(L(key), arguments)
fun LP(count: Int, one: String, other: String): String = LocalizationManager.format(L(if (count == 1) one else other), arrayOf(count))
