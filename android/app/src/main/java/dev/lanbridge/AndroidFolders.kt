package dev.lanbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class AndroidFolders(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val prefs = context.getSharedPreferences("folder_storage",0)
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
    data class Document(val id: String, val name: String, val directory: Boolean, val size: Long, val flags: Int)
    data class Entry(val relative: String, val uri: Uri, val directory: Boolean, val size: Long, val cache: File? = null)
    data class Plan(val name: String, val entries: List<Entry>, val bytes: Long) : Closeable {
        override fun close() { entries.forEach { it.cache?.delete() } }
    }
    init {
        // Only URIs recorded by this app as unfinished staging directories are removed.
        for(value in prefs.getStringSet("pending",emptySet()).orEmpty().toList()) {
            runCatching { val uri = Uri.parse(value); val name = document(uri).name; require(name.startsWith(".lanbridge-") && name.endsWith(".part")); if(DocumentsContract.deleteDocument(resolver,uri)) unregister(uri) }
        }
    }
    fun destination(): Uri? = prefs.getString("destination",null)?.let(Uri::parse)?.takeIf { uri -> resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } }
    fun destinationName(): String? = destination()?.let { uri -> runCatching { document(documentUri(uri)).name }.getOrNull() }
    fun setDestination(uri: Uri) {
        resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(document(documentUri(uri)).directory)
        check(prefs.edit().putString("destination",uri.toString()).commit())
    }
    private fun documentUri(uri: Uri): Uri = if(DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context,uri)) DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)) else uri
    private fun document(uri: Uri): Document = resolver.query(uri,columns,null,null,null)?.use { cursor ->
        require(cursor.moveToFirst()) { "目录或文件已不可读取" }
        fun string(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
        val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        Document(string(columns[0]),string(columns[1]),string(columns[2]) == DocumentsContract.Document.MIME_TYPE_DIR,if(cursor.isNull(sizeIndex)) -1 else cursor.getLong(sizeIndex),cursor.getInt(cursor.getColumnIndexOrThrow(columns[4])))
    } ?: error("无法读取目录")
    private fun children(tree: Uri, id: String): List<Document> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree,id)
        return resolver.query(uri,columns,null,null,null)?.use { cursor ->
            buildList {
                while(cursor.moveToNext()) {
                    require(size < FolderProtocol.MAX_ENTRIES) { "目录超过 10000 项" }
                    val sizeIndex = cursor.getColumnIndexOrThrow(columns[3])
                    add(Document(cursor.getString(cursor.getColumnIndexOrThrow(columns[0])),cursor.getString(cursor.getColumnIndexOrThrow(columns[1])),cursor.getString(cursor.getColumnIndexOrThrow(columns[2])) == DocumentsContract.Document.MIME_TYPE_DIR,if(cursor.isNull(sizeIndex)) -1 else cursor.getLong(sizeIndex),cursor.getInt(cursor.getColumnIndexOrThrow(columns[4]))))
                }
            }
        } ?: error("无法列出文件夹内容")
    }
    fun isDirectory(uri: Uri): Boolean = resolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR
    fun savedName(uri: Uri): String = document(uri).name
    suspend fun collect(tree: Uri): Plan {
        require(DocumentsContract.isTreeUri(tree)) { "请通过“选择文件夹”授权目录；此分享来源未提供目录访问权限" }
        val root = document(documentUri(tree)); require(root.directory && FolderProtocol.path(root.name).size == 1)
        val entries = mutableListOf<Entry>(); val names = mutableSetOf<String>(); val visited = mutableSetOf(root.id); var bytes = 0L
        suspend fun walk(id: String, parent: String, depth: Int) {
            require(depth <= 65) { "目录层级超过 64 层" }
            for(child in children(tree,id)) {
                currentCoroutineContext().ensureActive(); require(entries.size < FolderProtocol.MAX_ENTRIES && visited.add(child.id)) { "目录过大或包含循环引用" }
                require(child.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) { "目录含无法直接读取的虚拟文件" }
                val relative = if(parent.isEmpty()) child.name else "$parent/${child.name}"
                require(FolderProtocol.path(child.name).size == 1 && names.add(FolderProtocol.key(relative))) { "目录含名称冲突" }
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree,child.id)
                var size = if(child.directory) 0L else child.size
                var cached: File? = null
                try {
                    if(size < 0) {
                        cached = File.createTempFile("lanbridge-folder-",".part",context.cacheDir)
                        resolver.openInputStream(uri)?.use { input -> cached.outputStream().use { out ->
                            val buffer = ByteArray(262144); size = 0
                            while(true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if(n < 0) break; require(size+n <= FolderProtocol.MAX_FILE && context.cacheDir.usableSpace > n+1024*1024) { "临时空间不足或文件超过上限" }; out.write(buffer,0,n); size += n }
                        } } ?: error("无法读取文件")
                    }
                    require(size in 0..FolderProtocol.MAX_FILE && size <= FolderProtocol.MAX_TOTAL-bytes)
                    entries.add(Entry(relative,uri,child.directory,size,cached)); cached = null
                    bytes += size
                } finally { cached?.delete() }
                if(child.directory) walk(child.id,relative,depth+1)
            }
        }
        try { walk(root.id,"",1); return Plan(root.name,entries,bytes) }
        catch(e: Exception) { entries.forEach { it.cache?.delete() }; throw e }
    }
    private fun unregister(uri: Uri) { val pending = prefs.getStringSet("pending",emptySet()).orEmpty().toMutableSet(); pending.remove(uri.toString()); check(prefs.edit().putStringSet("pending",pending).commit()) }
    fun receiver(header: FolderProtocol.Header): FolderReceiver {
        val tree = destination() ?: error("请先在手机选择文件夹接收位置（只需授权一次）")
        val parent = documentUri(tree)
        require(document(parent).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "所选位置不能创建目录，请重新选择接收位置" }
        val stage = DocumentsContract.createDocument(resolver,parent,DocumentsContract.Document.MIME_TYPE_DIR,".lanbridge-${UUID.randomUUID()}.part") ?: error("无法创建接收目录")
        try {
            require(document(stage).flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) { "此保存位置不支持安全完成目录传输，请选择手机本地目录" }
            val pending = prefs.getStringSet("pending",emptySet()).orEmpty()+stage.toString()
            check(prefs.edit().putStringSet("pending",pending).commit())
            val directories = mutableMapOf("" to stage)
            val storage = object : FolderStorage {
                private fun parent(path: String) = requireNotNull(directories[path.substringBeforeLast('/',"")])
                override fun directory(path: String) {
                    val name = path.substringAfterLast('/')
                    val uri = DocumentsContract.createDocument(resolver,parent(path),DocumentsContract.Document.MIME_TYPE_DIR,name) ?: error("无法创建子目录")
                    require(document(uri).name == name) { "保存位置修改了目录名称，请选择手机本地目录" }; directories[path] = uri
                }
                override fun file(path: String): OutputStream {
                    val uri = DocumentsContract.createDocument(resolver,parent(path),"application/octet-stream",path.substringAfterLast('/')) ?: error("无法创建文件")
                    require(document(uri).name == path.substringAfterLast('/')) { "保存位置修改了文件名称，请选择手机本地目录" }
                    return resolver.openOutputStream(uri,"w") ?: error("无法写入文件")
                }
                override fun publish(name: String): String {
                    val occupied = children(tree,DocumentsContract.getDocumentId(parent)).map { it.name.uppercase(java.util.Locale.ROOT) }.toSet()
                    val finalName = (0..10000).asSequence().map { FolderProtocol.collisionName(name,it) }.first { it.uppercase(java.util.Locale.ROOT) !in occupied }
                    val result = DocumentsContract.renameDocument(resolver,stage,finalName) ?: error("无法完成文件夹保存")
                    runCatching { unregister(stage) }
                    return result.toString()
                }
                override fun abort() { if(DocumentsContract.deleteDocument(resolver,stage)) unregister(stage) }
            }
            return FolderReceiver(header,storage)
        } catch(e: Exception) { runCatching { DocumentsContract.deleteDocument(resolver,stage); unregister(stage) }; throw e }
    }
}
