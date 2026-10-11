package dev.lanbridge

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO

class QrDecoderTest {
    @Test fun copiesStridedCameraPlaneWithoutChangingPosition() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99,1,88,2,88,3,77,77,4,88,5,88,6))
        buffer.position(1)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6), QrDecoder.copyLuma(buffer,3,2,7,2))
        assertEquals(1,buffer.position())
    }
    @Test fun rejectsIncompleteFrames() {
        assertThrows(IllegalArgumentException::class.java) { QrDecoder.copyLuma(ByteBuffer.allocate(5),3,2,3,1) }
        assertThrows(IllegalArgumentException::class.java) { QrDecoder.copyLuma(ByteBuffer.allocate(6),0,2,3,1) }
        assertThrows(IllegalArgumentException::class.java) { QrDecoder.decode(ByteArray(3),2,2) }
    }
    @Test fun ignoresBlankFrames() { assertNull(QrDecoder.decode(ByteArray(640*480) { 255.toByte() },640,480)) }
    @Test fun decodesActualWindowsRenderingAndChangedAddress() {
        val path = System.getenv("LANBRIDGE_QR_IMAGE")
        assumeTrue("Run scripts/test-interop.ps1 -QrImage with Win32 QR fixtures",path != null)
        for(file in listOf(File(path!!), File("$path.changed.png"))) {
            val image = ImageIO.read(file)
            val width = image.width; val height = image.height
            val luma = ByteArray(width*height) { i ->
                val rgb = image.getRGB(i%width,i/width)
                (((rgb shr 16 and 255)*299 + (rgb shr 8 and 255)*587 + (rgb and 255)*114)/1000).toByte()
            }
            val expected = File("${file.path}.pairing.txt").readText()
            assertEquals("Desktop QR at ${file.name}", expected, QrDecoder.decode(luma,width,height))
            val pairing = Pairing.fromQr(expected)
            assertEquals(42831,pairing.port)
            assertEquals(16,pairing.code.length)
            if(file.path.endsWith(".changed.png")) assertEquals("192.168.77.42",pairing.host)
            // Raw camera buffers can be rotated with respect to the preview.
            val rotated = ByteArray(luma.size)
            for(y in 0 until height) for(x in 0 until width) rotated[x*height+(height-1-y)] = luma[y*width+x]
            assertEquals(expected,QrDecoder.decode(rotated,height,width))
        }
    }
}
