package org.fossify.messages.inkpilot.ui

import android.os.Bundle

/** Each tab owns its back stack, including unsent typed compose state. */
internal class StudioNavigator(savedState: Bundle? = null) {
    var selectedTab = StudioTab.entries.find { it.name == savedState?.getString("studio.tab") } ?: StudioTab.SMS
        private set
    private val stacks = StudioTab.entries.associateWith { tab ->
        val saved = savedState?.getBundle("studio.stack.${tab.name}")
        val routes = (0 until (saved?.getInt("size") ?: 0)).mapNotNull { saved?.getBundle("$it")?.decodeRoute() }
        (if (routes.firstOrNull() == StudioRoute.Tab(tab)) routes else listOf(StudioRoute.Tab(tab))).toMutableList()
    }
    val current: StudioRoute get() = stacks.getValue(selectedTab).last()
    fun open(route: StudioRoute) {
        if (route is StudioRoute.Tab) selectedTab = route.tab
        else if (current != route) stacks.getValue(selectedTab).add(route)
    }
    fun reset(tab: StudioTab) {
        selectedTab = tab
        stacks.getValue(tab).apply { clear(); add(StudioRoute.Tab(tab)) }
    }
    fun pop(): Boolean {
        val stack = stacks.getValue(selectedTab)
        if (stack.size == 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
    fun save(outState: Bundle) {
        outState.putString("studio.tab", selectedTab.name)
        stacks.forEach { (tab, routes) -> outState.putBundle("studio.stack.${tab.name}", Bundle().apply {
            putInt("size", routes.size)
            routes.forEachIndexed { index, route -> putBundle("$index", route.encode()) }
        }) }
    }
}
