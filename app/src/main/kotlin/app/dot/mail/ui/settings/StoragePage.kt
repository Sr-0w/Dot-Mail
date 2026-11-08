package app.dot.mail.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dot.design.*
import app.dot.design.components.*
import app.dot.mail.LocalMailGraph
import app.dot.mail.R
import app.dot.mail.data.model.*
import app.dot.mail.ui.common.Format

@Composable
internal fun StoragePage(onBack: () -> Unit) {
    val graph = LocalMailGraph.current
    val vm: StorageViewModel = viewModel { StorageViewModel(graph) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val state by vm.account.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val limit by vm.globalLimit.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableLongStateOf(0L) }
    var clearing by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(selected, accounts) {
        if (accounts.none { it.id == selected }) selected = accounts.firstOrNull()?.id ?: 0L
        if (selected != 0L) vm.select(selected)
    }
    val c = Dot.colors
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(c.background)) {
        DotTopBar(
            title = stringResource(R.string.storage_title),
            navigation = { BackButton(onBack) },
            elevated = scroll.value > 0,
        )
        Column(Modifier.weight(1f).verticalScroll(scroll).navigationBarsPadding()) {
            Text(
                stringResource(R.string.storage_explanation),
                style = Dot.type.callout,
                color = c.textSecondary,
                modifier = Modifier.padding(DotSpacing.gutter),
            )
            usage?.let { u ->
                ListRow(
                    stringResource(R.string.storage_used),
                    subtitle = Format.size(u.totalBytes),
                    icon = Icons.Outlined.Storage,
                )
                ListRow(
                    stringResource(R.string.storage_database),
                    subtitle = Format.size(u.databaseBytes),
                )
                ListRow(
                    stringResource(R.string.storage_local_work),
                    subtitle = Format.size(u.outboxBytes + u.draftAttachmentBytes),
                )
                if (u.orphanCacheBytes > 0)
                    ListRow(
                        stringResource(R.string.storage_orphans),
                        subtitle = Format.size(u.orphanCacheBytes),
                    )
                ListRow(
                    stringResource(R.string.storage_available),
                    subtitle = Format.size(u.availableBytes),
                )
                if (u.isApproximate)
                    Text(
                        stringResource(R.string.storage_approximate),
                        style = Dot.type.caption,
                        color = c.textTertiary,
                        modifier = Modifier.padding(horizontal = DotSpacing.gutter),
                    )
            }
            ListRow(
                stringResource(R.string.storage_refresh_usage),
                icon = Icons.Outlined.Refresh,
                onClick = { vm.refreshUsage() },
            )
            val limits =
                (listOf(0L, 128 * MIB, 512 * MIB, 1024 * MIB, 5120 * MIB) + limit)
                    .distinct()
                    .sorted()
            ChoiceRow(
                stringResource(R.string.storage_global_limit),
                Icons.Outlined.Storage,
                limit,
                limits.map {
                    it to
                        if (it == 0L) stringResource(R.string.storage_unlimited)
                        else Format.size(it)
                },
                enabled = !busy,
                onChange = vm::globalLimit,
            )
            Hairline()
            if (accounts.isNotEmpty()) {
                ChoiceRow(
                    stringResource(R.string.storage_account),
                    Icons.Outlined.Person,
                    selected,
                    accounts.map { it.id to it.displayName },
                    enabled = !busy,
                    onChange = { selected = it },
                )
                usage
                    ?.accounts
                    ?.firstOrNull { it.accountId == selected }
                    ?.let { account ->
                        ListRow(
                            stringResource(R.string.storage_payloads),
                            subtitle = Format.size(account.bodyBytes + account.attachmentBytes),
                        )
                        ListRow(
                            stringResource(R.string.storage_protected),
                            subtitle = Format.size(account.protectedBytes),
                        )
                    }
                state
                    ?.takeIf { it.accountId == selected }
                    ?.let { account ->
                        key(account.accountId, account.policy) {
                            PolicyEditor(account.policy, busy) { vm.save(account.accountId, it) }
                        }
                        Hairline()
                        val progress = account.progress
                        ListRow(
                            stringResource(R.string.storage_progress),
                            subtitle = stringResource(progress.state.label()),
                        )
                        ListRow(
                            stringResource(R.string.storage_messages),
                            subtitle =
                                if (progress.totalMessages > 0)
                                    stringResource(
                                        R.string.storage_counts,
                                        progress.cachedMessages,
                                        progress.totalMessages,
                                    )
                                else
                                    stringResource(
                                        R.string.storage_count_unknown,
                                        progress.cachedMessages,
                                    ),
                        )
                        ListRow(
                            stringResource(R.string.storage_bodies),
                            subtitle = progress.downloadedBodies.toString(),
                        )
                        ListRow(
                            stringResource(R.string.storage_attachments),
                            subtitle =
                                stringResource(
                                    R.string.storage_counts,
                                    progress.downloadedAttachments,
                                    progress.totalAttachments,
                                ),
                        )
                        if (progress.skippedMessages > 0)
                            Text(
                                pluralStringResource(
                                    R.plurals.storage_skipped,
                                    progress.skippedMessages,
                                    progress.skippedMessages,
                                ),
                                style = Dot.type.caption,
                                color = c.textSecondary,
                                modifier = Modifier.padding(horizontal = DotSpacing.gutter),
                            )
                        Row(
                            Modifier.fillMaxWidth().padding(DotSpacing.gutter),
                            horizontalArrangement = Arrangement.spacedBy(DotSpacing.s),
                        ) {
                            DotButton(
                                stringResource(
                                    if (progress.state == OfflineState.PAUSED)
                                        R.string.storage_resume
                                    else R.string.storage_pause
                                ),
                                {
                                    if (progress.state == OfflineState.PAUSED) vm.resume(selected)
                                    else vm.pause(selected)
                                },
                                enabled = !busy,
                                style = ButtonStyle.Secondary,
                            )
                            DotButton(
                                stringResource(R.string.storage_retry),
                                { vm.resume(selected) },
                                enabled = !busy,
                                style = ButtonStyle.Secondary,
                            )
                        }
                        ListRow(
                            stringResource(R.string.storage_clear),
                            subtitle = stringResource(R.string.storage_clear_sub),
                            icon = Icons.Outlined.DeleteSweep,
                            onClick = if (!busy) ({ clearing = selected }) else null,
                        )
                    }
            }
            if (failed)
                Text(
                    stringResource(R.string.storage_error),
                    style = Dot.type.callout,
                    color = c.danger,
                    modifier = Modifier.padding(DotSpacing.gutter),
                )
            freed?.let {
                Text(
                    stringResource(R.string.storage_freed, Format.size(it)),
                    style = Dot.type.caption,
                    color = c.textSecondary,
                    modifier = Modifier.padding(DotSpacing.gutter),
                )
            }
            Spacer(Modifier.height(DotSpacing.xl))
        }
    }
    clearing?.let { clearingAccount ->
        AlertDialog(
            onDismissRequest = { clearing = null },
            shape = DotShapes.large,
            containerColor = c.surface,
            modifier = Modifier.dotSurfaceEnter(),
            title = {
                Text(
                    stringResource(R.string.storage_clear),
                    style = Dot.type.title,
                    color = c.textPrimary,
                )
            },
            text = {
                Text(
                    stringResource(R.string.storage_clear_confirm),
                    style = Dot.type.callout,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                DotButton(
                    stringResource(R.string.storage_clear),
                    {
                        clearing = null
                        vm.clear(clearingAccount)
                    },
                    style = ButtonStyle.Danger,
                    compact = true,
                )
            },
            dismissButton = {
                DotButton(
                    stringResource(R.string.storage_cancel),
                    { clearing = null },
                    style = ButtonStyle.Secondary,
                    compact = true,
                )
            },
        )
    }
}

@Composable
private fun PolicyEditor(policy: StoragePolicy, busy: Boolean, onSave: (StoragePolicy) -> Unit) {
    var editing by rememberSaveable(stateSaver = StoragePolicySaver) { mutableStateOf(policy) }
    var count by rememberSaveable { mutableStateOf(policy.latestMessages.toString()) }
    var days by rememberSaveable { mutableStateOf(policy.days.toString()) }
    var limit by rememberSaveable { mutableStateOf((policy.maxBytes / MIB).toString()) }
    val parsed = storagePolicyInput(editing, count, days, limit)
    ListRow(
        stringResource(R.string.storage_full_offline),
        subtitle = stringResource(R.string.storage_full_offline_sub),
        icon = Icons.Outlined.Download,
        onClick =
            if (busy) null
            else
                ({
                    editing =
                        editing.copy(
                            retention = RetentionMode.ALL,
                            downloadBodies = true,
                            maxBytes = 0,
                        )
                    limit = "0"
                }),
    )
    ChoiceRow(
        stringResource(R.string.storage_retention),
        Icons.Outlined.History,
        editing.retention,
        RetentionMode.entries.map {
            it to
                stringResource(
                    when (it) {
                        RetentionMode.LATEST_MESSAGES -> R.string.storage_latest
                        RetentionMode.LAST_DAYS -> R.string.storage_days
                        RetentionMode.ALL -> R.string.storage_all
                    }
                )
        },
        enabled = !busy,
        onChange = { editing = editing.copy(retention = it) },
    )
    if (editing.retention == RetentionMode.LATEST_MESSAGES)
        DotTextField(
            count,
            { count = it },
            label = stringResource(R.string.storage_latest_count),
            keyboardType = KeyboardType.Number,
            enabled = !busy,
            modifier = Modifier.padding(horizontal = DotSpacing.gutter),
        )
    if (editing.retention == RetentionMode.LAST_DAYS)
        DotTextField(
            days,
            { days = it },
            label = stringResource(R.string.storage_days_count),
            keyboardType = KeyboardType.Number,
            enabled = !busy,
            modifier = Modifier.padding(horizontal = DotSpacing.gutter),
        )
    DotTextField(
        limit,
        { limit = it },
        label = stringResource(R.string.storage_account_limit),
        supporting = stringResource(R.string.storage_zero_unlimited),
        keyboardType = KeyboardType.Number,
        enabled = !busy,
        modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
    )
    ToggleRow(
        stringResource(R.string.storage_prefetch_bodies),
        null,
        Icons.Outlined.Download,
        editing.downloadBodies,
        enabled = !busy,
    ) {
        editing =
            editing.copy(
                downloadBodies = it,
                downloadAttachments = editing.downloadAttachments && it,
            )
    }
    ToggleRow(
        stringResource(R.string.storage_prefetch_attachments),
        null,
        Icons.Outlined.AttachFile,
        editing.downloadAttachments,
        enabled = !busy,
    ) {
        editing =
            editing.copy(downloadAttachments = it, downloadBodies = editing.downloadBodies || it)
    }
    ToggleRow(
        stringResource(R.string.storage_wifi),
        null,
        Icons.Outlined.Wifi,
        editing.wifiOnly,
        enabled = !busy,
    ) {
        editing = editing.copy(wifiOnly = it)
    }
    ToggleRow(
        stringResource(R.string.storage_charging),
        null,
        Icons.Outlined.BatteryChargingFull,
        editing.chargingOnly,
        enabled = !busy,
    ) {
        editing = editing.copy(chargingOnly = it)
    }
    if (parsed == null)
        Text(
            stringResource(R.string.storage_invalid),
            color = Dot.colors.danger,
            style = Dot.type.caption,
            modifier = Modifier.padding(horizontal = DotSpacing.gutter),
        )
    DotButton(
        stringResource(R.string.storage_apply),
        { parsed?.let(onSave) },
        enabled = !busy && parsed != null,
        modifier = Modifier.padding(DotSpacing.gutter),
    )
}

private fun OfflineState.label(): Int =
    when (this) {
        OfflineState.IDLE -> R.string.storage_idle
        OfflineState.QUEUED -> R.string.storage_queued
        OfflineState.DOWNLOADING -> R.string.storage_downloading
        OfflineState.PAUSED -> R.string.storage_paused
        OfflineState.WAITING_FOR_NETWORK -> R.string.storage_wait_network
        OfflineState.WAITING_FOR_CHARGING -> R.string.storage_wait_charging
        OfflineState.STORAGE_LIMIT -> R.string.storage_limit_reached
        OfflineState.DEVICE_FULL -> R.string.storage_device_full
        OfflineState.COMPLETE -> R.string.storage_complete
        OfflineState.PARTIAL -> R.string.storage_partial
        OfflineState.ERROR -> R.string.storage_error
    }

private val StoragePolicySaver =
    listSaver<StoragePolicy, Any>(
        save = {
            listOf(
                it.retention.name,
                it.latestMessages,
                it.days,
                it.maxBytes,
                it.downloadBodies,
                it.downloadAttachments,
                it.wifiOnly,
                it.chargingOnly,
            )
        },
        restore = {
            StoragePolicy(
                RetentionMode.valueOf(it[0] as String),
                it[1] as Int,
                it[2] as Int,
                it[3] as Long,
                it[4] as Boolean,
                it[5] as Boolean,
                it[6] as Boolean,
                it[7] as Boolean,
            )
        },
    )
