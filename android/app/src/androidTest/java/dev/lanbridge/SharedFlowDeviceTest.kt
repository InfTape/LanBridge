package dev.lanbridge

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses real ACTION_SEND intents, MediaStore, production engine, and a Win32 echo fixture. */
@RunWith(AndroidJUnit4::class)
class SharedFlowDeviceTest {
    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime()+20000
        while(!condition() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
        assertTrue(description,condition())
    }
    @Test fun sharesAutomaticallyAfterPairingAcrossRecreationAndFromNewIntents() {
        val arguments = InstrumentationRegistry.getArguments()
        val code = arguments.getString("fixtureCode")
        assumeTrue("Requires scripts/test-android-share.ps1",code != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BridgeApplication
        val engine = app.engine
        val preferences = app.getSharedPreferences("pairing",0)
        val previousAddress = preferences.getString("address",null)
        val marker = "LanBridge-auto-share-${UUID.randomUUID()}"
        val created = mutableListOf<Uri>()
        val expected = mutableListOf<ByteArray>()
        val names = mutableListOf<String>()
        engine.disconnect()
        val shareIntent = Intent(app,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,marker).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<MainActivity>(shareIntent)
        try {
            waitFor("Cold ACTION_SEND should wait for pairing") { engine.state.value.pendingShares == 1 }
            scenario.recreate()
            SystemClock.sleep(300)
            assertEquals("Activity recreation must not enqueue the same intent twice",1,engine.state.value.pendingShares)
            scenario.onActivity { engine.connect("127.0.0.1:${arguments.getString("fixturePort")}",code!!) }
            waitFor("Shared text should be sent automatically after pairing") { engine.state.value.received.count { it.text == marker } == 1 }
            waitFor("Shared text queue drained") { engine.state.value.pendingShares == 0 && !engine.state.value.sending }
            SystemClock.sleep(300)
            assertEquals(1,engine.state.value.received.count { it.text == marker })
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM,Uri.parse("content://media/external/downloads/9223372036854775806")))
            }
            waitFor("Unreadable local file must report a send error") { engine.state.value.status.startsWith("发送失败") }
            assertTrue("Unreadable local file must not disconnect the network",engine.state.value.connected)
            for(size in listOf(0,262145,1048593)) {
                val name = "$marker-$size.bin"
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME,name)
                    put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH,"Download/LanBridge")
                    put(MediaStore.Downloads.IS_PENDING,1)
                }) ?: error("Cannot create test media")
                created.add(uri); names.add(name)
                val bytes = ByteArray(size) { i -> (i*17+29).toByte() }; expected.add(bytes)
                app.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes) }
                app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)
            }
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM,created[0]).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            waitFor("Single file should send without confirmation") { engine.state.value.received.any { it.name == names[0] } }
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity,MainActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE).setType("application/octet-stream")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf(created[1],created[2])).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            waitFor("Multiple files should send without confirmation") { names.all { name -> engine.state.value.received.any { it.name == name } } }
            waitFor("File queue drained") { engine.state.value.pendingShares == 0 && !engine.state.value.sending }
            for(i in names.indices) {
                val received = engine.state.value.received.single { it.name == names[i] }
                assertEquals(expected[i].size.toLong(),received.size)
                val actual = app.contentResolver.openInputStream(received.uri!!)!!.use { it.readBytes() }
                assertArrayEquals("Real Android/Win32 file round trip",expected[i],actual)
            }
        } finally {
            engine.disconnect()
            for(uri in created) app.contentResolver.delete(uri,null,null)
            for(file in engine.state.value.received.filter { it.uri != null && it.name.startsWith(marker) }) app.contentResolver.delete(file.uri!!,null,null)
            preferences.edit().apply { if(previousAddress == null) remove("address") else putString("address",previousAddress) }.apply()
            scenario.close()
        }
    }
}
