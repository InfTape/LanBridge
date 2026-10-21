package dev.lanbridge

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReceivedFileShareTest {
    @Test fun sharesDownloadedImagesWithTheirMimeTypeAndReadPermission() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        for((extension, mime) in listOf("png" to "image/png", "jpg" to "image/jpeg",
            "webp" to "image/webp", "gif" to "image/gif", "pdf" to "application/pdf",
            "bin" to "application/octet-stream")) {
            val name = "LanBridge-share-test-${UUID.randomUUID()}.$extension"
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/LanBridge")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }))
            try {
                // Verify the outgoing contract without sharing anything to another app.
                val share = receivedFileShare(resolver, uri, name)
                assertEquals(Intent.ACTION_SEND, share.action)
                assertEquals(mime, share.type)
                @Suppress("DEPRECATION")
                assertEquals(uri, share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                assertEquals(uri, share.clipData!!.getItemAt(0).uri)
                assertTrue(share.clipData!!.description.hasMimeType(mime))
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = Intent.createChooser(share, "分享")
                assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } finally { resolver.delete(uri, null, null) }
        }
    }
}
