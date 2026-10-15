package com.dewijones92.totum.backup.drive

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafTreeFolder(private val resolver: ContentResolver, private val tree: Uri) : BackupFolder {
    override val where: String = "Drive folder ${Uri.decode(tree.toString()).substringAfterLast(':')}"
    private val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun children(): List<Triple<String, String, Long>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
            }
        }.orEmpty()
    }

    private fun documentFor(name: String): Uri? =
        children().firstOrNull { it.second == name }?.let {
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                it.first
            )
        }

    override suspend fun list(): List<BackupCopy> = withContext(Dispatchers.IO) {
        children().map { BackupCopy(it.second, it.third) }
    }

    override suspend fun write(name: String, text: String): Unit = withContext(Dispatchers.IO) {
        val target = documentFor(name)
            ?: DocumentsContract.createDocument(resolver, parent, MIME, name)
            ?: error("the folder refused a new file $name")
        checkNotNull(
            resolver.openOutputStream(target, "wt")
        ) { "no way to write $name" }.use { it.write(text.toByteArray()) }
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        documentFor(name)?.let { uri -> resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        documentFor(name)?.let { DocumentsContract.deleteDocument(resolver, it) }
    }

    private companion object {
        const val MIME = "application/json"
    }
}

class SafSingleFile(private val resolver: ContentResolver, private val document: Uri) : BackupFolder {
    override val where: String = "Drive file ${Uri.decode(document.toString()).substringAfterLast(':')}"

    override suspend fun list(): List<BackupCopy> = withContext(Dispatchers.IO) {
        listOf(BackupCopy(DriveBackup.LATEST, modifiedAt()))
    }

    private fun modifiedAt(): Long =
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    override suspend fun write(name: String, text: String): Unit = withContext(Dispatchers.IO) {
        require(name == DriveBackup.LATEST) { "a single Drive file holds only the latest backup" }
        checkNotNull(resolver.openOutputStream(document, "wt")) { "no way to write the Drive file" }
            .use { it.write(text.toByteArray()) }
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        resolver.openInputStream(document)?.use { it.readBytes().decodeToString() }?.takeIf { it.isNotBlank() }
    }

    override suspend fun delete(name: String) = Unit
}
