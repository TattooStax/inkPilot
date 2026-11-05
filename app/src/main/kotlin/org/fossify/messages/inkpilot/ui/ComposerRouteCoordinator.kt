package org.fossify.messages.inkpilot.ui

import androidx.appcompat.app.AppCompatActivity
import org.fossify.messages.messaging.ComposerStore
import org.fossify.messages.messaging.hasCompletedCompose

/** Removes completed drafts from navigation and hierarchy state before any render. */
internal class ComposerRouteCoordinator(
    private val activity: AppCompatActivity, private val navigator: StudioNavigator,
    private val panels: StudioCanvasState, private val render: () -> Unit,
) {
    fun prune(composer: ComposerStore = ComposerStore(activity)) {
        navigator.threadRoutes().filter { composer.load(it.threadId) == null }.forEach(panels::discard)
        navigator.composeRoutes().filter { composer.hasCompletedCompose(it.composerId) }.forEach { completed ->
            panels.discard(completed)
            navigator.forget(completed)
        }
    }

    fun refreshFromProvider() {
        val composer = ComposerStore(activity)
        val prior = navigator.current
        panels.invalidate(StudioRoute.Tab(StudioTab.SMS))
        panels.invalidateThreads { composer.load(it) != null }
        prune(composer)
        if (prior != navigator.current) { render(); return }
        when (val route = navigator.current) {
            is StudioRoute.Compose -> {
                if (composer.isUnknown(route.composerId)) {
                    panels.invalidate(route)
                    render()
                }
            }
            is StudioRoute.Thread, StudioRoute.Tab(StudioTab.SMS) -> render()
            else -> Unit
        }
    }

    fun acceptedThread(threadId: Long) {
        val route = StudioRoute.Thread(threadId)
        panels.discard(route)
        if (navigator.current == route) render()
    }

    fun completeCompose(source: StudioRoute.Compose, target: StudioRoute) {
        val visible = navigator.current == source
        panels.discard(source)
        navigator.forget(source)
        if (visible) { navigator.open(target); render() }
    }
}
