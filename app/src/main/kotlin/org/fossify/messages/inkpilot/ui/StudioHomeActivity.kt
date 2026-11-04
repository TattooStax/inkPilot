package org.fossify.messages.inkpilot.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.messages.R
import org.fossify.messages.BuildConfig
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.appLaunched
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
    private val appLock by lazy { StudioAppLock(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        navigator = StudioNavigator(savedInstanceState)
        panels = StudioCanvasState(savedInstanceState?.getBundle("studio.panels") ?: Bundle())
        if (savedInstanceState == null && intent.getStringExtra("inkpilot_panel") == "drafts") {
            navigator.open(StudioRoute.Auxiliary("drafts"))
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
        val messages = MessagePanel(this, work, ::setupSms, { openAgent("drafts") }, ::openRoute)
        val view = panels.view(route) { when (route) {
            StudioRoute.Auxiliary("drafts") -> DraftPanel(this, repository, work, ::refreshPanels).view()
            is StudioRoute.Thread -> messages.thread(route.threadId)
            is StudioRoute.Compose -> StudioUi.text(this, "New messages will be available in the composer update.")
            StudioRoute.Soul -> agent.soul()
            StudioRoute.Model -> agent.model()
            else -> when (tab) {
                StudioTab.SMS -> messages.messages()
                StudioTab.PHONE -> messages.phone()
                StudioTab.CONTACTS -> ContactPanel(this, repository, work, ::refreshPanels).view()
                StudioTab.STUDIO -> studio.albums()
                StudioTab.CALENDAR -> studio.calendar()
            }
        }
        }
        heading.text = when (route) { is StudioRoute.Tab -> tab.label; is StudioRoute.Thread -> "Conversation"; is StudioRoute.Compose -> "New message"; else -> "Assistant" }
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

    internal fun openRoute(route: StudioRoute) { navigator.open(route); render() }

    private fun openAgent(value: String) {
        navigator.open(when (value) {
            "soul" -> StudioRoute.Soul
            "model" -> StudioRoute.Model
            else -> StudioRoute.Auxiliary(value)
        })
        render()
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
        if (intent.getStringExtra("inkpilot_panel") == "drafts") openAgent("drafts")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        navigator.save(outState)
        outState.putBundle("studio.panels", panels.save())
        agent.soulDraft?.let { outState.putString("soul_draft", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SMS_ROLE_REQUEST) requestSmsPermissions()
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
