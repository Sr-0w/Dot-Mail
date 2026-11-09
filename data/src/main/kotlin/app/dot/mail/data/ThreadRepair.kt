package app.dot.mail.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.dot.mail.data.local.*
import app.dot.mail.engine.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The journal is also consulted before notifications, including after a crash before preferences
 * flush.
 */
internal suspend fun RealMailGraph.isThreadMuted(id: Long): Boolean {
    val rules = NotificationRules.get(context)
    return rules.isMuted(id) || dao.hasRepairMute(id) || rules.isMuted(id)
}

/**
 * Upgrade-only repair. Each original group commits atomically, including its checkpoint and
 * snoozes. Planning and read-only IMAP recovery run outside transactions and the global sync mutex.
 * Before applying a plan, recheck its streamed fingerprint under the write transaction: concurrent
 * sync can invalidate a plan, but cannot cause messages or new relationships to be silently
 * overwritten.
 */
internal class ThreadRepair(
    private val graph: RealMailGraph,
    private val recover: (suspend (ThreadRow, List<RepairMessage>) -> Map<Long, Header>)? = null,
    private val onPhase: suspend (Phase) -> Unit = {},
) {
    internal enum class Phase {
        PLANNED,
        UPDATING,
        COMMITTED,
        MUTES_DURABLE,
    }

    private val dao
        get() = graph.dao

    private val rules
        get() = NotificationRules.get(graph.context)

    internal suspend fun flushMutes() =
        graph.muteLock.withLock {
            while (true) {
                val pending = ArrayList<Long>()
                var after = 0L
                while (true) {
                    val page = dao.repairMutes(after)
                    if (page.isEmpty()) break
                    pending.addAll(page)
                    after = page.last()
                }
                if (pending.isEmpty()) break
                // A single preference write per snapshot avoids quadratic rewrites for big splits.
                rules.setMutedDurably(pending, true)
                onPhase(Phase.MUTES_DURABLE)
                // A crash before/during these deletes repeats only an idempotent durable write.
                pending.chunked(100).forEach { dao.finishRepairMutes(it) }
            }
        }

    /**
     * False means retryable work remains; unavailable historical evidence is retained with a
     * reason.
     */
    suspend fun run(): Boolean {
        flushMutes()
        var complete = true
        for (account in dao.accounts()) {
            var secrets = setOf(account.email)
            try {
                // There is no group to split for an identified singleton. Atomic SQL also
                // avoids needless network recovery for the common singleton case. Identifier-less
                // singletons still recover their evidence before any fallback alias is rewritten.
                dao.finishSingletonRepairs(account.id)
                // One authenticated lease per account, rather than a login for each legacy group.
                val ok =
                    if (recover == null && dao.legacyThreadRepairs(account.id)) {
                        if (account.authError) {
                            complete = false
                            continue
                        }
                        val config = Codec.config(account.config)
                        val credentials = graph.credentials(account)
                        secrets = secrets + credentials.secret
                        graph.client.withImapSession(config.imap.endpoint(), credentials) {
                            repairAccount(account.id)
                        }
                    } else repairAccount(account.id)
                if (!ok) complete = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncDiagnostics.warn("Thread repair account=${account.id}", e, secrets)
                if (
                    ConnectionFailure.classify(e) == ConnectionFailure.AUTH ||
                        SyncDiagnostics.problem(e) == app.dot.mail.data.model.SyncProblem.AUTH
                ) {
                    dao.syncError(account.id, SyncDiagnostics.stored(e), true)
                }
                complete = false
            }
        }
        flushMutes()
        if (graph.scheduling) graph.restoreSnoozeWork()
        return complete && !dao.hasThreadRepairs()
    }

    private suspend fun repairAccount(account: Long): Boolean {
        var after = 0L
        var complete = true
        while (true) {
            currentCoroutineContext().ensureActive()
            val job = dao.nextThreadRepair(account, after) ?: break
            after = job.threadId
            val thread = dao.threadRow(job.threadId) ?: continue
            try {
                when (repair(thread)) {
                    Outcome.RETRY -> complete = false
                    Outcome.UNAVAILABLE -> dao.threadRepairState(thread.id, "SOURCE_UNAVAILABLE")
                    Outcome.DONE -> Unit
                }
            } catch (e: org.json.JSONException) {
                SyncDiagnostics.warn("Thread repair unavailable thread=${thread.id}", e)
                dao.threadRepairState(thread.id, "MALFORMED_HEADER")
            }
        }
        return complete
    }

    private enum class Outcome {
        DONE,
        RETRY,
        UNAVAILABLE,
    }

    private suspend fun repair(thread: ThreadRow): Outcome {
        // Read in bounded metadata pages. Old builds omitted IRT/Gmail IDs; absence is NOT evidence
        // that the message was unrelated. Recover these headers before attempting any split.
        var after = 0L
        while (true) {
            val page = dao.threadRepairMessages(thread.id, after)
            if (page.isEmpty()) break
            after = page.last().id
            val unknown = page.filter { !hasEvidence(it.header) }
            if (unknown.isEmpty()) continue
            val recovered = recover?.invoke(thread, unknown) ?: recoverHeaders(thread, unknown)
            if (unknown.any { it.id !in recovered }) return Outcome.UNAVAILABLE
            var valid = true
            var identityMismatch = false
            graph.database.withTransaction {
                for (row in unknown) {
                    val current = dao.message(row.id)
                    val remote = recovered.getValue(row.id)
                    if (current?.threadId != thread.id || current.header != row.header) {
                        valid = false
                        continue
                    }
                    val old = JSONObject(row.header)
                    val oldId =
                        old.optString("messageId").takeIf { it.isNotBlank() && it != "null" }
                    if (oldId != null && oldId != remote.messageId) {
                        identityMismatch = true
                        valid = false
                        continue
                    }
                    // Preserve local flags, previews, classification, bodies and attachment
                    // references.
                    old.put("threadingRevision", 1)
                        .put("inReplyTo", remote.inReplyTo)
                        .put("gmailThreadId", remote.gmailThreadId)
                        .put("messageId", remote.messageId)
                        .put("refs", Codec.strings(remote.references))
                    dao.setHeader(row.id, old.toString())
                }
            }
            if (identityMismatch) return Outcome.UNAVAILABLE
            if (!valid) return Outcome.RETRY
        }

        val plan = plan(thread.id) ?: return Outcome.UNAVAILABLE
        onPhase(Phase.PLANNED)
        var applied = false
        graph.muteLock.withLock {
            graph.database.withTransaction {
                if (!plan.fingerprint.contentEquals(fingerprint(thread.id))) return@withTransaction
                if (dao.threadRow(thread.id) == null) return@withTransaction
                val snoozes = dao.threadSnoozes(thread.id)
                val muted = graph.isThreadMuted(thread.id)
                val components = plan.components()
                // Oldest (sentAt,localId) wins, independently of row/arrival/thread-key ordering.
                val keeper = plan.oldestRoot()
                val targets = mutableMapOf(keeper to thread.id)
                for ((component, ids) in components) {
                    if (component == keeper) continue
                    val target =
                        dao.insertThread(
                            ThreadRow(accountId = thread.accountId, subject = thread.subject)
                        )
                    targets[component] = target
                    ids.chunked(100).forEach {
                        dao.splitMessages(thread.id, target, it)
                        dao.splitMessageSnoozes(thread.id, target, it)
                    }
                    for (snooze in snoozes.filter { it.messageId == null }) {
                        dao.insertSnooze(snooze.copy(id = 0, threadId = target))
                    }
                    if (muted) dao.queueRepairMute(RepairMuteRow(target))
                }
                onPhase(Phase.UPDATING)
                // Remove stale subject aliases as well as identifiers pointing at the wrong
                // component.
                dao.clearThreadKeys(thread.id)
                for ((key, member) in plan.keys) {
                    dao.insertKey(
                        ThreadKeyRow(thread.accountId, key, targets.getValue(plan.root(member)))
                    )
                }
                for ((key, member) in plan.legacyKeys) {
                    val target = targets.getValue(plan.root(member))
                    val previous = dao.threadKey(thread.accountId, key)?.let { dao.threadRow(it) }
                    if (previous == null || previous.lastAt <= plan.dates[member]) {
                        dao.putKey(ThreadKeyRow(thread.accountId, key, target))
                    }
                }
                targets.values.forEach { graph.rebuildThread(it) }
                dao.finishThreadRepair(thread.id)
                applied = true
            }
        }
        if (!applied) return Outcome.RETRY
        onPhase(Phase.COMMITTED)
        flushMutes()
        return Outcome.DONE
    }

    private suspend fun recoverHeaders(
        thread: ThreadRow,
        rows: List<RepairMessage>,
    ): Map<Long, Header> {
        val account = dao.account(thread.accountId) ?: return emptyMap()
        val config = Codec.config(account.config)
        val credentials = graph.credentials(account)
        val result = mutableMapOf<Long, Header>()
        val operations = dao.operations(account.id)
        // Pending optimistic moves retain the original folder/UID in their queued operation.
        data class Source(val row: RepairMessage, val folder: FolderRow, val uid: Long)
        val sources =
            rows.mapNotNull { row ->
                val operation =
                    if (row.uid < 0)
                        operations.firstOrNull {
                            it.messageLocalId == row.id && it.action == "MOVE"
                        }
                    else null
                val folder =
                    dao.folder(operation?.folderId ?: row.folderId) ?: return@mapNotNull null
                val uid = operation?.uid ?: row.uid
                if (
                    uid <= 0 ||
                        folder.validity <= 0 ||
                        (operation != null && operation.validity != folder.validity)
                )
                    null
                else Source(row, folder, uid)
            }
        if (sources.isEmpty()) return emptyMap()
        graph.client.withImapSession(config.imap.endpoint(), credentials) {
            for ((folder, group) in sources.groupBy { it.folder }) {
                val headers =
                    try {
                        graph.client.headers(
                            config.imap.endpoint(),
                            credentials,
                            folder.path,
                            group.map { it.uid }.distinct().toLongArray(),
                            folder.validity,
                            strict = true,
                        )
                    } catch (e: jakarta.mail.FolderNotFoundException) {
                        continue // Its original headers no longer exist on the server.
                    } catch (e: IllegalArgumentException) {
                        if (e.message == "Folder UIDVALIDITY changed") continue else throw e
                    }
                val remote = headers.associateBy { it.uid }
                group.forEach { source -> remote[source.uid]?.let { result[source.row.id] = it } }
            }
        }
        return result
    }

    private suspend fun plan(thread: Long): Plan? {
        val result = Plan()
        val digest = MessageDigest.getInstance("SHA-256")
        var after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.threadRepairMessages(thread, after)
            if (page.isEmpty()) break
            after = page.last().id
            for (row in page) {
                update(digest, row)
                if (!hasEvidence(row.header)) return null
                val json = JSONObject(row.header)
                val mid = json.nullable("messageId")
                val refs = Codec.strings(json.optString("refs", "[]"))
                val reply = json.nullable("inReplyTo")
                val gmail =
                    if (json.isNull("gmailThreadId")) null else json.getLong("gmailThreadId")
                val header =
                    Header(
                        row.uid,
                        mid,
                        refs,
                        reply,
                        Codec.addressField(row.header, "from").map { Address(it.email, it.name) },
                        Codec.addressField(row.header, "to").map { Address(it.email, it.name) },
                        Codec.addressField(row.header, "cc").map { Address(it.email, it.name) },
                        emptyList(),
                        emptyList(),
                        row.subject,
                        row.sentAt,
                        RemoteFlags(row.uid, false, false, false),
                        false,
                        gmailThreadId = gmail,
                    )
                result.add(row, header)
            }
        }
        result.finishLegacy()
        result.fingerprint = digest.digest()
        return result
    }

    private suspend fun fingerprint(thread: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var after = 0L
        while (true) {
            val page = dao.threadRepairMessages(thread, after)
            if (page.isEmpty()) break
            after = page.last().id
            page.forEach { update(digest, it) }
        }
        return digest.digest()
    }

    /** Only identifiers/member IDs remain in memory; pages never include body columns. */
    private class Plan {
        val ids = ArrayList<Long>()
        val dates = ArrayList<Long>()
        val parents = ArrayList<Int>()
        val keys = HashMap<String, Int>()
        val legacyKeys = HashMap<String, Int>()
        private val legacy = ArrayList<Pair<String, Int>>()
        lateinit var fingerprint: ByteArray

        fun root(index: Int): Int {
            var n = index
            while (parents[n] != n) {
                parents[n] = parents[parents[n]]
                n = parents[n]
            }
            return n
        }

        private fun union(a: Int, b: Int) {
            val x = root(a)
            val y = root(b)
            if (x != y) parents[maxOf(x, y)] = minOf(x, y)
        }

        fun add(row: RepairMessage, header: Header) {
            val index = ids.size
            ids.add(row.id)
            dates.add(row.sentAt)
            parents.add(index)
            for (key in Threading.keys(header)) {
                keys.putIfAbsent(key, index)?.let { union(it, index) }
            }
            if (Threading.mayFallback(header))
                Threading.fallbackKey(header)?.let { legacy.add(it to index) }
        }

        fun finishLegacy() {
            for ((key, index) in
                legacy.sortedWith(
                    compareBy({ it.first }, { dates[it.second] }, { ids[it.second] })
                )) {
                val previous = legacyKeys.put(key, index)
                if (previous != null && dates[index] - dates[previous] <= 30L * 86400000)
                    union(previous, index)
            }
        }

        fun components(): Map<Int, List<Long>> = ids.indices.groupBy({ root(it) }, { ids[it] })

        fun oldestRoot(): Int =
            ids.indices.minWithOrNull(compareBy({ dates[it] }, { ids[it] }))?.let(::root) ?: -1
    }

    companion object {
        private fun hasEvidence(raw: String): Boolean =
            runCatching { JSONObject(raw).optInt("threadingRevision") == 1 }.getOrDefault(false)

        private fun JSONObject.nullable(key: String): String? =
            if (isNull(key)) null else getString(key)

        private fun update(digest: MessageDigest, row: RepairMessage) {
            // Length prefix avoids ambiguous concatenations; folder/flags/body edits do not affect
            // threading.
            val bytes = row.header.toByteArray(Charsets.UTF_8)
            digest.update(
                "${row.id}:${row.sentAt}:${row.subject.length}:${row.subject}:${bytes.size}:"
                    .toByteArray(Charsets.UTF_8)
            )
            digest.update(bytes)
        }
    }
}

class ThreadRepairWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        try {
            val graph = GraphRegistry.get(applicationContext)
            if (graph.threadRepair.run()) Result.success()
            else if (
                graph.dao.accounts().any {
                    !it.authError && graph.dao.nextThreadRepair(it.id, 0) != null
                }
            )
                Result.retry()
            else Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncDiagnostics.warn("Thread repair worker", e)
            Result.retry()
        }
}

internal suspend fun RealMailGraph.maintainThreadRepair() {
    if (dao.hasThreadRepairs()) WorkScheduling.threadRepair(context)
    dao.observeRepairMutes().collect { pending ->
        if (pending.isNotEmpty())
            try {
                threadRepair.flushMutes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncDiagnostics.warn("Thread repair mute journal", e)
                WorkScheduling.threadRepair(context)
            }
    }
}
