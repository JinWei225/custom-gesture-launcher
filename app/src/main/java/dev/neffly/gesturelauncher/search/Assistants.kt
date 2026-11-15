package dev.neffly.gesturelauncher.search

import android.content.Intent
import dev.neffly.gesturelauncher.drawer.AppInfo
import java.text.Normalizer

/**
 * The `ask` command: `ask Claude how tall is K2` opens a new chat in that AI app with the prompt
 * typed into it, not sent. With no app named, every installed assistant gets a row.
 *
 * Which apps are assistants is a list of the ones known, the way the clock fallback lists clock
 * apps: nothing on the device says an app is one. The prompt goes over as shared text, which
 * Gemini opens as a draft in a new chat. Claude first asks where to put it, unless its "Always
 * share to a new session" setting is on.
 *
 * Not Claude's new-chat link (claude.ai/new?q=), though it skips that question: a prompt arriving
 * in a link could have been written by anyone, so Claude warns over every one, including the one
 * the user just typed here.
 */
object Assistants {

    /** Known assistant apps, by package. */
    private val KNOWN = setOf(
        "com.anthropic.claude",
        "com.google.android.apps.bard", // Gemini
        "com.openai.chatgpt",
        "com.microsoft.copilot",
        "ai.perplexity.app.android",
        "com.deepseek.chat",
        "ai.x.grok"
    )

    /** The installed assistants among [apps], by name. */
    fun installed(apps: List<AppInfo>): List<AppInfo> =
        apps.filter { it.packageName in KNOWN }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

    /** The rows for [query], or null when it isn't an ask command. */
    fun parse(query: String, assistants: List<AppInfo>): List<Command.Ask>? {
        val q = Normalizer.normalize(query, Normalizer.Form.NFKC).replace(WHITESPACE, " ").trimStart()
        val rest = KEYWORD.matchEntire(q)?.groupValues?.get(1)?.trim() ?: return null
        assistants.filter { startsWithName(rest, it.label) }.maxByOrNull { it.label.length }?.let {
            return listOf(Command.Ask(prompt(rest.substring(it.label.length)), it))
        }
        return assistants.map { Command.Ask(prompt(rest), it) }
    }

    /**
     * Opens a new chat with the prompt in it. With no prompt there is nothing to carry, so the app
     * simply opens.
     */
    fun intent(ask: Command.Ask): Intent {
        val app = ask.assistant
        if (ask.prompt.isEmpty()) {
            return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.componentName)
        }
        return Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, ask.prompt)
            .setPackage(app.packageName)
    }

    /** "to …" and a leading colon or comma introduce the prompt — "ask Claude to summarise this". */
    private fun prompt(text: String): String = text.trim().replace(LEAD_IN, "").trim()

    private fun startsWithName(text: String, name: String): Boolean =
        name.isNotEmpty() && text.regionMatches(0, name, 0, name.length, ignoreCase = true) &&
            (text.length == name.length || !text[name.length].isLetterOrDigit())

    private val WHITESPACE = Regex("""\s+""")
    private val KEYWORD = Regex("""ask (.*)""", RegexOption.IGNORE_CASE)
    private val LEAD_IN = Regex("""^(?:to\s+|[:,\-]\s*)""", RegexOption.IGNORE_CASE)
}
