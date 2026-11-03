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
    private var tab = StudioTab.SMS
    private var screen = "tab"
    private var smsObserver: SmsRefreshObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        tab = StudioTab.entries.find { it.name == savedInstanceState?.getString("tab") } ?: StudioTab.SMS
        screen = savedInstanceState?.getString("screen")
            ?: if (intent.getStringExtra("inkpilot_panel") == "drafts") "drafts" else "tab"
        repository = StudioRepository(applicationContext)
        work = StudioWork(this, executor)
        agent = AgentPanel(this, work, ::render)
        studio = StudioPanels(this, executor, { StudioUi.error(this, it) }, repository)
        buildFrame()
        smsObserver = SmsRefreshObserver(this) { if (screen == "tab" && tab == StudioTab.SMS) render() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen != "tab") { screen = "tab"; render() } else finish()
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
        canvas.removeAllViews()
        val messages = MessagePanel(this, work, ::setupSms) { openAgent("drafts") }
        val view = when (screen) {
            "drafts" -> DraftPanel(this, repository, work, ::render).view()
            "soul" -> agent.soul()
            "model" -> agent.model()
            else -> when (tab) {
                StudioTab.SMS -> messages.messages()
                StudioTab.PHONE -> messages.phone()
                StudioTab.CONTACTS -> ContactPanel(this, repository, work, ::render).view()
                StudioTab.STUDIO -> studio.albums()
                StudioTab.CALENDAR -> studio.calendar()
            }
        }
        heading.text = if (screen == "tab") tab.label else "Assistant"
        canvas.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        footer.removeAllViews()
        footer.addView(navigation(this, tab) { chosen -> tab = chosen; screen = "tab"; render() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun openAgent(value: String) { screen = value; render() }

    private fun setupSms() {
        if (Telephony.Sms.getDefaultSmsPackage(this) != packageName) {
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
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), SMS_ROLE_REQUEST) else render()
    }

    override fun onResume() { super.onResume(); render(); smsObserver?.start() }
    override fun onPause() { smsObserver?.stop(); super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("inkpilot_panel") == "drafts") openAgent("drafts")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", tab.name); outState.putString("screen", screen)
        super.onSaveInstanceState(outState)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SMS_ROLE_REQUEST) requestSmsPermissions()
        else if (!agent.onActivityResult(requestCode, resultCode, data)) studio.onActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
