package net.orandja.shadowlayout

import android.view.View
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal class OnUpdate<T>(default: T, val transformation: ((T) -> T)? = null, val onUpdate: ((T) -> Unit)? = null) :
    ReadWriteProperty<View, T> {
    var backing: T = default
    override fun getValue(thisRef: View, property: KProperty<*>): T = backing
    override fun setValue(thisRef: View, property: KProperty<*>, value: T) {
        if (backing == value) return
        backing = transformation?.invoke(value) ?: value
        onUpdate?.invoke(value)
        thisRef.postInvalidate()
    }
}