package gothic2.mobile.app.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import gothic2.mobile.core.gamedata.GameTree
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * [GameTree] поверх Storage Access Framework: приложение видит только папку, которую
 * пользователь выбрал сам, без разрешения на доступ ко всему накопителю.
 */
class SafGameTree(private val resolver: ContentResolver, private val treeUri: Uri) : GameTree {
    private data class Node(val documentId: String, val entry: GameTree.Entry)

    private val listings = HashMap<String, List<Node>>()

    override fun list(dir: String): List<GameTree.Entry> = nodes(dir).map { it.entry }

    override fun open(file: String): InputStream {
        val parent = file.substringBeforeLast('/', "")
        val name = file.substringAfterLast('/')
        val node = nodes(parent).firstOrNull { it.entry.name == name && !it.entry.isDirectory }
            ?: throw FileNotFoundException(file)
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId)
        return resolver.openInputStream(uri) ?: throw FileNotFoundException(file)
    }

    private fun nodes(dir: String): List<Node> = listings.getOrPut(dir) {
        val parentId = if (dir.isEmpty()) {
            DocumentsContract.getTreeDocumentId(treeUri)
        } else {
            val parent = dir.substringBeforeLast('/', "")
            val name = dir.substringAfterLast('/')
            nodes(parent).firstOrNull { it.entry.name == name && it.entry.isDirectory }?.documentId
                ?: return@getOrPut emptyList()
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        resolver.query(children, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                    add(Node(c.getString(0), GameTree.Entry(c.getString(1), isDir, if (c.isNull(3)) 0 else c.getLong(3))))
                }
            }
        }.orEmpty()
    }
}
