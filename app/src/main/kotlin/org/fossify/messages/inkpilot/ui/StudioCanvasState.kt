package org.fossify.messages.inkpilot.ui

import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup

/** Retains live panels across tab changes and their hierarchy across Activity recreation. */
internal class StudioCanvasState(private val saved: Bundle = Bundle()) {
    private val views = mutableMapOf<StudioRoute, View>()
    private fun key(route: StudioRoute) = when (route) {
        is StudioRoute.Tab -> "tab.${route.tab.name}"
        else -> route.encode().getString("kind") + "." + route.toString()
    }
    fun view(route: StudioRoute, create: () -> View): View = views.getOrPut(route) {
        create().also { view ->
            var nextId = 0x00e00000
            fun identify(node: View) {
                if (node.id == View.NO_ID) node.id = nextId++
                if (node is ViewGroup) for (index in 0 until node.childCount) identify(node.getChildAt(index))
            }
            identify(view)
            saved.getSparseParcelableArray<Parcelable>(key(route))?.let(view::restoreHierarchyState)
        }
    }
    fun invalidate(route: StudioRoute) {
        views.remove(route)?.let { saveView(route, it) }
    }
    fun discard(route: StudioRoute) { views.remove(route); saved.remove(key(route)) }
    fun invalidateThreads(keepState: (Long) -> Boolean = { true }) {
        views.keys.filterIsInstance<StudioRoute.Thread>().toList().forEach { route ->
            if (keepState(route.threadId)) invalidate(route) else discard(route)
        }
    }
    private fun saveView(route: StudioRoute, view: View) {
        saved.putSparseParcelableArray(key(route), SparseArray<Parcelable>().also(view::saveHierarchyState))
    }
    fun save(): Bundle {
        views.forEach { (route, view) -> saveView(route, view) }
        return Bundle(saved)
    }
    fun clear() { views.clear() }
}
