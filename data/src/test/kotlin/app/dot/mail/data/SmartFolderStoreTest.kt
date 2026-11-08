package app.dot.mail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.SmartFolder
import app.dot.mail.engine.text.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmartFolderStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `smart folders survive json round trip and skip corrupt entries`(): Unit {
        val a = SmartFolder("a", "Bills", setOf(1), setOf(FolderRole.INBOX, FolderRole.ARCHIVE), "x", "y", "z", Category.BILL, true, true, true, 5, 9)
        val b = SmartFolder("b", "Plain")
        assertThat(SmartFolderStore.decode(SmartFolderStore.encode(listOf(a, b)))).containsExactly(a, b).inOrder()
        assertThat(SmartFolderStore.decode("[{\"id\":\"x\"},{\"id\":\"ok\",\"name\":\"Ok\"}]").map { it.id }).containsExactly("ok")
        assertThat(SmartFolderStore.decode("not json")).isEmpty()
        assertThat(SmartFolderStore.decode(null)).isEmpty()
    }

    @Test fun `store saves, replaces, reorders, deletes and persists`(): Unit {
        val store = SmartFolderStore.forTest(context)
        store.replaceAll(emptyList())
        store.save(SmartFolder("a", "A")); store.save(SmartFolder("b", "B")); store.save(SmartFolder("c", "C"))
        store.save(SmartFolder("b", "B2", from = "x"))
        assertThat(store.folders.value.map { it.name }).containsExactly("A", "B2", "C").inOrder()
        store.move("c", -1)
        store.move("a", -1) // already first: no-op
        assertThat(store.folders.value.map { it.id }).containsExactly("a", "c", "b").inOrder()
        store.forgetAccount(7)
        store.save(SmartFolder("d", "D", accountIds = setOf(7, 8)))
        store.forgetAccount(7)
        assertThat(store.get("d")!!.accountIds).containsExactly(8L)
        store.delete("a")
        assertThat(SmartFolderStore.forTest(context).folders.value.map { it.id }).containsExactly("c", "b", "d").inOrder()
        store.replaceAll(emptyList())
    }
}
