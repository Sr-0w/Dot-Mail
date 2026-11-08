package app.dot.mail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.category
import app.dot.mail.data.model.viewRole
import app.dot.mail.engine.text.Category
import app.dot.mail.engine.text.Field
import app.dot.mail.engine.text.Op
import app.dot.mail.engine.text.Rule
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CategoriesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `every category except personal has a view and maps back`(): Unit {
        Category.entries.filter { it != Category.PERSONAL }.forEach { assertThat(it.viewRole()!!.category()).isEqualTo(it) }
        assertThat(Category.PERSONAL.viewRole()).isNull()
        assertThat(FolderRole.INBOX.category()).isNull()
    }

    @Test fun `newsletters are on by default and choices and rules persist`(): Unit {
        val store = CategorySettings.forTest(context)
        assertThat(store.enabled.value).containsExactly(Category.NEWSLETTER)
        store.setEnabled(Category.PURCHASE, true)
        store.setEnabled(Category.NEWSLETTER, false)
        val rule = Rule("r1", Field.FROM_DOMAIN, null, Op.EQUALS, "shop.example", Category.PURCHASE, true)
        store.setRules(listOf(rule))
        assertThat(ClassificationRules.current).containsExactly(rule)
        val again = CategorySettings.forTest(context)
        assertThat(again.enabled.value).containsExactly(Category.PURCHASE)
        assertThat(again.rules.value).containsExactly(rule)
        store.setRules(emptyList())
    }

    private fun header(category: String, auto: String? = null): String =
        JSONObject().put("category", category).apply { auto?.let { put("autoCategory", it) } }
            .put("from", Codec.addresses(listOf(EmailAddress("promo@shop.example", "Shop"))).let { "[{\"email\":\"promo@shop.example\",\"name\":\"Shop\"}]" })
            .put("listId", "<deals.shop.example>").toString()

    @Test fun `reclassified applies rules, remembers the heuristic result and can revert`(): Unit {
        val rule = Rule("r1", Field.FROM_DOMAIN, null, Op.EQUALS, "shop.example", Category.BILL, true)
        val applied = Codec.reclassified(header("NEWSLETTER"), "Hello", listOf(rule))!!
        assertThat(Codec.field(applied, "category")).isEqualTo("BILL")
        assertThat(Codec.field(applied, "autoCategory")).isEqualTo("NEWSLETTER")
        assertThat(Codec.reclassified(applied, "Hello", listOf(rule))).isNull() // already up to date
        val reverted = Codec.reclassified(applied, "Hello", emptyList())!!
        assertThat(Codec.field(reverted, "category")).isEqualTo("NEWSLETTER")
        val listRule = rule.copy(field = Field.LIST_ID, op = Op.CONTAINS, value = "deals", category = Category.PERSONAL)
        assertThat(Codec.field(Codec.reclassified(header("NEWSLETTER"), "x", listOf(listRule))!!, "category")).isEqualTo("PERSONAL")
    }
}
