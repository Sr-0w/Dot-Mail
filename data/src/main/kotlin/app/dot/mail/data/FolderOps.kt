package app.dot.mail.data

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import app.dot.mail.data.local.AccountRow
import app.dot.mail.data.local.FolderRow
import app.dot.mail.data.local.OperationRow
import app.dot.mail.data.model.AccountConfig
import app.dot.mail.data.model.FolderException
import app.dot.mail.data.model.FolderFailure
import app.dot.mail.engine.Credentials
import app.dot.mail.engine.FolderOpException
import app.dot.mail.engine.RemoteFolder
import app.dot.mail.engine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Folder management. A folder command changes the local database at once (the UI updates instantly) and leaves an
 * OperationRow with folderId = -1 (so the per-folder message replay never touches it); the local folder id is in
 * `uid` and the arguments are in `target`, joined with U+0001:
 *    FOLDER_CREATE  parentPath, name          (parentPath empty = top level)
 *    FOLDER_RENAME  oldPath, newParentPath, newName
 *    FOLDER_DELETE  path
 * Sync replays them in order before listing folders (create/rename) or after all message operations (delete, so a
 * folder is never deleted while messages are still being moved out of it). The server wins on conflicts.
 */

internal const val OP_FOLDER_CREATE = "FOLDER_CREATE"
internal const val OP_FOLDER_RENAME = "FOLDER_RENAME"
internal const val OP_FOLDER_DELETE = "FOLDER_DELETE"
private const val JOIN = '\u0001'
private val FOLDER_OPS = setOf(OP_FOLDER_CREATE, OP_FOLDER_RENAME, OP_FOLDER_DELETE)

private fun args(vararg parts: String) = parts.joinToString(JOIN.toString())
private fun parts(op: OperationRow) = op.target.orEmpty().split(JOIN)

internal fun RealMailGraph.folderSeparator(accountId: Long): String =
    context.getSharedPreferences("dotmail_folder_separators", Context.MODE_PRIVATE).getString("$accountId", null) ?: "/"

private fun RealMailGraph.rememberSeparator(accountId: Long, separator: Char) =
    context.getSharedPreferences("dotmail_folder_separators", Context.MODE_PRIVATE).edit { putString("$accountId", separator.toString()) }

/** Names can't contain either common hierarchy separator, IMAP wildcards or control characters. */
internal fun validateFolderName(raw: String): String {
    val name = raw.trim()
    val bad = name.isEmpty() || name.length > 100 || name == "." || name == ".." ||
        name.any { it in "/.*%#\\" || it.isISOControl() }
    if (bad) throw FolderException(FolderFailure.INVALID_NAME, "Folder names can't be empty or contain / . * % # \\")
    return name
}

private fun parentPathOf(row: FolderRow, sep: String): String? =
    if (row.path.length > row.name.length + sep.length && row.path.endsWith(sep + row.name)) row.path.dropLast(row.name.length + sep.length) else null

private fun isInSubtree(f: FolderRow, root: FolderRow, sep: String) = f.id == root.id || f.path.startsWith(root.path + sep)

// ------------------------------------------------------------------------------------------------ local side

internal suspend fun RealMailGraph.createFolderLocal(accountId: Long, parentId: Long?, rawName: String): Long = withContext(Dispatchers.IO) {
    requireNotNull(dao.account(accountId)) { "Unknown account" }
    val name = validateFolderName(rawName)
    val sep = folderSeparator(accountId)
    val id = database.withTransaction {
        val parent = parentId?.let { dao.folder(it) ?: throw FolderException(FolderFailure.NOT_FOUND, "The parent folder no longer exists") }
        if (parent != null && (parent.accountId != accountId || dao.operations(accountId).any { it.action == OP_FOLDER_DELETE && it.uid == parent.id })) {
            throw FolderException(FolderFailure.INVALID_TARGET, "Choose a folder in the same account")
        }
        val path = parent?.let { it.path + sep + name } ?: name
        if (dao.folders(accountId).any { it.path.equals(path, ignoreCase = true) }) {
            throw FolderException(FolderFailure.ALREADY_EXISTS, "A folder named \"$name\" already exists here")
        }
        val created = dao.insertFolder(FolderRow(accountId = accountId, path = path, name = name, role = Role.CUSTOM.name))
        dao.insertOperation(OperationRow(accountId = accountId, folderId = -1, validity = 0, uid = created, action = OP_FOLDER_CREATE, target = args(parent?.path.orEmpty(), name)))
        created
    }
    requestSync()
    id
}

internal suspend fun RealMailGraph.relocateFolderLocal(folderId: Long, keepParent: Boolean, newParentId: Long?, newName: String?) {
    withContext(Dispatchers.IO) {
        database.withTransaction {
            val row = dao.folder(folderId) ?: throw FolderException(FolderFailure.NOT_FOUND, "The folder no longer exists")
            if (row.role != Role.CUSTOM.name) throw FolderException(FolderFailure.PROTECTED_FOLDER, "System folders can't be renamed or moved")
            val sep = folderSeparator(row.accountId)
            val all = dao.folders(row.accountId)
            val subtree = all.filter { isInSubtree(it, row, sep) }.map { it.id }.toSet()
            if (dao.operations(row.accountId).any { it.action in FOLDER_OPS && it.uid in subtree }) {
                throw FolderException(FolderFailure.PENDING, "This folder is still synchronising. Try again in a moment.")
            }
            val name = newName?.let(::validateFolderName) ?: row.name
            val newParentPath: String? = if (keepParent) parentPathOf(row, sep) else newParentId?.let { p ->
                val parent = dao.folder(p) ?: throw FolderException(FolderFailure.NOT_FOUND, "The destination folder no longer exists")
                if (parent.accountId != row.accountId || isInSubtree(parent, row, sep)) {
                    throw FolderException(FolderFailure.INVALID_TARGET, "A folder can't be moved into itself or its subfolders")
                }
                parent.path
            }
            val newPath = newParentPath?.let { it + sep + name } ?: name
            if (newPath == row.path) return@withTransaction
            if (all.any { it.id != row.id && it.path.equals(newPath, ignoreCase = true) }) {
                throw FolderException(FolderFailure.ALREADY_EXISTS, "A folder named \"$name\" already exists there")
            }
            all.filter { it.id in subtree }.forEach { f ->
                dao.updateFolder(f.copy(path = newPath + f.path.removePrefix(row.path), name = if (f.id == row.id) name else f.name))
            }
            dao.insertOperation(OperationRow(accountId = row.accountId, folderId = -1, validity = 0, uid = row.id, action = OP_FOLDER_RENAME, target = args(row.path, newParentPath.orEmpty(), name)))
        }
    }
    requestSync()
}

internal suspend fun RealMailGraph.deleteFolderLocal(folderId: Long, moveContentsTo: Long?) {
    withContext(Dispatchers.IO) {
        database.withTransaction {
            val row = dao.folder(folderId) ?: throw FolderException(FolderFailure.NOT_FOUND, "The folder no longer exists")
            if (row.role != Role.CUSTOM.name) throw FolderException(FolderFailure.PROTECTED_FOLDER, "System folders can't be deleted")
            val sep = folderSeparator(row.accountId)
            val ops = dao.operations(row.accountId)
            if (ops.any { it.action == OP_FOLDER_DELETE && it.uid == row.id }) return@withTransaction // already on its way
            if (dao.folders(row.accountId).any { it.id != row.id && isInSubtree(it, row, sep) }) {
                throw FolderException(FolderFailure.HAS_SUBFOLDERS, "Delete or move the subfolders first")
            }
            val pendingCreate = ops.firstOrNull { it.action == OP_FOLDER_CREATE && it.uid == row.id }
            if (pendingCreate != null) { // never reached the server: just forget it
                dao.deleteOperation(pendingCreate.id); dao.deleteFolderRow(row.id); return@withTransaction
            }
            if (ops.any { it.action in FOLDER_OPS && it.uid == row.id }) {
                throw FolderException(FolderFailure.PENDING, "This folder is still synchronising. Try again in a moment.")
            }
            val messages = dao.folderMessages(row.id)
            if (messages.isNotEmpty()) {
                val targetId = moveContentsTo ?: throw FolderException(FolderFailure.NOT_EMPTY, "This folder contains ${messages.size} messages")
                val target = dao.folder(targetId) ?: throw FolderException(FolderFailure.NOT_FOUND, "The destination folder no longer exists")
                if (target.accountId != row.accountId || target.id == row.id) {
                    throw FolderException(FolderFailure.INVALID_TARGET, "Choose another folder of the same account for the messages")
                }
                messages.forEach { moveMessage(it, target) }
                messages.map { it.threadId }.distinct().forEach { rebuildThread(it) }
            }
            dao.insertOperation(OperationRow(accountId = row.accountId, folderId = -1, validity = 0, uid = row.id, action = OP_FOLDER_DELETE, target = row.path))
        }
    }
    requestSync()
}

// ------------------------------------------------------------------------------------------------ sync side

internal class FolderSyncOutcome(val warning: String?, val success: Boolean)

/** Replays queued CREATE / RENAME commands, oldest first. Server refusals roll the local change back and are reported. */
internal suspend fun RealMailGraph.runFolderOps(account: AccountRow, config: AccountConfig, creds: Credentials): FolderSyncOutcome {
    var warning: String? = null
    val endpoint = config.imap.endpoint()
    val ops = dao.operations(account.id).filter { it.action == OP_FOLDER_CREATE || it.action == OP_FOLDER_RENAME }
    for (op in ops) {
        val p = parts(op)
        try {
            when (op.action) {
                OP_FOLDER_CREATE -> {
                    val created = try { client.createFolder(endpoint, creds, p[0].ifEmpty { null }, p[1]) }
                    catch (e: FolderOpException) { if (e.kind == FolderOpException.Kind.EXISTS) null else throw e } // replay after a lost reply, or already there: fine
                    if (created != null) dao.folder(op.uid)?.let { if (it.path != created) dao.updateFolder(it.copy(path = created)) }
                }
                OP_FOLDER_RENAME -> {
                    val renamed = client.renameFolder(endpoint, creds, p[0], p[1].ifEmpty { null }, p[2])
                    dao.folder(op.uid)?.let { if (it.path != renamed) dao.updateFolder(it.copy(path = renamed)) }
                }
            }
            dao.deleteOperation(op.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FolderOpException) {
            // Server wins: drop the command and undo the optimistic change.
            dao.deleteOperation(op.id)
            when (op.action) {
                OP_FOLDER_CREATE -> { dao.deleteFolderRow(op.uid); warning = warning ?: "Couldn't create folder \"${p[1]}\": ${e.message}" }
                else -> {
                    // NOT_FOUND: deleted elsewhere, folder reconciliation removes our copy. Otherwise put the old name back.
                    if (e.kind != FolderOpException.Kind.NOT_FOUND) {
                        revertRename(op, p[0])
                        warning = warning ?: "Couldn't rename folder \"${p[0].substringAfterLast(folderSeparator(account.id))}\": ${e.message}"
                    }
                }
            }
        } catch (e: Exception) {
            SyncDiagnostics.warn("Folder operation ${op.action} account=${account.id}", e, setOf(account.email, creds.secret))
            return FolderSyncOutcome(SyncDiagnostics.stored(e), success = false) // connection trouble: keep it queued, keep the order
        }
    }
    return FolderSyncOutcome(warning, success = true)
}

private suspend fun RealMailGraph.revertRename(op: OperationRow, oldPath: String) {
    val row = dao.folder(op.uid) ?: return
    val sep = folderSeparator(row.accountId)
    dao.folders(row.accountId).filter { isInSubtree(it, row, sep) }.forEach { f ->
        val restored = oldPath + f.path.removePrefix(row.path)
        dao.updateFolder(f.copy(path = restored, name = if (f.id == row.id) oldPath.substringAfterLast(sep) else f.name))
    }
}

/**
 * Brings the local folder list in line with the server: folders renamed elsewhere keep their messages (told apart from
 * delete + create by an unchanged UIDVALIDITY), folders deleted elsewhere disappear with their cached messages.
 */
internal suspend fun RealMailGraph.reconcileFolders(account: AccountRow, config: AccountConfig, creds: Credentials, remote: List<RemoteFolder>) {
    if (remote.none { it.role == Role.INBOX }) return // never act on a LIST that looks broken
    rememberSeparator(account.id, remote.first().separator)
    val pendingCreates = dao.operations(account.id).filter { it.action == OP_FOLDER_CREATE }.map { it.uid }.toSet()
    val local = dao.folders(account.id)
    val remotePaths = remote.map { it.path }.toSet()
    val vanished = local.filter { it.path !in remotePaths && it.id !in pendingCreates }.toMutableList()
    if (vanished.isEmpty()) return
    val localPaths = local.map { it.path }.toSet()
    val endpoint = config.imap.endpoint()
    for (r in remote.filter { it.path !in localPaths }) {
        if (vanished.isEmpty()) break
        val validity = client.folderValidity(endpoint, creds, r.path)
        if (validity <= 0) continue
        val match = vanished.firstOrNull { it.validity == validity } ?: continue
        dao.updateFolder(match.copy(path = r.path, name = r.name))
        vanished.remove(match)
    }
    for (f in vanished) {
        database.withTransaction {
            val threads = dao.folderMessages(f.id).map { it.threadId }.distinct()
            dao.operations(account.id).filter { it.folderId == f.id || (it.action in FOLDER_OPS && it.uid == f.id) }.forEach { dao.deleteOperation(it.id) }
            dao.deleteFolderRow(f.id)
            threads.forEach { rebuildThread(it) }
        }
    }
}

/** Runs queued DELETEs once no message operation is waiting on the folder any more. */
internal suspend fun RealMailGraph.finishFolderDeletes(account: AccountRow, config: AccountConfig, creds: Credentials): FolderSyncOutcome {
    var warning: String? = null
    val endpoint = config.imap.endpoint()
    for (op in dao.operations(account.id).filter { it.action == OP_FOLDER_DELETE }) {
        val row = dao.folder(op.uid)
        if (row == null) { dao.deleteOperation(op.id); continue }
        if (dao.operations(account.id).any { it.folderId == row.id }) return FolderSyncOutcome(warning, success = false) // messages still leaving
        try {
            client.deleteFolder(endpoint, creds, row.path)
            database.withTransaction { dao.deleteOperation(op.id); dao.deleteFolderRow(row.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FolderOpException) {
            dao.deleteOperation(op.id) // server wins: the folder stays, visible again
            warning = warning ?: "Couldn't delete folder \"${row.name}\": ${e.message}"
        } catch (e: Exception) {
            SyncDiagnostics.warn("Folder delete account=${account.id}", e, setOf(account.email, creds.secret))
            return FolderSyncOutcome(SyncDiagnostics.stored(e), success = false)
        }
    }
    return FolderSyncOutcome(warning, success = true)
}
