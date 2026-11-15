package inkpilot.agent.design

/** Pure brief composition; no customer-message drafting or rendering authority. */
object DesignSkillSelection {
    private val sleeve = Regex("\\b(sleeves?|wraps?|shoulder cap|upper arm|full arm)\\b", RegexOption.IGNORE_CASE)
    private val stencil = Regex("\\b(stencils?|outlines?|thermal|tracing|seams?|borders?)\\b", RegexOption.IGNORE_CASE)
    fun select(request: String): List<String> = buildList {
        add("tattoo-design-workflow")
        if (sleeve.containsMatchIn(request)) add("tattoo-sleeve-layout")
        if (stencil.containsMatchIn(request) || sleeve.containsMatchIn(request)) add("tattoo-stencil-production")
    }
    fun brief(request: String, artistStyle: String, read: (String) -> String): String {
        require(request.isNotBlank() && request.length <= 6000) { "Enter a design request up to 6,000 characters." }
        require(artistStyle.length <= 4000) { "Use artist preferences up to 4,000 characters." }
        return buildString {
            append("DESIGN BRIEF — draft, no artwork rendered\nRequest: ").append(request.trim())
            if (artistStyle.isNotBlank()) append("\nArtist preferences for this brief only: ").append(artistStyle.trim())
            append("\nKeep client reference images in the existing project. Attach actual references to a capable image editor.\n")
            select(request).forEach { append("\n").append(read(it)).append("\n") }
        }
    }
}
