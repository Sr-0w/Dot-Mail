package app.dot.mail.data

import android.content.Context
import androidx.core.content.edit
import app.dot.mail.engine.text.Category
import app.dot.mail.engine.text.Classifier
import app.dot.mail.engine.text.Rule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which category views the user turned on and their own classification rules.
 *
 * Categories are only *views*: enabling or disabling one never moves or hides mail, and the Inbox always keeps
 * everything. Rules run locally against the sender, subject and List-Id; nothing leaves the device.
 */
class CategorySettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("dotmail_categories", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(readEnabled())
    private val _rules = MutableStateFlow(readRules())

    /** Category views shown in the drawer. Newsletters were always shown, so they stay on by default. */
    val enabled: StateFlow<Set<Category>> = _enabled.asStateFlow()
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    init { ClassificationRules.current = _rules.value }

    fun setEnabled(category: Category, on: Boolean) {
        val next = if (on) _enabled.value + category else _enabled.value - category
        prefs.edit { putString("enabled", next.joinToString(",") { it.name }) }
        _enabled.value = next
    }

    /** Replaces the rules. Call [RealMailGraph.reclassifyAll] (via MailRepository) afterwards to apply them to cached mail. */
    fun setRules(rules: List<Rule>) {
        prefs.edit { putString("rules", Classifier.encodeRules(rules)) }
        _rules.value = rules
        ClassificationRules.current = rules
    }

    private fun readEnabled(): Set<Category> {
        val raw = prefs.getString("enabled", null) ?: return setOf(Category.NEWSLETTER)
        return raw.split(',').mapNotNull { n -> Category.entries.firstOrNull { it.name == n } }.toSet()
    }

    private fun readRules(): List<Rule> =
        prefs.getString("rules", null)?.let { runCatching { Classifier.decodeRules(it) }.getOrNull() }.orEmpty()

    companion object {
        @Volatile private var instance: CategorySettings? = null
        fun get(context: Context): CategorySettings =
            instance ?: synchronized(this) { instance ?: CategorySettings(context.applicationContext).also { instance = it } }

        /** For tests: a store on a private context, bypassing the singleton. */
        internal fun forTest(context: Context) = CategorySettings(context)
    }
}

/** Process-wide copy of the user's rules for the sync path (which has no Context at hand). */
object ClassificationRules {
    @Volatile var current: List<Rule> = emptyList()
}
