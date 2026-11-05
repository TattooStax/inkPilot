package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.messages.R
import org.fossify.messages.BuildConfig
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.appLaunched
import org.fossify.messages.inkpilot.data.StudioArchiveExporter
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.studio.StudioPanels
import java.util.concurrent.Executors

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
    private val tab get() = navigator.selectedTab
    private var smsObserver: SmsRefreshObserver? = null
    private var attachmentThread = 0L
    private val appLock by lazy { StudioAppLock(this) }
    private val studioArchiveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        when (StudioArchiveHandoff.handle(uri, appLockManager.isLocked(),
            discard = { DocumentsContract.deleteDocument(contentResolver, it) },
            start = { selected ->
                work.load({ StudioArchiveExporter(applicationContext, repository).export(selected) {
                    !appLockManager.isLocked()
                } }) { summary ->
                    StudioUi.error(this, "Studio archive saved: ${summary.tables} tables and ${summary.originals} originals.")
                }
            })) {
            StudioArchiveHandoff.Outcome.LOCKED_DISCARDED ->
                StudioUi.error(this, "Unlock inkPilot and choose export again.")
            StudioArchiveHandoff.Outcome.LOCKED_UNDELETED ->
                StudioUi.error(this, "Unlock inkPilot and remove the incomplete document before retrying.")
            else -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        attachmentThread = savedInstanceState?.getLong("attachment.thread") ?: 0L
        navigator = StudioNavigator(savedInstanceState)
        panels = StudioCanvasState(savedInstanceState?.getBundle("studio.panels") ?: Bundle())
        if (savedInstanceState == null) {
            intent.getBundleExtra(ExternalRouteParser.ROUTE)?.decodeRoute()?.let(::acceptExternalRoute)
                ?: if (intent.getStringExtra("inkpilot_panel") == "drafts") navigator.open(StudioRoute.Auxiliary("drafts")) else Unit
        }
        repository = StudioRepository(applicationContext)
        work = StudioWork(this, executor)
        agent = AgentPanel(this, work, ::refreshPanels, savedInstanceState?.getString("soul_draft"))
        studio = StudioPanels(this, executor, { StudioUi.error(this, it) }, repository)
        buildFrame()
        appLaunched(BuildConfig.APPLICATION_ID)
        smsObserver = SmsRefreshObserver(this) {
            panels.invalidate(StudioRoute.Tab(StudioTab.SMS))
            val route = navigator.current
            panels.invalidateThreads()
            if (route is StudioRoute.Thread || route == StudioRoute.Tab(StudioTab.SMS)) render()
        }
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
        val route = navigator.current
        val messages = MessagePanel(this, work, ::setupSms, { openAgent("drafts") }, ::openRoute,
            threadFooter = ::threadFooter, importImage = ::importMmsImage)
        val auxiliary = AuxiliaryPanel(this, work, ::openRoute, ::refreshPanels, ::exportStudio)
        val view = panels.view(route) { when (route) {
            StudioRoute.Auxiliary("drafts") -> DraftPanel(this, repository, work, ::refreshPanels, ::openRoute).view()
            StudioRoute.Auxiliary("proposals") -> ProposalPanel(this, repository, work, ::refreshPanels).view()
            is StudioRoute.Thread -> messages.thread(route.threadId)
            is StudioRoute.Compose -> StudioUi.scroll(this, ThreadComposer(this, work, ::pickAttachment, ::refreshPanels, ::openRoute).view(route.composerId, route))
            is StudioRoute.Draft -> inlineDraft(route.draftId)
            is StudioRoute.ImageLinks -> studio.linkedImages(route.contactId, route.threadId)
            is StudioRoute.Settings -> auxiliary.settings()
            StudioRoute.Auxiliary("archive") -> auxiliary.archive()
            StudioRoute.Auxiliary("recycle") -> auxiliary.recycle()
            StudioRoute.Auxiliary("search") -> auxiliary.search()
            is StudioRoute.Auxiliary -> when {
                route.key.startsWith("details:") -> auxiliary.details(route.key.substringAfter(':').toLongOrNull() ?: 0)
                route.key.startsWith("recycle:") -> auxiliary.recycledThread(route.key.substringAfter(':').toLongOrNull() ?: 0)
                else -> auxiliary.unsupported()
            }
            StudioRoute.Soul -> agent.soul()
            StudioRoute.Model -> agent.model()
            else -> when (tab) {
                StudioTab.SMS -> messages.messages()
                StudioTab.PHONE -> messages.phone()
                StudioTab.CONTACTS -> ContactPanel(this, repository, work, ::refreshPanels) { contactId ->
                    navigator.open(StudioRoute.ImageLinks(contactId = contactId)); render()
                }.view()
                StudioTab.STUDIO -> studio.albums()
                StudioTab.CALENDAR -> studio.calendar()
            }
        }
        }
        heading.text = when (route) {
            is StudioRoute.Tab -> tab.label
            is StudioRoute.Thread -> "Conversation"
            is StudioRoute.Compose -> "New message"
            is StudioRoute.ImageLinks -> "Client References"
            is StudioRoute.Settings -> "Settings"
            is StudioRoute.Auxiliary -> route.key.substringBefore(':').replaceFirstChar { it.uppercase() }
            else -> "Assistant"
        }
        if (canvas.getChildAt(0) !== view) {
            canvas.removeAllViews()
            canvas.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (footer.tag != tab) {
            footer.removeAllViews()
            footer.addView(navigation(this, tab) { chosen -> navigator.open(StudioRoute.Tab(chosen)); render() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            footer.tag = tab
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

    private fun acceptExternalRoute(route: StudioRoute) {
        if (route is StudioRoute.Tab) { navigator.reset(route.tab); return }
        if (route is StudioRoute.Thread || route is StudioRoute.Compose || route is StudioRoute.Draft ||
            route is StudioRoute.Settings || route is StudioRoute.Auxiliary) navigator.open(StudioRoute.Tab(StudioTab.SMS))
        navigator.open(route)
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

    private fun pickAttachment(threadId: Long) {
        attachmentThread = threadId
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 7310)
    }
    private fun threadFooter(threadId: Long): android.view.View {
        val root = StudioUi.column(this, 0)
        work.load({ repository.draftsForThread(threadId) }) { drafts ->
            drafts.forEach { root.addView(InlineDraftReview(this, work, ::refreshPanels).view(it), 0) }
        }
        root.addView(ThreadComposer(this, work, ::pickAttachment, ::refreshPanels, ::openRoute).view(threadId))
        return StudioUi.scroll(this, root).apply { layoutParams = LinearLayout.LayoutParams(-1, StudioUi.dp(root, 280)) }
    }
    private fun inlineDraft(id: String): android.view.View {
        val root = StudioUi.column(this, 0)
        work.load({ repository.draft(id) }) { draft ->
            if (draft != null) root.addView(MessagePanel(this, work, ::setupSms, { openAgent("drafts") }, ::openRoute, ::threadFooter).thread(draft.threadId))
            else root.addView(StudioUi.text(this, "Draft is no longer available"))
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
        } else requestSmsPermissions()
    }

    private fun requestSmsPermissions() {
        val permissions = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), SMS_ROLE_REQUEST) else refreshPanels()
    }

    override fun onResume() {
        super.onResume()
        if (appLock.onResume()) { refreshPanels(); smsObserver?.start() }
        else { canvas.removeAllViews(); smsObserver?.stop() }
    }
    override fun onPause() { smsObserver?.stop(); super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getBundleExtra(ExternalRouteParser.ROUTE)?.decodeRoute()?.let { acceptExternalRoute(it); render() }
            ?: if (intent.getStringExtra("inkpilot_panel") == "drafts") openAgent("drafts") else Unit
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("attachment.thread", attachmentThread)
        navigator.save(outState)
        outState.putBundle("studio.panels", panels.save())
        agent.soulDraft?.let { outState.putString("soul_draft", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7310 && resultCode == RESULT_OK && data != null) {
            val uris = buildList {
                data.data?.let { add(it) }
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
            }.distinct()
            uris.forEach { uri -> try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { } }
            val store = org.fossify.messages.messaging.ComposerStore(this)
            store.load(attachmentThread)?.let { store.save(it.copy(attachmentUris = (it.attachmentUris + uris).distinct())) }
            refreshPanels()
        } else if (requestCode == SMS_ROLE_REQUEST) requestSmsPermissions()
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
        smsObserver?.stop()
        executor.execute { repository.close() }
        executor.shutdown()
        super.onDestroy()
    }
    companion object { private const val SMS_ROLE_REQUEST = 7300 }
}
