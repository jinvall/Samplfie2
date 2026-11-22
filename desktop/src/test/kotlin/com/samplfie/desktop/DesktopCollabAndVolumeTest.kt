package com.samplfie.desktop

import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.model.NetworkProtocol
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.network.CollabInstrumentStyle
import com.samplfie.desktop.network.CollabSyncCommand
import com.samplfie.desktop.network.CollabWireCodec
import com.samplfie.desktop.network.MAX_COLLAB_PEERS
import com.samplfie.desktop.network.SessionHandoffSnapshot
import com.samplfie.desktop.network.Smp2NetworkNode
import com.samplfie.desktop.stems.DesktopDisbandClient
import com.samplfie.desktop.stems.DesktopDisbandHttpTransport
import com.samplfie.desktop.stems.DesktopDisbandStem
import com.samplfie.desktop.stems.DesktopHttpResponse
import com.samplfie.desktop.stems.DesktopSecretsVault
import com.samplfie.desktop.stems.DesktopStemPreset
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCollabAndVolumeTest {

    @Test
    fun masterVolumeAndMuteSyncAcrossSoundEngineArrangementAndHandoff() {
        val ctrlA = DesktopStudioController()
        val ctrlB = DesktopStudioController()
        val portA = ServerSocket(0).use { it.localPort }
        val portB = ServerSocket(0).use { it.localPort }
        try {
            ctrlA.setMasterVolume(0.45f)
            assertEquals(0.45f, ctrlA.uiState.value.masterVolume, 0.001f)
            assertEquals(0.45f, ctrlA.soundEngine.effectiveMasterGain(), 0.001f)
            assertEquals(0.45f, ctrlA.arrangement.effectiveMasterGain(), 0.001f)

            ctrlA.toggleMasterMute()
            assertTrue(ctrlA.uiState.value.isMasterMuted)
            assertEquals(0f, ctrlA.soundEngine.effectiveMasterGain(), 0.001f)
            assertEquals(0f, ctrlA.arrangement.effectiveMasterGain(), 0.001f)

            // Push SMH2 snapshot and verify Master Volume & Mute arrive cleanly on peer
            ctrlA.updateRigConfig { copy(protocol = NetworkProtocol.TCP, bindPort = portA, txHost = "127.0.0.1", txPort = portB) }
            ctrlB.updateRigConfig { copy(protocol = NetworkProtocol.TCP, bindPort = portB, txHost = "127.0.0.1", txPort = portA) }
            ctrlB.toggleNetworkListener()
            Thread.sleep(100)

            ctrlA.pushSessionToPeer()
            val deadline = System.currentTimeMillis() + 3500
            while (System.currentTimeMillis() < deadline && !ctrlB.uiState.value.isMasterMuted) {
                Thread.sleep(30)
            }
            assertTrue(ctrlB.uiState.value.isMasterMuted)
            assertEquals(0.45f, ctrlB.uiState.value.masterVolume, 0.01f)
            assertEquals(0f, ctrlB.soundEngine.effectiveMasterGain(), 0.001f)

            // Also verify direct SessionHandoffSnapshot codec preservation
            val wire = ctrlA.captureSessionSnapshot().encodeToWireBytes()
            val parsed = SessionHandoffSnapshot.decodeFromWireBytes(wire)!!
            assertEquals(0.45f, parsed.masterVolume, 0.005f)
            assertTrue(parsed.isMasterMuted)
        } finally {
            ctrlA.shutdown()
            ctrlB.shutdown()
        }
    }

    @Test
    fun desktopDisbandSupportsCancellationAllStemsToLooperAndStemWavExport() {
        var polls = 0
        val cancelClient = DesktopDisbandClient(object : DesktopDisbandHttpTransport {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?,
                readTimeoutMs: Int
            ): DesktopHttpResponse {
                return if (method == "POST") {
                    DesktopHttpResponse(202, """{"id":"sep_dt_cancel","status":"queued"}""".toByteArray())
                } else {
                    polls++
                    DesktopHttpResponse(200, """{"id":"sep_dt_cancel","status":"processing"}""".toByteArray())
                }
            }
        })
        var shouldCancel = false
        var interrupted = false
        val tone = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 220.0, 0.2f, 44100, 1)
        try {
            cancelClient.separateAndDownloadStems(
                baseUrl = "http://127.0.0.1:9999/v1",
                apiKey = "key",
                stems = listOf(DesktopDisbandStem.VOCALS),
                sourceBuffer = tone,
                isCancelled = {
                    val c = shouldCancel
                    shouldCancel = true
                    c
                }
            )
        } catch (_: InterruptedException) {
            interrupted = true
        }
        assertTrue("Expected cooperative cancellation on DesktopDisbandClient", interrupted && polls <= 1)

        // Real loopback separation + Looper A..D load + Stem WAV export
        val wavBytes = DesktopWavCodec.encodeToBytes(tone)
        val queries = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/v1/separations") { ex ->
            val path = ex.requestURI.path
            val query = ex.requestURI.rawQuery.orEmpty()
            when {
                ex.requestMethod == "POST" && path == "/v1/separations" -> {
                    ex.requestBody.readBytes()
                    queries += query
                    val b = """{"id":"sep_dt_ok","status":"queued","charge":20}""".toByteArray()
                    ex.sendResponseHeaders(202, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                ex.requestMethod == "GET" && path == "/v1/separations/sep_dt_ok" -> {
                    val b = """{"id":"sep_dt_ok","status":"ready","charge":24,"stems":{"vocals":{"wav":{"url":"http://127.0.0.1:$port/v1/separations/sep_dt_ok/stems/vocals/wav"}},"drums":{"wav":{"url":"http://127.0.0.1:$port/v1/separations/sep_dt_ok/stems/drums/wav"}},"bass":{"wav":{"url":"http://127.0.0.1:$port/v1/separations/sep_dt_ok/stems/bass/wav"}},"guitar":{"wav":{"url":"http://127.0.0.1:$port/v1/separations/sep_dt_ok/stems/guitar/wav"}}}}""".toByteArray()
                    ex.sendResponseHeaders(200, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                else -> {
                    ex.sendResponseHeaders(200, wavBytes.size.toLong())
                    ex.responseBody.use { it.write(wavBytes) }
                }
            }
        }
        server.start()

        val ctrl = DesktopStudioController()
        val tempWav = File.createTempFile("stem_export_", ".wav").apply { delete() }
        try {
            ctrl.saveSecretKey(DesktopSecretsVault.KEY_DISBAND_API_KEY, "dt_key_123")
            ctrl.saveSecretKey(DesktopSecretsVault.KEY_DISBAND_BASE_URL, "http://127.0.0.1:$port/v1")
            ctrl.applyStemPreset(DesktopStemPreset.FOUR_STEM_BAND)
            assertTrue(ctrl.requestStemSeparation())

            val deadline = System.currentTimeMillis() + 5000
            while (ctrl.stemState.value.isRunning && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            assertEquals(4, ctrl.stemState.value.separatedStems.size)

            ctrl.sendAllSeparatedStemsToLooper()
            assertTrue(ctrl.looperState.value.slots.all { it.buffer != null })

            val exported = ctrl.exportSeparatedStemWav("vocals", tempWav)
            assertTrue(exported.isSuccess)
            assertTrue(tempWav.exists() && tempWav.length() > 100)
        } finally {
            tempWav.delete()
            ctrl.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun fourDeviceCollaborationEnforcesCapDiscoversPeersAndLandsBandTracks() {
        val portHost = ServerSocket(0).use { it.localPort }
        val portGuest1 = ServerSocket(0).use { it.localPort }
        val portGuest2 = ServerSocket(0).use { it.localPort }
        val discoveryPort = DatagramSocket(0).use { it.localPort }

        val hostCtrl = DesktopStudioController()
        val guestNode1 = Smp2NetworkNode()
        val guestNode2 = Smp2NetworkNode()

        try {
            hostCtrl.updateRigConfig { copy(protocol = NetworkProtocol.UDP, bindPort = portHost) }
            hostCtrl.toggleNetworkListener()
            hostCtrl.setMyCollabStyle(CollabInstrumentStyle.VOCALS, 0)
            hostCtrl.netNode.startDiscovery(discoveryPort)

            guestNode1.updateLocalPresence("KEYS_LINUX", portGuest1, CollabInstrumentStyle.KEYS_SYNTH, 1, false)
            guestNode1.startListening(NetworkProtocol.UDP, portGuest1)

            guestNode2.updateLocalPresence("BASS_PHONE", portGuest2, CollabInstrumentStyle.BASS, 2, false)
            guestNode2.startListening(NetworkProtocol.UDP, portGuest2)
            Thread.sleep(100)

            // Nearby LAN beacon from guest1 reaches hostCtrl
            guestNode1.sendDiscoveryBeaconOnce(discoveryPort, explicitTargets = listOf("127.0.0.1" to discoveryPort))
            val discDeadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < discDeadline && hostCtrl.collabPeers.value.none { it.isNearby }) {
                Thread.sleep(25)
            }
            assertTrue(hostCtrl.collabPeers.value.any { it.isNearby && it.name == "KEYS_LINUX" })

            // Connect up to 4 simultaneous peers; reject 5th
            assertTrue(hostCtrl.connectCollabPeer("127.0.0.1", portGuest1, "Keys Player", "LINUX", CollabInstrumentStyle.KEYS_SYNTH, 1))
            assertTrue(hostCtrl.connectCollabPeer("127.0.0.1", portGuest2, "Bass Player", "ANDROID", CollabInstrumentStyle.BASS, 2))
            assertTrue(hostCtrl.connectCollabPeer("10.0.0.51", 9001, "Drummer", "ANDROID", CollabInstrumentStyle.DRUMS, 3))
            assertTrue(hostCtrl.connectCollabPeer("10.0.0.52", 9002, "Guitarist", "LINUX", CollabInstrumentStyle.GUITAR, 0))
            assertEquals(MAX_COLLAB_PEERS, hostCtrl.netState.value.connectedPeerCount)
            assertFalse(hostCtrl.connectCollabPeer("10.0.0.53", 9003, "Too Many", "PEER", CollabInstrumentStyle.DJ_FX, 3))

            // Synchronized Live Band Recording captures multi-peer audio onto Timeline tracks 2 & 3
            assertTrue(guestNode1.connectPeer("127.0.0.1", portHost, "Host", "LINUX", CollabInstrumentStyle.VOCALS, 0).isSuccess)
            assertTrue(guestNode2.connectPeer("127.0.0.1", portHost, "Host", "LINUX", CollabInstrumentStyle.VOCALS, 0).isSuccess)
            hostCtrl.startLiveBandRecording(broadcastToPeers = true, syncedTakeName = "BAND_TEST")
            val keysTone = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 440.0, 0.25f, 44100, 1)
            val bassTone = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 110.0, 0.25f, 44100, 1)
            guestNode1.sendCollabLiveChunk(keysTone.samples, 44100, CollabInstrumentStyle.KEYS_SYNTH, 1)
            guestNode2.sendCollabLiveChunk(bassTone.samples, 44100, CollabInstrumentStyle.BASS, 2)

            val rxDeadline = System.currentTimeMillis() + 3500
            while (System.currentTimeMillis() < rxDeadline && hostCtrl.netState.value.rxPackets < 2L) {
                Thread.sleep(30)
            }
            hostCtrl.stopLiveBandRecording(broadcastStop = true)

            val tracks = hostCtrl.timelineState.value.tracks
            assertTrue("Expected Track 2 (Keys) clip from Peer 1", tracks[1].clips.isNotEmpty())
            assertTrue("Expected Track 3 (Bass) clip from Peer 2", tracks[2].clips.isNotEmpty())

            val spec = CollabWireCodec.serializeKnownPeers(hostCtrl.collabPeers.value)
            val parsed = CollabWireCodec.parseKnownPeers(spec)
            assertTrue(parsed.size >= 4)
        } finally {
            guestNode1.release()
            guestNode2.release()
            hostCtrl.shutdown()
        }
    }
}
