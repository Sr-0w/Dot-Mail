package app.dot.mail.ui.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.*
import app.dot.mail.data.model.*
import app.dot.mail.demo.DemoMailGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val fixture = StorageGraph()

    private fun content(restoration: StateRestorationTester? = null) {
        val body: @androidx.compose.runtime.Composable () -> Unit = {
            CompositionLocalProvider(LocalMailGraph provides fixture) {
                DotTheme(ThemeMode.LIGHT) { StoragePage(onBack = {}) }
            }
        }
        if (restoration == null) compose.setContent(body) else restoration.setContent(body)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Keep locally").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun edit(label: String, value: String) {
        val node = compose.onNodeWithContentDescription(label)
        node.performScrollTo().performTextReplacement(value)
    }

    @Test
    fun inputRejectsOverflowAndInvalidRanges() {
        val p = StoragePolicy()
        assertThat(storagePolicyInput(p, "1", "30", "0")!!.maxBytes).isEqualTo(0)
        assertThat(storagePolicyInput(p, "500", "90", "1024")!!.maxBytes).isEqualTo(1024 * MIB)
        for (values in
            listOf(
                listOf("0", "30", "1"),
                listOf("1000001", "30", "1"),
                listOf("1", "36501", "1"),
                listOf("1", "30", "-1"),
                listOf("1", "30", "9223372036854775807"),
            )) {
            assertThat(storagePolicyInput(p, values[0], values[1], values[2])).isNull()
        }
    }

    @Test
    fun editedPolicySurvivesRestorationAndAppliesToTheSelectedAccount() {
        val restoration = StateRestorationTester(compose)
        content(restoration)
        edit("Number of days", "90")
        edit("Account cache limit (MiB)", "1024")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Number of days").assertTextEquals("90")
        compose.onNodeWithContentDescription("Account cache limit (MiB)").assertTextEquals("1024")
        compose.onNodeWithText("Apply policy").performScrollTo().performClick()
        compose.waitUntil(5000) { fixture.policies.value[1L]?.days == 90 }
        assertThat(fixture.policies.value[1L]!!.maxBytes).isEqualTo(1024 * MIB)
        assertThat(fixture.policies.value[2L]).isNull()
    }

    @Test
    fun clearNeedsConfirmationAndPausesOnlyThisAccount() {
        content()
        compose.onNodeWithText("Clear downloaded mail").performScrollTo().performClick()
        assertThat(fixture.cleared.value).isEmpty()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(fixture.cleared.value).isEmpty()
        compose.onNodeWithText("Clear downloaded mail").performClick()
        compose
            .onNode(
                hasText("Clear downloaded mail") and hasClickAction() and hasAnyAncestor(isDialog())
            )
            .performClick()
        compose.waitUntil(5000) { fixture.cleared.value.isNotEmpty() }
        assertThat(fixture.cleared.value).containsExactly(1L)
        assertThat(fixture.progress.value[1L]?.state).isEqualTo(OfflineState.PAUSED)
    }

    @Test
    fun fullOfflineShortcutIsReviewableAndDoesNotStartUntilApply() {
        content()
        compose.onNodeWithText("Use unlimited full offline").performScrollTo().performClick()
        assertThat(fixture.policies.value).isEmpty()
        compose.onNodeWithContentDescription("Account cache limit (MiB)").assertTextEquals("0")
        compose.onNode(hasText("Download bodies automatically") and isToggleable()).assertIsOn()
        compose.onNodeWithText("Apply policy").performScrollTo().performClick()
        compose.waitUntil(5000) { fixture.policies.value[1L]?.retention == RetentionMode.ALL }
        assertThat(fixture.policies.value[1L]!!.maxBytes).isEqualTo(0)
        assertThat(fixture.policies.value[1L]!!.downloadBodies).isTrue()
    }

    @Test
    fun switchingAccountsTargetsOnlyTheChosenPolicy() {
        content()
        compose.onNodeWithText("Account").performScrollTo().performClick()
        compose.onNodeWithText("Robin (work)").performClick()
        edit("Number of days", "365")
        compose.onNodeWithText("Apply policy").performScrollTo().performClick()
        compose.waitUntil(5000) { fixture.policies.value[2L]?.days == 365 }
        assertThat(fixture.policies.value[1L]).isNull()
    }

    @Test
    fun pauseAndResumeReflectRepositoryProgress() {
        content()
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.waitUntil(5000) { fixture.progress.value[1L]?.state == OfflineState.PAUSED }
        compose.onNodeWithText("Resume").performScrollTo().performClick()
        compose.waitUntil(5000) { fixture.progress.value[1L]?.state == OfflineState.QUEUED }
    }

    @Test
    fun attachmentsImplyBodiesAndClearNumbersDisableApply() {
        content()
        val toggle =
            compose.onNode(hasText("Download attachments automatically") and isToggleable())
        toggle.performScrollTo().performClick()
        compose.onNode(hasText("Download bodies automatically") and isToggleable()).assertIsOn()
        edit("Account cache limit (MiB)", "-1")
        compose.onNodeWithText("Apply policy").performScrollTo().assertIsNotEnabled()
    }
}

private class StorageGraph : MailGraph {
    private val demo = DemoMailGraph()
    override val accounts = demo.accounts
    override val compose = demo.compose
    val policies = MutableStateFlow<Map<Long, StoragePolicy>>(emptyMap())
    val progress = MutableStateFlow<Map<Long, OfflineProgress>>(emptyMap())
    val cleared = MutableStateFlow<List<Long>>(emptyList())
    override val mail =
        object : MailRepository by demo.mail {
            override val globalStorageLimit = MutableStateFlow(0L)

            override suspend fun setGlobalStorageLimit(bytes: Long) {
                globalStorageLimit.value = bytes
            }

            override fun storagePolicy(accountId: Long) =
                policies.map { it[accountId] ?: StoragePolicy() }

            override fun offlineProgress(accountId: Long) =
                progress.map { it[accountId] ?: OfflineProgress(accountId) }

            override suspend fun setStoragePolicy(accountId: Long, policy: StoragePolicy) {
                policies.update { it + (accountId to policy) }
            }

            override suspend fun pauseOfflineDownload(accountId: Long) {
                progress.update {
                    it + (accountId to OfflineProgress(accountId, OfflineState.PAUSED))
                }
            }

            override suspend fun resumeOfflineDownload(accountId: Long) {
                progress.update {
                    it + (accountId to OfflineProgress(accountId, OfflineState.QUEUED))
                }
            }

            override suspend fun storageUsage() =
                StorageUsage(
                    accounts = listOf(AccountStorageUsage(1, 20, 5, 10 * MIB, 2 * MIB, MIB, 1024)),
                    availableBytes = 1024 * MIB,
                )

            override suspend fun clearCachedMail(accountId: Long): CacheCleanupResult {
                pauseOfflineDownload(accountId)
                cleared.update { it + accountId }
                return CacheCleanupResult(MIB, 2)
            }
        }
}
