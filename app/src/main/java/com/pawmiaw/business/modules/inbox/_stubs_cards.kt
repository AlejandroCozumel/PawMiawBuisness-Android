package com.pawmiaw.business.inbox

import com.pawmiaw.business.AppLanguage
import com.pawmiaw.business.LocalizationManager
import kotlinx.serialization.Serializable

//
//  Stand-in for the Inbox module's Olfato AI words, used by AuditHistoryCard's Olfato lines.
//  The merge deletes this file.
//

// STUB: InboxOlfatoModels.swift
@Serializable
data class InboxOlfatoWords(val es: String? = null, val en: String? = null) {
    val text: String
        get() = if (LocalizationManager.currentLanguage == AppLanguage.spanish) (es ?: en ?: "") else (en ?: es ?: "")
}
