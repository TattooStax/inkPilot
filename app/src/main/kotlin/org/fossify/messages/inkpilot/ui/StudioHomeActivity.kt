package org.fossify.messages.inkpilot.ui

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Telephony
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.messages.R
import org.fossify.messages.BuildConfig
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.appLaunched
import org.fossify.messages.inkpilot.data.StudioArchiveCleanupNotice
import org.fossify.messages.inkpilot.data.StudioArchiveExporter
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.saveStaffEdit
import org.fossify.messages.inkpilot.studio.StudioPanels
import java.util.concurrent.Executors

private const val THREAD_REVIEW_FOOTER_HEIGHT_DP = 280

/** A small native host; panels own their UI and the repository owns all studio data. */
class StudioHomeActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var repository: StudioRepository
    private lateinit var work: StudioWork
    private lateinit var canvas: FrameLayout
    private lateinit var footer: LinearLayout
    private lateinit var heading: TextView
    private lateinit var agent: AgentPanel
    private lateinit var studio: StudioPanels
    private lateinit var navigator: StudioNavigator
    private lateinit var panels: StudioCanvasState
    private lateinit var contactEdit: ContactEditState
    private val tab get() = navigator.selectedTab
    private var smsObserver: SmsRefreshObserver? = null
    internal val composerRoutes by lazy { ComposerRouteCoordinator(this, navigator, panels, ::render) }
    private var attachmentThread = 0L
    private var cleanupDialog: AlertDialog? = null
    private var closing = false
    private val appLock by lazy { StudioAppLock(this) }
    private val studioArchiveDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        StudioArchiveHandoff.handle(uri, appLockManager.isLocked(),
            discard = { selected ->
                StudioArchiveCleanupNotice(this).recordIfDeletionFails {
                    DocumentsContract.deleteDocument(contentResolver, selected)
                }
            },
            start = { selected ->
                work.load({ StudioArchiveExporter(applicationContext, repository).export(selected) {
                    !appLockManager.isLocked()
                } }, failed = { showExportCleanupWarning() }) { summary ->
                    StudioUi.error(this,
                        "Studio archive saved: ${summary.tables} tables and ${summary.originals} originals.")
                }
            })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        attachmentThread = savedInstanceState?.getLong("attachment.thread") ?: 0L
        navigator = StudioNavigator(savedInstanceState)
        panels = StudioCanvasState(savedInstanceState?.getBundle("studio.panels") ?: Bundle())
        contactEdit = ContactEditState(savedInstanceState?.getBundle("contact.edit"))
        if (savedInstanceState == null) {
            intent.getBundleExtra(ExternalRouteParser.ROUTE)?.decodeRoute()?.let { navigator.acceptExternalRoute(it) }
                ?: intent.getStringExtra("inkpilot_panel")?.let { navigator.acceptPanelHint(it, panels) }
        }
        repository = StudioRepository(applicationContext)
        work = StudioWork(this, executor)
        agent = AgentPanel(this, work, ::refreshPanels, savedInstanceState?.getString("soul_draft"))
        studio = StudioPanels(this, executor, { StudioUi.error(this, it) }, repository)
        buildFrame()
        appLaunched(BuildConfig.APPLICATION_ID)
        smsObserver = SmsRefreshObserver(this, composerRoutes::refreshFromProvider)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigator.pop()) { render() }
                else { appLockManager.lock(); finish() }
            }
        })
    }

    private fun buildFrame() {
        val root = LinearLayout(this).apply {
            id = R.id.inkpilot_home
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.paper)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(StudioUi.button(this, "☰") { showStudioDrawer(this, ::openAgent) }.apply {
            id = R.id.inkpilot_menu
            contentDescription = "Open agent controls"
        }, LinearLayout.LayoutParams(StudioUi.dp(root, 64), StudioUi.dp(root, 56)))
        heading = StudioUi.text(this, "Messages", 24f, true).apply { id = R.id.inkpilot_title }
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        root.addView(StudioUi.rule(this))
        canvas = FrameLayout(this).apply { id = R.id.inkpilot_canvas }
        root.addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(StudioUi.rule(this))
        footer = LinearLayout(this)
        root.addView(footer)
        setContentView(root)
    }

    private fun render() {
        if (isDestroyed || isFinishing) return
        if (appLockManager.isLocked()) { canvas.removeAllViews(); return }
        composerRoutes.prune()
        val route = navigator.current
        val messages = MessagePanel(this, work, ::setupSms, { openAgent("drafts") }, ::openRoute,
            threadFooter = { threadFooter(it) }, importImage = ::importMmsImage)
        val auxiliary = AuxiliaryPanel(this, work, ::openRoute, ::refreshPanels, ::exportStudio)
        val view = panels.view(route) {
            supplementalCanvas(this, route, messages, work, repository) { onBackPressedDispatcher.onBackPressed() }
                ?: when (route) {
                    is StudioRoute.Thread -> messages.thread(route.threadId)
                    is StudioRoute.Compose -> StudioUi.scroll(this, ThreadComposer(this, work, ::pickAttachment,
                        ::refreshPanels, { sent -> composerRoutes.completeCompose(route, sent) },
                        composerRoutes::acceptedThread).view(route.composerId, route))
                    is StudioRoute.Draft -> inlineDraft(route.draftId)
                    else -> auxiliaryCanvas(this, route, repository, work, auxiliary, studio, agent,
                        ::refreshPanels, ::openRoute)
                        ?: dailyCanvas(this, tab, messages, repository, work, studio, contactEdit, ::refreshPanels,
                            ::openRoute, { number ->
                                navigator.open(StudioRoute.Tab(StudioTab.PHONE))
                                openRoute(StudioRoute.PhoneDial(number))
                            })
                }
        }
        heading.text = routeTitle(route, tab)
        if (canvas.getChildAt(0) !== view) {
            canvas.removeAllViews()
            canvas.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val highlightedTab = tab.takeUnless { route.isSideRoute() }
        if (footer.tag != highlightedTab || footer.childCount == 0) {
            footer.removeAllViews()
            footer.addView(navigation(this, highlightedTab) { chosen -> navigator.selectDailyTab(chosen); render() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            footer.tag = highlightedTab
        }
    }

    private fun importMmsImage(key: org.fossify.messages.messaging.MessageKey, threadId: Long,
                               attachment: org.fossify.messages.models.Attachment) {
        work.load(repository::contacts) { contacts ->
            if (contacts.isEmpty()) { StudioUi.error(this, "Add a client before saving reference images."); return@load }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Save reference for client")
                .setItems(contacts.map { it.displayName.ifBlank { it.number } }.toTypedArray()) { _, index ->
                    val contactId = contacts[index].id
                    studio.importMmsOriginal(attachment.getUri(),
                        org.fossify.messages.inkpilot.studio.StudioImageLink(contactId, key, threadId)) {
                        val affected = listOf(StudioRoute.ImageLinks(contactId), StudioRoute.ImageLinks(threadId = threadId),
                            StudioRoute.ImageLinks(contactId, threadId))
                        affected.forEach(panels::invalidate)
                        if (navigator.current == StudioRoute.Thread(threadId)) openRoute(StudioRoute.ImageLinks(contactId, threadId))
                        else if (navigator.current in affected) render()
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    internal fun openRoute(route: StudioRoute) { navigator.open(route); render() }
    internal fun openRouteIfCurrent(expected: StudioRoute, destination: StudioRoute) {
        if (navigator.current == expected) openRoute(destination)
    }
    private fun openAgent(value: String) {
        navigator.open(when (value) {
            "soul" -> StudioRoute.Soul
            "model" -> StudioRoute.Model
            "settings" -> StudioRoute.Settings("messaging")
            else -> StudioRoute.Auxiliary(value)
        })
        render()
    }

    private fun exportStudio() {
        if (!appLockManager.isLocked()) studioArchiveDocument.launch("InkPilot-Studio.zip")
    }

    private fun showExportCleanupWarning() {
        if (closing || isFinishing || isDestroyed) return
        if (appLockManager.isLocked()) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (cleanupDialog?.isShowing == true) return
        val notice = StudioArchiveCleanupNotice(this)
        val generation = notice.pendingGenerationIfUnlocked(true) ?: return
        val dialog = AlertDialog.Builder(this)
            .setTitle("Studio export needs cleanup")
            .setMessage("An incomplete plaintext Studio export may remain in the location you chose. " +
                "Remove the incomplete file before exporting again.")
            .setPositiveButton("Got it") { _, _ ->
                notice.acknowledgeIfUnlocked(!appLockManager.isLocked(), generation)
            }.setCancelable(false).create()
        cleanupDialog = dialog
        dialog.setOnDismissListener {
            cleanupDialog = null
            if (closing || isFinishing || isDestroyed) return@setOnDismissListener
            if (appLockManager.isLocked()) return@setOnDismissListener
            if (notice.pendingGenerationIfUnlocked(true)?.let { it != generation } == true) {
                showExportCleanupWarning()
            }
        }
        dialog.show()
    }

    private fun pickAttachment(threadId: Long) {
        attachmentThread = threadId
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 7310)
    }
    private fun threadFooter(threadId: Long, selectedDraftId: String? = null): android.view.View {
        val root = StudioUi.column(this, 0)
        val scroll = StudioUi.scroll(this, root).apply {
            layoutParams = LinearLayout.LayoutParams(-1, StudioUi.dp(root, THREAD_REVIEW_FOOTER_HEIGHT_DP))
        }
        if (selectedDraftId == null) {
            work.load({ repository.draftsForThread(threadId) }) { drafts ->
                drafts.forEachIndexed { index, draft ->
                    val review = InlineDraftReview(this, work, ::refreshPanels, repository::saveStaffEdit)
                    root.addView(review.view(draft), index)
                }
            }
            root.addView(ThreadComposer(this, work, ::pickAttachment, ::refreshPanels, ::openRoute,
                composerRoutes::acceptedThread).view(threadId))
        } else {
            root.isFocusableInTouchMode = true
            root.requestFocus()
            root.addView(StudioUi.text(this, "Loading held reply…"))
            work.load({ repository.draftsForThread(threadId) }) { drafts ->
                root.removeAllViews()
                val selected = drafts.firstOrNull { it.draftId == selectedDraftId }
                root.addView(if (selected == null) StudioUi.text(this, "Held draft is no longer available.")
                    else InlineDraftReview(this, work, ::refreshPanels, repository::saveStaffEdit).view(selected))
                scroll.post { scroll.scrollTo(0, 0) }
            }
        }
        return scroll
    }
    private fun inlineDraft(id: String): android.view.View {
        val root = StudioUi.column(this, 0)
        work.load({ repository.draft(id) }) { draft ->
            if (draft != null) {
                val panel = MessagePanel(this, work, ::setupSms, { openAgent("drafts") },
                    ::openRoute, { threadFooter(it, id) })
                root.attachDraftThread(panel.thread(draft.threadId, focusedReview = true))
            } else root.addView(StudioUi.text(this, "Draft is no longer available"))
        }
        return root
    }

    private fun setupSms() {
        if (!org.fossify.messages.helpers.SmsRole.isHeld(this)) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val role = getSystemService(RoleManager::class.java)
                if (!role.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    StudioUi.error(this, "This device does not offer the SMS app role.")
                    return
                }
                role.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            startActivityForResult(intent, SMS_ROLE_REQUEST)
        } else SmsRolePermissions.request(this, SMS_ROLE_REQUEST, ::refreshPanels)
    }

    override fun onResume() {
        super.onResume()
        if (appLock.onResume()) {
            refreshPanels()
            smsObserver?.start()
            window.decorView.post { showExportCleanupWarning() }
        }
        else { canvas.removeAllViews(); smsObserver?.stop() }
    }
    override fun onPause() { smsObserver?.stop(); super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getBundleExtra(ExternalRouteParser.ROUTE)?.decodeRoute()?.let { route ->
            navigator.acceptExternalRoute(route)
            render()
        }
            ?: intent.getStringExtra("inkpilot_panel")?.let { hint ->
                navigator.acceptPanelHint(hint, panels)
                render()
            }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("attachment.thread", attachmentThread)
        navigator.save(outState)
        outState.putBundle("studio.panels", panels.save())
        contactEdit.save()?.let { outState.putBundle("contact.edit", it) }
        agent.soulDraft?.let { outState.putString("soul_draft", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7310) {
            // A picker return must not attach private content while the shell is locked.
            if (resultCode != RESULT_OK || data == null || appLockManager.isLocked()) return
            val uri = data.data ?: data.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri != null) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: Exception) { /* The preflight checks whether the URI remains readable. */ }
                val store = org.fossify.messages.messaging.ComposerStore(this)
                store.load(attachmentThread)?.let { store.save(it.copy(attachmentUris = listOf(uri))) }
                refreshPanels()
            }
        } else if (requestCode == SMS_ROLE_REQUEST) SmsRolePermissions.request(this, SMS_ROLE_REQUEST, ::refreshPanels)
        else if (!agent.onActivityResult(requestCode, resultCode, data)) studio.onActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPanels()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !appLockManager.isLocked()) smsObserver?.start()
    }
    private fun refreshPanels() {
        // External activities may change provider/repository data. Preserve hierarchy while reloading.
        panels.save()
        panels.clear()
        render()
    }
    override fun onDestroy() {
        closing = true
        cleanupDialog?.dismiss()
        smsObserver?.stop()
        executor.execute { repository.close() }
        executor.shutdown()
        super.onDestroy()
    }
    companion object { private const val SMS_ROLE_REQUEST = 7300 }
}

/** Fill the shell canvas so the thread viewport cannot shrink around its own footer. */
internal fun LinearLayout.attachDraftThread(thread: android.view.View) {
    addView(thread, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
}

private fun StudioNavigator.acceptPanelHint(value: String, panels: StudioCanvasState) {
    when (value) {
        "drafts" -> {
            open(StudioRoute.Tab(StudioTab.SMS))
            open(StudioRoute.Auxiliary("drafts"))
        }
        "calendar" -> {
            reset(StudioTab.CALENDAR)
            panels.invalidate(StudioRoute.Tab(StudioTab.CALENDAR))
        }
    }
}
