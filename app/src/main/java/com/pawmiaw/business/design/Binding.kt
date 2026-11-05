package com.pawmiaw.business

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable

//
//  Swift `@Binding` as Compose `MutableState<T>`. A caller with a `remember { mutableStateOf() }`
//  passes it as it is; a caller that holds the value elsewhere builds one from a getter and a
//  setter (Swift `Binding(get:set:)`), and `.constant(x)` is `constantState(x)`.
//

/** Swift `Binding(get:set:)`. */
fun <T> stateOf(get: () -> T, set: (T) -> Unit): MutableState<T> = object : MutableState<T> {
    override var value: T
        get() = get()
        set(v) = set(v)
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

/** Swift `.constant(x)`: a binding that ignores writes. */
fun <T> constantState(value: T): MutableState<T> = stateOf({ value }, {})

/** A binding to one property of another (Swift `$form.name`). */
@Stable
fun <S, T> MutableState<S>.map(get: (S) -> T, set: (S, T) -> S): MutableState<T> =
    stateOf({ get(this.value) }, { this.value = set(this.value, it) })
