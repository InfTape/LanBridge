package dev.lanbridge

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.util.Locale

internal fun receivedFileShare(resolver: ContentResolver, uri: Uri, name: String): Intent {
    val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() && '*' !in it && it != "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
        ?: "application/octet-stream"
    return Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData(name, arrayOf(mime), ClipData.Item(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
