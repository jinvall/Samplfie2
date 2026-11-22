package com.example

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioBuffer
import com.example.audio.AudioImporter
import com.example.audio.AudioPlaybackEngine
import com.example.audio.WavCodec
import com.example.config.NetworkProtocol
import com.example.network.CollabInstrumentStyle
import com.example.network.CollabSyncCommand
import com.example.network.CollabWireCodec
import com.example.network.MAX_COLLAB_PEERS
import com.example.network.NetworkAudioTransmitter
import com.example.network.SessionHandoffSnapshot
import com.example.stems.DisbandApiClient
import com.example.stems.DisbandHttpResponse
import com.example.stems.DisbandHttpTransport
import com.example.stems.DisbandStem
import com.example.studio.StudioEngine
import com.example.ui.SamplfieViewModel
import java.io.File
import java.net.DatagramSocket
import java.net.ServerSocket
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollabVolumeAndImportTest {

    private val grantPermissionRule = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                shadowOf(app).grantPermissions(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
                base.evaluate()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(grantPermissionRule).around(composeRule)

    @Test
    fun manifestDeclaresPlayCompliantAudioAndMulticastPermissionsOnly() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()

        assertTrue(perms.contains(Manifest.permission.READ_MEDIA_AUDIO))
        assertTrue(perms.contains(Manifest.permission.ACCESS_WIFI_STATE))
        assertTrue(perms.contains(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE))
        assertTrue(perms.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(perms.contains(Manifest.permission.INTERNET))
        if (Build.VERSION.SDK_INT <= 32) {
            assertTrue(perms.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
        }

        // Play Creator Policy compliance: no broad/prohibited media or external storage permissions
        assertFalse(perms.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        if (Build.VERSION.SDK_INT >= 33) {
            assertFalse(perms.contains(Manifest.permission.READ_MEDIA_IMAGES))
            assertFalse(perms.contains(Manifest.permission.READ_MEDIA_VIDEO))
        }
    }

    @Test
    fun audioImporterDecodesValidWavAndFailsGracefullyOnBadUri() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val tempDir = File(ctx.cacheDir, "imp_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val wavFile = File(tempDir, "valid_src.wav")
            WavCodec.writeWav(makeSine("src", 330f, 0.2f), wavFile)

            val ok = AudioImporter.importUriAsWav(ctx, Uri.fromFile(wavFile), tempDir, targetSampleRate = 44100)
            assertTrue("Expected WAV URI import to succeed", ok.isSuccess)
            val (buf, outWav) = ok.getOrThrow()
            assertTrue(buf.frameCount > 1000)
            assertTrue(outWav.exists())

            val bad = AudioImporter.importUriAsWav(ctx, Uri.parse("content://nonexistent.provider/bad.mp3"), tempDir)
            assertTrue("Invalid URI must return Result.failure instead of crashing", bad.isFailure)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun masterVolumeAndMuteScaleEnginesAndRoundtripHandoffSnapshot() {
        val playback = AudioPlaybackEngine()
        val studio = StudioEngine()
        try {
            playback.setMasterVolume(0.62f, muted = false)
            studio.setMasterVolume(0.62f, muted = false)
            assertEquals(0.62f, playback.effectiveMasterGain(), 0.001f)
            assertEquals(0.62f, studio.effectiveMasterGain(), 0.001f)

            playback.setMasterVolume(0.62f, muted = true)
            studio.setMasterVolume(0.62f, muted = true)
            assertEquals(0f, playback.effectiveMasterGain(), 0.001f)
            assertEquals(0f, studio.effectiveMasterGain(), 0.001f)

            val snap = SessionHandoffSnapshot(
                senderName = "ANDROID STAGE",
                stationKey = "LIVE",
                masterVolume = 0.48f,
                isMasterMuted = true,
                buffer = makeSine("mix", 220f, 0.1f)
            )
            val wire = snap.encodeToWireBytes()
            val decoded = SessionHandoffSnapshot.decodeFromWireBytes(wire)
            assertNotNull(decoded)
            assertEquals(0.48f, decoded!!.masterVolume, 0.005f)
            assertTrue(decoded.isMasterMuted)
        } finally {
            playback.release()
            studio.release()
        }
    }

    @Test
    fun disbandApiClientSupportsCooperativeCancellation() {
        var polls = 0
        val client = DisbandApiClient(object : DisbandHttpTransport {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?,
                readTimeoutMs: Int
            ): DisbandHttpResponse {
                return if (method == "POST") {
                    DisbandHttpResponse(202, """{"id":"sep_cancel","status":"queued"}""".toByteArray())
                } else {
                    polls++
                    DisbandHttpResponse(200, """{"id":"sep_cancel","status":"processing"}""".toByteArray())
                }
            }
        })

        var cancelled = false
        try {
            client.separateAndDownloadStems(
                baseUrl = "http://127.0.0.1:9999/v1",
                apiKey = "key",
                stems = listOf(DisbandStem.VOCALS),
                sourceBuffer = makeSine("cancel_src", 440f, 0.15f),
                isCancelled = {
                    val c = cancelled
                    cancelled = true
                    c
                }
            )
        } catch (ie: InterruptedException) {
            assertTrue("Expected cooperative cancellation on InterruptedException", polls <= 1)
            return
        }
        throw AssertionError("Expected InterruptedException when cancelled")
    }

    @Test
    fun collabTransmitterEnforcesMax4PeersDiscoversViaBeaconAndRecordsSyncedTracks() {
        val portA = ServerSocket(0).use { it.localPort }
        val portB = ServerSocket(0).use { it.localPort }
        val discoveryPort = DatagramSocket(0).use { it.localPort }

        var syncedCmd: CollabSyncCommand? = null
        val hostNode = NetworkAudioTransmitter(
            onCollabSyncCommandReceived = { syncedCmd = it }
        )
        val guestNode = NetworkAudioTransmitter(
            onCollabSyncCommandReceived = { syncedCmd = it }
        )

        try {
            hostNode.updateLocalPresence("HOST RIG", portA, CollabInstrumentStyle.VOCALS, 0, false)
            guestNode.updateLocalPresence("GUEST GTRS", portB, CollabInstrumentStyle.GUITAR, 1, false)

            hostNode.startListening(NetworkProtocol.UDP, portA)
            guestNode.startListening(NetworkProtocol.UDP, portB)
            hostNode.startDiscovery(discoveryPort)
            Thread.sleep(100)

            // 1) Nearby LAN discovery beacon arrives and populates nearby list
            guestNode.sendDiscoveryBeaconOnce(discoveryPort, explicitTargets = listOf("127.0.0.1" to discoveryPort))
            val discDeadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < discDeadline && hostNode.collabPeers.value.none { it.isNearby }) {
                Thread.sleep(30)
            }
            assertTrue("Expected guest beacon to mark peer nearby", hostNode.collabPeers.value.any { it.isNearby && it.name == "GUEST GTRS" })

            // 2) Connect 4 simultaneous peers; 5th peer must be rejected
            assertTrue(hostNode.connectPeer("127.0.0.1", portB, "Guitar Guest", "ANDROID", CollabInstrumentStyle.GUITAR, 1).isSuccess)
            assertTrue(hostNode.connectPeer("10.0.0.21", 9001, "Bass Phone", "ANDROID", CollabInstrumentStyle.BASS, 2).isSuccess)
            assertTrue(hostNode.connectPeer("10.0.0.22", 9002, "Drums Pad", "LINUX", CollabInstrumentStyle.DRUMS, 3).isSuccess)
            assertTrue(hostNode.connectPeer("10.0.0.23", 9003, "Synth Keys", "LINUX", CollabInstrumentStyle.KEYS_SYNTH, 0).isSuccess)
            assertEquals(MAX_COLLAB_PEERS, hostNode.getConnectedPeers().size)

            val fifth = hostNode.connectPeer("10.0.0.24", 9004, "Extra Fifth", "ANDROID", CollabInstrumentStyle.DJ_FX, 3)
            assertTrue("5th simultaneous collaborator must fail due to 4-device limit", fifth.isFailure)

            // 3) Synchronized SMRC command + SMCL live audio chunk from guest -> host Track 2
            hostNode.beginCollabAudioCapture()
            val guestTarget = guestNode.connectPeer("127.0.0.1", portA, "Host", "ANDROID", CollabInstrumentStyle.VOCALS, 0)
            assertTrue(guestTarget.isSuccess)
            guestNode.sendCollabRecSync(
                CollabSyncCommand(
                    start = true,
                    bpm = 128f,
                    senderPeerId = guestNode.localNodeId,
                    senderName = "GUEST GTRS",
                    senderStyle = CollabInstrumentStyle.GUITAR,
                    senderTrackSlot = 1,
                    takeName = "BAND_SYNC_TEST",
                    senderListenPort = portB
                )
            )
            val sine = makeSine("gtr", 329.63f, 0.25f)
            guestNode.sendCollabLiveChunk(sine.samples, 44100, CollabInstrumentStyle.GUITAR, 1)

            val rxDeadline = System.currentTimeMillis() + 3500
            while (System.currentTimeMillis() < rxDeadline && (syncedCmd == null || hostNode.state.value.rxPackets == 0L)) {
                Thread.sleep(30)
            }
            val cmd = syncedCmd
            assertNotNull(cmd)
            assertTrue(cmd!!.start)
            assertEquals(128f, cmd.bpm, 0.1f)

            val collected = hostNode.stopAndCollectCollabTracks("BAND_SYNC_TEST")
            assertEquals(1, collected.size)
            assertEquals(1, collected[0].first.trackSlot)
            assertTrue(collected[0].second.frameCount > 1000)

            // 4) Known peer serialization round-trip
            val serialized = CollabWireCodec.serializeKnownPeers(hostNode.collabPeers.value)
            val reparsed = CollabWireCodec.parseKnownPeers(serialized)
            assertTrue(reparsed.size >= 4)
        } finally {
            hostNode.release()
            guestNode.release()
        }
    }

    @Test
    fun composeUi_hubMasterVolumeMuteAnd4DeviceCollabFlowWork() {
        composeRule.waitForIdle()

        // 1) Top Toolbar has ever-present Band Collaborator pill; Booth Hub has Master Volume + Band Strip
        composeRule.onNodeWithTag("top_collab_pill").assertIsDisplayed()
        composeRule.onNodeWithTag("hub_master_volume_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("hub_master_mute_button").performScrollTo().assertIsDisplayed()

        // 2) Switch to DECK spoke and verify DockedCircularProductionHub keeps Volume Slider, Mute, and Collab Pill docked on top
        composeRule.onNodeWithTag("nav_tab_deck").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_master_volume_slider").assertIsDisplayed()
        composeRule.onNodeWithTag("hub_master_mute_button").assertIsDisplayed()
        composeRule.onNodeWithTag("hub_collab_pill").assertIsDisplayed()

        composeRule.onNodeWithTag("hub_master_mute_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_master_volume_label").assertTextContains("MUTE")
        composeRule.onNodeWithTag("hub_master_mute_button").performClick()
        composeRule.waitForIdle()

        // 3) Open 4-Device Collab modal from Docked Hub pill
        composeRule.onNodeWithTag("hub_collab_pill").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collab_connect_dialog").assertIsDisplayed()

        // Switch local musician style to DRUMS and link a collaborator peer
        composeRule.onNodeWithTag("collab_my_style_drums").performScrollTo().performClick()
        composeRule.onNodeWithTag("collab_add_host_input").performScrollTo().performTextReplacement("192.168.1.55")
        composeRule.onNodeWithTag("collab_add_port_input").performTextReplacement("9000")
        composeRule.onNodeWithTag("collab_add_connect_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collab_peer_row_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("collab_peer_style_0").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Close dialog & verify ViewModel reflects updated Band Collaborator + Master Volume state
        composeRule.activityRule.scenario.onActivity { act ->
            val vmField = MainActivity::class.java.getDeclaredField("viewModel\$delegate")
            vmField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val vm = (vmField.get(act) as Lazy<SamplfieViewModel>).value
            assertEquals(CollabInstrumentStyle.DRUMS, vm.bandRecState.value.myStyle)
            assertEquals(1, vm.collabPeers.value.count { it.isConnected })
            vm.setShowCollabSheet(false)
            vm.setMasterVolume(0.72f)
            assertEquals(0.72f, vm.rigConfig.value.masterVolume, 0.01f)
        }
        composeRule.waitForIdle()
    }

    private fun makeSine(name: String, hz: Float, sec: Float): AudioBuffer {
        val sr = 44100
        val n = (sr * sec).toInt()
        val samples = FloatArray(n) { i -> (0.5f * sin(2.0 * PI * hz * i / sr)).toFloat() }
        return AudioBuffer(samples, sr, 1, name)
    }
}
