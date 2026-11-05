package com.pawmiaw.business.money

import com.pawmiaw.business.LocalizationManager
import com.pawmiaw.business.localized

//
//  The Money module's words: its own table first (Money.json), then the shared one.
//  A same-package function beats the root `L`, so inside the module `L("…")` is this one.
//

fun L(key: String): String = localized(key, "Money")
fun LF(key: String, vararg arguments: Any?): String = LocalizationManager.format(L(key), arguments)
fun LP(count: Int, one: String, other: String): String = LocalizationManager.format(L(if (count == 1) one else other), arrayOf(count))
