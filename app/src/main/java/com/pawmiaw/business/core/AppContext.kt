package com.pawmiaw.business

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The app's context and small stores, set once by the Application.
 * `AppContext.defaults` is iOS `UserDefaults.standard`.
 */
object AppContext {
    lateinit var app: Application
        private set

    val context: Context get() = app

    val defaults: SharedPreferences by lazy { app.getSharedPreferences("pawmiaw_negocios", Context.MODE_PRIVATE) }

    fun init(application: Application) {
        app = application
    }
}

/**
 * iOS `NotificationCenter` names the app posts: a 401 (the session may have ended)
 * and "something changed that other screens show" (`announceDataChanged()`).
 */
object AppEvents {
    private val _sessionMayHaveExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** An authenticated request was answered 401 (iOS `.pawMiawSessionMayHaveExpired`). */
    val sessionMayHaveExpired: SharedFlow<Unit> = _sessionMayHaveExpired

    private val _dataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Something changed that other screens show: reload (iOS `.pawMiawDataChanged`). */
    val dataChanged: SharedFlow<Unit> = _dataChanged

    fun postSessionMayHaveExpired() { _sessionMayHaveExpired.tryEmit(Unit) }
    fun postDataChanged() { _dataChanged.tryEmit(Unit) }
}

/** Tell every screen that data changed (they reload on `ReloadsOnScope`). */
fun announceDataChanged() = AppEvents.postDataChanged()
