package app.dot.mail.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotTextField
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.design.components.ListRow
import app.dot.design.components.SectionLabel
import app.dot.design.components.dotSurfaceEnter
import app.dot.mail.LocalMailGraph
import app.dot.mail.R
import app.dot.mail.data.CategorySettings
import app.dot.mail.data.model.viewRole
import app.dot.mail.engine.text.Category
import app.dot.mail.engine.text.Field
import app.dot.mail.engine.text.Op
import app.dot.mail.engine.text.Rule
import app.dot.mail.ui.common.Format
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.regex.Pattern

/** Categories that have their own menu view (everything but Personal). */
internal val viewCategories = listOf(Category.NEWSLETTER, Category.PURCHASE, Category.BILL, Category.NOTIFICATION, Category.SOCIAL)

/** Rule fields that can be evaluated on cached mail (other headers are not stored). */
private val ruleFields = listOf(Field.FROM_ADDRESS, Field.FROM_DOMAIN, Field.SUBJECT, Field.LIST_ID)

@Composable
private fun categoryLabel(c: Category): String = when (c) {
    Category.NEWSLETTER -> stringResource(R.string.inbox_role_newsletters)
    Category.PURCHASE -> stringResource(R.string.inbox_role_purchases)
    Category.BILL -> stringResource(R.string.inbox_role_bills)
    Category.NOTIFICATION -> stringResource(R.string.inbox_role_notifications)
    Category.SOCIAL -> stringResource(R.string.inbox_role_social)
    Category.PERSONAL -> stringResource(R.string.categories_personal)
}

@Composable
private fun categorySub(c: Category): String = stringResource(
    when (c) {
        Category.NEWSLETTER -> R.string.categories_sub_newsletter
        Category.PURCHASE -> R.string.categories_sub_purchase
        Category.BILL -> R.string.categories_sub_bill
        Category.NOTIFICATION -> R.string.categories_sub_notification
        Category.SOCIAL -> R.string.categories_sub_social
        Category.PERSONAL -> R.string.categories_personal
    },
)

@Composable
private fun fieldLabel(f: Field): String = stringResource(
    when (f) {
        Field.FROM_ADDRESS -> R.string.categories_field_from_address
        Field.FROM_DOMAIN -> R.string.categories_field_from_domain
        Field.SUBJECT -> R.string.categories_field_subject
        else -> R.string.categories_field_list_id
    },
)

@Composable
private fun opLabel(o: Op): String = stringResource(
    when (o) {
        Op.EQUALS -> R.string.categories_op_equals
        Op.CONTAINS -> R.string.categories_op_contains
        Op.ENDS_WITH -> R.string.categories_op_ends_with
        Op.REGEX -> R.string.categories_op_regex
    },
)

/** Which category views are on, and the user's own rules. Never hides anything from the Inbox. */
@Composable
internal fun CategoriesPage(onBack: () -> Unit) {
    val c = Dot.colors
    val context = LocalContext.current.applicationContext
    val store = remember { CategorySettings.get(context) }
    val enabled by store.enabled.collectAsStateWithLifecycle()
    val rules by store.rules.collectAsStateWithLifecycle()
    val graph = LocalMailGraph.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Rule?>(null) }
    val scroll = rememberScrollState()

    fun saveRules(next: List<Rule>) {
        store.setRules(next)
        scope.launch { runCatching { graph.mail.reclassifyAll() } }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        DotTopBar(title = stringResource(R.string.categories_title), elevated = scroll.value > 0, navigation = { BackButton(onBack) })
        Column(Modifier.verticalScroll(scroll).navigationBarsPadding()) {
            Text(
                stringResource(R.string.categories_intro), style = Dot.type.callout, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
            SectionLabel(stringResource(R.string.categories_section_views))
            viewCategories.forEach { cat ->
                val role = cat.viewRole()!!
                ToggleRow(categoryLabel(cat), categorySub(cat), Format.roleIcon(role), cat in enabled) { on -> store.setEnabled(cat, on) }
            }
            Hairline(Modifier.padding(vertical = DotSpacing.s))
            SectionLabel(stringResource(R.string.categories_section_rules))
            Text(
                stringResource(R.string.categories_rules_note), style = Dot.type.caption, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )
            if (rules.isEmpty()) {
                Text(
                    stringResource(R.string.categories_no_rules), style = Dot.type.caption, color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
                )
            }
            rules.forEach { rule ->
                val summary = stringResource(R.string.categories_rule_summary, fieldLabel(rule.field), opLabel(rule.op), rule.value, categoryLabel(rule.category))
                ListRow(
                    title = summary,
                    subtitle = if (rule.enabled) null else stringResource(R.string.categories_rule_off),
                    titleColor = if (rule.enabled) c.textPrimary else c.textTertiary,
                    onClick = { editing = rule },
                )
            }
            ListRow(
                stringResource(R.string.categories_add_rule), icon = Icons.Outlined.Add,
                onClick = { editing = Rule(UUID.randomUUID().toString(), Field.FROM_ADDRESS, null, Op.CONTAINS, "", Category.NEWSLETTER, true) },
            )
            Spacer(Modifier.height(DotSpacing.xl))
        }
    }

    editing?.let { draft ->
        val isNew = rules.none { it.id == draft.id }
        RuleDialog(
            initial = draft, isNew = isNew, onDismiss = { editing = null },
            onDelete = { editing = null; saveRules(rules.filter { it.id != draft.id }) },
            onSave = { rule ->
                editing = null
                saveRules(if (isNew) rules + rule else rules.map { if (it.id == rule.id) rule else it })
            },
        )
    }
}

@Composable
private fun RuleDialog(initial: Rule, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Rule) -> Unit) {
    val c = Dot.colors
    var field by remember { mutableStateOf(initial.field) }
    var op by remember { mutableStateOf(initial.op) }
    var value by remember { mutableStateOf(initial.value) }
    var category by remember { mutableStateOf(initial.category) }
    var on by remember { mutableStateOf(initial.enabled) }
    val patternOk = op != Op.REGEX || value.length <= 200 && runCatching { Pattern.compile(value) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dotSurfaceEnter(),
        containerColor = c.surface, shape = DotShapes.large,
        title = { Text(stringResource(if (isNew) R.string.categories_rule_new else R.string.categories_rule_edit), style = Dot.type.title, color = c.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DotSpacing.xs)) {
                ChoiceRow(stringResource(R.string.categories_rule_field), null, field, ruleFields.map { it to fieldLabel(it) }) { field = it }
                ChoiceRow(stringResource(R.string.categories_rule_op), null, op, Op.entries.map { it to opLabel(it) }) { op = it }
                DotTextField(
                    value, { value = it }, label = stringResource(R.string.categories_rule_value),
                    isError = !patternOk, supporting = if (patternOk) null else stringResource(R.string.categories_rule_invalid),
                )
                ChoiceRow(stringResource(R.string.categories_rule_category), null, category, Category.entries.map { it to categoryLabel(it) }) { category = it }
                ToggleRow(stringResource(R.string.categories_rule_enabled), null, null, on) { on = it }
            }
        },
        confirmButton = {
            DotButton(
                stringResource(R.string.categories_rule_save), compact = true, enabled = value.isNotBlank() && patternOk,
                onClick = { onSave(Rule(initial.id, field, null, op, value.trim(), category, on)) },
            )
        },
        dismissButton = {
            Column {
                if (!isNew) DotButton(stringResource(R.string.categories_rule_delete), onDelete, style = ButtonStyle.Danger, compact = true)
                DotButton(stringResource(R.string.identity_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true)
            }
        },
    )
}
