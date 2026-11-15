package org.fossify.messages.inkpilot.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.EditText
import inkpilot.agent.design.DesignSkillSelection

/** Local design workspace, separate from the SMS model and staff approval queue. */
internal class TattooSkillsPanel(private val activity: Activity, state: Bundle?) {
    private var request = state?.getString("design_request").orEmpty()
    private var style = state?.getString("design_style").orEmpty()
    private var result = state?.getString("design_brief").orEmpty()
    private val names = linkedMapOf("tattoo-design-workflow" to "Tattoo Reference Design", "tattoo-sleeve-layout" to "Sleeve Layout", "tattoo-stencil-production" to "Stencil Production", "tattoo-studio-routing" to "Studio Handoffs")
    private fun read(name: String): String {
        require(name in names)
        val body = activity.assets.open("tattoo_skills/$name/SKILL.md").bufferedReader().use { it.readText() }.substringAfter("---").substringAfter("---").trim()
        val reference = mapOf("tattoo-design-workflow" to "artist-style-lessons.md", "tattoo-sleeve-layout" to "sources.md", "tattoo-stencil-production" to "production-sources.md")[name]
        return if (reference == null) body else body + "\n\n" + activity.assets.open("tattoo_skills/$name/references/$reference").bufferedReader().use { it.readText() }
    }
    private fun field(hint: String, initial: String, limit: Int, changed: (String) -> Unit) = EditText(activity).apply {
        this.hint = hint; minLines = 3; setTextColor(StudioUi.ink); setHintTextColor(StudioUi.muted)
        filters = arrayOf(InputFilter.LengthFilter(limit)); setText(initial)
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(value: Editable?) { changed(value.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }
    fun view(): android.view.View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity,"Tattoo design skills",24f,true))
        body.addView(StudioUi.text(activity,"Create a design brief with the matching sleeve and stencil workflows. This prepares instructions; it does not render artwork or certify physical fit.",15f))
        names.forEach { (key,name) -> body.addView(StudioUi.button(activity,name) {
            val text = StudioUi.text(activity,read(key),15f)
            AlertDialog.Builder(activity).setTitle(name).setView(StudioUi.scroll(activity,StudioUi.column(activity).apply { addView(text) })).setPositiveButton("Close",null).show()
        }) }
        body.addView(field("Placement, size, subject and requested changes",request,6000) { request = it })
        body.addView(field("Artist style for this brief (optional)",style,4000) { style = it })
        val output = StudioUi.text(activity,result,15f).apply { setTextIsSelectable(true) }
        body.addView(StudioUi.button(activity,"Prepare design brief") {
            try { result = DesignSkillSelection.brief(request,style,::read); output.text = result }
            catch (error: IllegalArgumentException) { StudioUi.error(activity,error.message ?: "Check the design request.") }
        })
        body.addView(StudioUi.button(activity,"Copy design brief") {
            if (result.isBlank()) StudioUi.error(activity,"Prepare a brief first.")
            else { (activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tattoo design brief",result)); StudioUi.error(activity,"Design brief copied. No message sent.") }
        })
        body.addView(output)
        return StudioUi.scroll(activity,body)
    }
    fun save(state: Bundle) { state.putString("design_request",request);state.putString("design_style",style);state.putString("design_brief",result) }
}
