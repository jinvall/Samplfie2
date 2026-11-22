package com.example.studio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.audio.AudioBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Generates crisp real-time electronic DJ drum samples (808 Kick, Snare, Closed Hat, Clap)
 * as standard AudioBuffers so the 16-step sequencer and looper work immediately alongside mic takes.
 */
object DrumKitSynthesizer {
    fun kick808(sampleRate: Int = 44100): AudioBuffer {
        val frames = (sampleRate * 0.35f).toInt()
        val samples = FloatArray(frames)
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toFloat() / sampleRate
            val freq = 48f + 130f * exp(-t * 28f)
            phase += 2.0 * PI * freq / sampleRate
            val env = exp(-t * 8.5f)
            samples[i] = (sin(phase) * env * 0.92f).toFloat()
        }
        return AudioBuffer(samples, sampleRate, 1, "808 Kick")
    }

    fun snare909(sampleRate: Int = 44100): AudioBuffer {
        val frames = (sampleRate * 0.22f).toInt()
        val samples = FloatArray(frames)
        val rng = Random(42)
        var lp = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sampleRate
            val body = sin(2.0 * PI * 185.0 * t).toFloat() * exp(-t * 24f)
            val noise = (rng.nextFloat() * 2f - 1f)
            lp += 0.35f * (noise - lp)
            val hpNoise = noise - lp
            val envNoise = exp(-t * 15f)
            samples[i] = (body * 0.55f + hpNoise * envNoise * 0.65f).coerceIn(-1f, 1f)
        }
        return AudioBuffer(samples, sampleRate, 1, "909 Snare")
    }

    fun hiHatClosed(sampleRate: Int = 44100): AudioBuffer {
        val frames = (sampleRate * 0.09f).toInt()
        val samples = FloatArray(frames)
        val rng = Random(99)
        var prev = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sampleRate
            val white = rng.nextFloat() * 2f - 1f
            val hp = white - prev * 0.85f
            prev = white
            val env = exp(-t * 48f)
            samples[i] = (hp * env * 0.6f).coerceIn(-1f, 1f)
        }
        return AudioBuffer(samples, sampleRate, 1, "Hi-Hat")
    }

    fun percZap(sampleRate: Int = 44100): AudioBuffer {
        val frames = (sampleRate * 0.16f).toInt()
        val samples = FloatArray(frames)
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toFloat() / sampleRate
            val freq = 260f + 540f * exp(-t * 32f)
            phase += 2.0 * PI * freq / sampleRate
            val env = exp(-t * 18f)
            samples[i] = (sin(phase) * env * 0.75f).toFloat()
        }
        return AudioBuffer(samples, sampleRate, 1, "Perc Zap")
    }
}

data class LoopSlot(
    val id: Int,
    val label: String,
    val buffer: AudioBuffer? = null,
    val isActive: Boolean = false,
    val isMuted: Boolean = false,
    val bars: Int = 1,
    val volume: Float = 0.85f
)

data class LooperState(
    val slots: List<LoopSlot> = listOf(
        LoopSlot(0, "LOOP A"),
        LoopSlot(1, "LOOP B"),
        LoopSlot(2, "LOOP C"),
        LoopSlot(3, "LOOP D")
    ),
    val isRunning: Boolean = false,
    val bpm: Float = 124f,
    val barProgress: Float = 0f
)

data class SequencerLane(
    val id: Int,
    val name: String,
    val buffer: AudioBuffer,
    val steps: BooleanArray = BooleanArray(16),
    val isMuted: Boolean = false,
    val volume: Float = 0.85f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SequencerLane) return false
        return id == other.id &&
            name == other.name &&
            buffer == other.buffer &&
            isMuted == other.isMuted &&
            volume == other.volume &&
            steps.contentEquals(other.steps)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + name.hashCode()
        result = 31 * result + buffer.hashCode()
        result = 31 * result + steps.contentHashCode()
        result = 31 * result + isMuted.hashCode()
        result = 31 * result + volume.hashCode()
        return result
    }
}

data class SequencerState(
    val lanes: List<SequencerLane> = defaultLanes(),
    val isPlaying: Boolean = false,
    val currentStep: Int = 0,
    val bpm: Float = 124f,
    val swing: Float = 0.08f
) {
    companion object {
        fun defaultLanes(): List<SequencerLane> {
            val kickSteps = BooleanArray(16) { it % 4 == 0 }
            val snareSteps = BooleanArray(16) { it == 4 || it == 12 }
            val hatSteps = BooleanArray(16) { it % 2 == 1 }
            val sampleSteps = BooleanArray(16) { it == 0 || it == 10 }
            return listOf(
                SequencerLane(0, "SAMPLE", DrumKitSynthesizer.percZap(), sampleSteps, volume = 0.9f),
                SequencerLane(1, "KICK 808", DrumKitSynthesizer.kick808(), kickSteps, volume = 0.95f),
                SequencerLane(2, "SNARE 909", DrumKitSynthesizer.snare909(), snareSteps, volume = 0.85f),
                SequencerLane(3, "HI-HAT", DrumKitSynthesizer.hiHatClosed(), hatSteps, volume = 0.7f)
            )
        }
    }
}

data class TimelineClip(
    val id: String,
    val label: String,
    val buffer: AudioBuffer,
    val startTimeSec: Float
)

data class TimelineTrack(
    val id: Int,
    val name: String,
    val clips: List<TimelineClip> = emptyList(),
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val gain: Float = 0.9f
)

data class TimelineState(
    val tracks: List<TimelineTrack> = listOf(
        TimelineTrack(0, "TRACK 1 · VOCAL / MIC"),
        TimelineTrack(1, "TRACK 2 · LOOPER MIX"),
        TimelineTrack(2, "TRACK 3 · SEQ BEAT"),
        TimelineTrack(3, "TRACK 4 · FX / DUB")
    ),
    val isPlaying: Boolean = false,
    val playheadSec: Float = 0f,
    val totalDurationSec: Float = 8.0f
)

data class SyncedPlayheadState(
    val isPlaying: Boolean = false,
    val bpm: Float = 124f,
    val playheadSec: Float = 0f,
    val bar: Int = 1,
    val beat: Int = 1,
    val phaseFraction: Float = 0f
)

/**
 * Pure Kotlin mixing engine for Looper slots, 16-Step Sequencer patterns, and Multi-Track Timeline arrangements,
 * plus live AudioTrack playback for Sequencer, Looper, and Timeline with real-time playhead tracking.
 */
class StudioEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _looperState = MutableStateFlow(LooperState())
    val looperState: StateFlow<LooperState> = _looperState.asStateFlow()

    private val _sequencerState = MutableStateFlow(SequencerState())
    val sequencerState: StateFlow<SequencerState> = _sequencerState.asStateFlow()

    private val _timelineState = MutableStateFlow(TimelineState())
    val timelineState: StateFlow<TimelineState> = _timelineState.asStateFlow()

    private val _syncedPlayhead = MutableStateFlow(SyncedPlayheadState())
    val syncedPlayhead: StateFlow<SyncedPlayheadState> = _syncedPlayhead.asStateFlow()

    private var seqJob: Job? = null
    private var looperJob: Job? = null
    private var timelineJob: Job? = null
    private var masterSyncJob: Job? = null

    private val seqPlayingFlag = AtomicBoolean(false)
    private val looperPlayingFlag = AtomicBoolean(false)
    private val timelinePlayingFlag = AtomicBoolean(false)
    private val masterSyncFlag = AtomicBoolean(false)

    @Volatile
    private var masterGain = 0.85f

    @Volatile
    private var masterMuted = false

    fun setMasterVolume(volume: Float, muted: Boolean) {
        masterGain = volume.coerceIn(0f, 1f)
        masterMuted = muted
    }

    fun effectiveMasterGain(): Float = if (masterMuted) 0f else masterGain

    // --- LOOPER ---

    fun assignBufferToLoopSlot(slotId: Int, buffer: AudioBuffer, label: String = buffer.name) {
        val updated = _looperState.value.slots.map { slot ->
            if (slot.id == slotId) {
                slot.copy(buffer = buffer, label = label.take(16), isActive = true, isMuted = false)
            } else {
                slot
            }
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun toggleLoopSlotActive(slotId: Int) {
        val updated = _looperState.value.slots.map { slot ->
            if (slot.id == slotId && slot.buffer != null) slot.copy(isActive = !slot.isActive) else slot
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun setLoopSlotActive(slotId: Int, active: Boolean) {
        val updated = _looperState.value.slots.map { slot ->
            if (slot.id == slotId) slot.copy(isActive = active && slot.buffer != null) else slot
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun toggleLoopSlotMute(slotId: Int) {
        val updated = _looperState.value.slots.map { slot ->
            if (slot.id == slotId) slot.copy(isMuted = !slot.isMuted) else slot
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun clearLoopSlot(slotId: Int) {
        val updated = _looperState.value.slots.map { slot ->
            if (slot.id == slotId) LoopSlot(slot.id, "LOOP ${'A' + slot.id}") else slot
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun setLoopSlotBars(slotId: Int, bars: Int) {
        val updated = _looperState.value.slots.map { slot ->
            if (slot.id == slotId) slot.copy(bars = bars.coerceIn(1, 4)) else slot
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun setBpm(bpm: Float) {
        val clamped = bpm.coerceIn(60f, 180f)
        _looperState.value = _looperState.value.copy(bpm = clamped)
        _sequencerState.value = _sequencerState.value.copy(bpm = clamped)
        _syncedPlayhead.value = _syncedPlayhead.value.copy(bpm = clamped)
    }

    fun seekSyncedPlayhead(sec: Float) {
        val maxSec = _timelineState.value.totalDurationSec.coerceAtLeast(4f)
        val clamped = sec.coerceIn(0f, maxSec)
        val bpm = _syncedPlayhead.value.bpm.coerceIn(60f, 180f)
        val beatSec = 60f / bpm
        val totalBeats = (clamped / beatSec).toInt()
        val bar = (totalBeats / 4) + 1
        val beat = (totalBeats % 4) + 1
        val phase = ((clamped % (beatSec * 4f)) / (beatSec * 4f)).coerceIn(0f, 1f)
        val step = ((clamped / (beatSec / 4f)).toInt() % 16).coerceIn(0, 15)

        _timelineState.value = _timelineState.value.copy(playheadSec = clamped)
        _looperState.value = _looperState.value.copy(barProgress = phase)
        _sequencerState.value = _sequencerState.value.copy(currentStep = step)
        _syncedPlayhead.value = _syncedPlayhead.value.copy(
            playheadSec = clamped,
            bar = bar,
            beat = beat,
            phaseFraction = phase
        )
    }

    fun toggleSyncedTransport() {
        if (masterSyncFlag.get()) {
            stopSyncedTransport()
        } else {
            startSyncedTransport()
        }
    }

    fun startSyncedTransport() {
        stopSyncedTransport()
        masterSyncFlag.set(true)
        _syncedPlayhead.value = _syncedPlayhead.value.copy(isPlaying = true)
        startLooper()
        startSequencer()
        if (_timelineState.value.tracks.any { it.clips.isNotEmpty() }) {
            startTimeline()
        }
        masterSyncJob = scope.launch {
            val startPlayhead = _syncedPlayhead.value.playheadSec
            var elapsed = 0f
            val tickMs = 40L
            while (masterSyncFlag.get() && isActive) {
                delay(tickMs)
                elapsed += tickMs / 1000f
                val tlSec = if (timelinePlayingFlag.get()) {
                    _timelineState.value.playheadSec
                } else {
                    val total = _timelineState.value.totalDurationSec.coerceAtLeast(8f)
                    (startPlayhead + elapsed) % total
                }
                val bpm = _syncedPlayhead.value.bpm.coerceIn(60f, 180f)
                val beatSec = 60f / bpm
                val barDur = beatSec * 4f
                val totalBeats = (tlSec / beatSec).toInt()
                val phase = ((tlSec % barDur) / barDur).coerceIn(0f, 1f)
                _syncedPlayhead.value = _syncedPlayhead.value.copy(
                    isPlaying = true,
                    playheadSec = tlSec,
                    bar = (totalBeats / 4) + 1,
                    beat = (totalBeats % 4) + 1,
                    phaseFraction = phase
                )
            }
        }
    }

    fun stopSyncedTransport() {
        masterSyncFlag.set(false)
        masterSyncJob?.cancel()
        masterSyncJob = null
        stopLooper()
        stopSequencer()
        stopTimeline()
        _syncedPlayhead.value = _syncedPlayhead.value.copy(isPlaying = false)
    }

    fun toggleLooperPlayback() {
        if (looperPlayingFlag.get()) {
            stopLooper()
        } else {
            startLooper()
        }
    }

    fun startLooper() {
        stopLooper()
        looperPlayingFlag.set(true)
        _looperState.value = _looperState.value.copy(isRunning = true)

        looperJob = scope.launch {
            val sr = 44100
            val bufBytes = AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            var track: AudioTrack? = null
            try {
                track = buildMonoTrack(sr, bufBytes)
                track.play()
                val chunkFrames = 512
                var globalFrame = 0L

                while (looperPlayingFlag.get() && isActive) {
                    val state = _looperState.value
                    val barFrames = max(1, ((60f / state.bpm) * 4f * sr).toInt())
                    val chunk = FloatArray(chunkFrames)

                    for (slot in state.slots) {
                        val buf = slot.buffer ?: continue
                        if (!slot.isActive || slot.isMuted || buf.frameCount == 0) continue
                        val resampled = if (buf.sampleRate != sr || buf.channels != 1) {
                            buf.convertChannels(1).resample(sr)
                        } else {
                            buf
                        }
                        val slotCycleFrames = barFrames * slot.bars
                        val srcFrames = resampled.frameCount
                        for (i in 0 until chunkFrames) {
                            val posInCycle = ((globalFrame + i) % slotCycleFrames).toInt()
                            if (posInCycle < srcFrames) {
                                chunk[i] += resampled.samples[posInCycle] * slot.volume
                            }
                        }
                    }

                    val gain = effectiveMasterGain()
                    val shorts = ShortArray(chunkFrames) { i ->
                        ((chunk[i] * gain).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    track.write(shorts, 0, shorts.size)
                    globalFrame += chunkFrames
                    val progress = ((globalFrame % barFrames).toFloat() / barFrames.toFloat()).coerceIn(0f, 1f)
                    _looperState.value = _looperState.value.copy(barProgress = progress)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { track?.pause(); track?.flush(); track?.release() }
                looperPlayingFlag.set(false)
                _looperState.value = _looperState.value.copy(isRunning = false, barProgress = 0f)
            }
        }
    }

    fun stopLooper() {
        looperPlayingFlag.set(false)
        looperJob?.cancel()
        looperJob = null
        _looperState.value = _looperState.value.copy(isRunning = false, barProgress = 0f)
    }

    fun bounceLooperMixdown(sampleRate: Int = 44100): AudioBuffer {
        val state = _looperState.value
        val maxBars = state.slots.filter { it.isActive && !it.isMuted && it.buffer != null }.maxOfOrNull { it.bars } ?: 1
        val barFrames = ((60f / state.bpm) * 4f * sampleRate).toInt()
        val totalFrames = max(1, barFrames * maxBars)
        val mix = FloatArray(totalFrames)

        for (slot in state.slots) {
            val raw = slot.buffer ?: continue
            if (!slot.isActive || slot.isMuted || raw.frameCount == 0) continue
            val mono = raw.convertChannels(1).resample(sampleRate)
            val cycleFrames = barFrames * slot.bars
            for (f in 0 until totalFrames) {
                val pos = f % cycleFrames
                if (pos < mono.frameCount) {
                    mix[f] += mono.samples[pos] * slot.volume
                }
            }
        }
        for (i in mix.indices) mix[i] = mix[i].coerceIn(-1f, 1f)
        return AudioBuffer(mix, sampleRate, 1, "Looper Mix (${state.bpm.toInt()} BPM)")
    }

    // --- 16-STEP SEQUENCER ---

    fun setSequencerSampleForLane(laneId: Int, buffer: AudioBuffer) {
        val updated = _sequencerState.value.lanes.map { lane ->
            if (lane.id == laneId) lane.copy(buffer = buffer, name = buffer.name.take(12)) else lane
        }
        _sequencerState.value = _sequencerState.value.copy(lanes = updated)
    }

    fun toggleSequencerStep(laneId: Int, stepIndex: Int) {
        if (stepIndex !in 0 until 16) return
        val updated = _sequencerState.value.lanes.map { lane ->
            if (lane.id == laneId) {
                val copySteps = lane.steps.copyOf()
                copySteps[stepIndex] = !copySteps[stepIndex]
                lane.copy(steps = copySteps)
            } else {
                lane
            }
        }
        _sequencerState.value = _sequencerState.value.copy(lanes = updated)
    }

    fun applySequencerPatternMask(laneId: Int, stepMask: Int) {
        val updated = _sequencerState.value.lanes.map { lane ->
            if (lane.id == laneId) {
                val copySteps = BooleanArray(16) { i -> ((stepMask shr i) and 1) == 1 }
                lane.copy(steps = copySteps)
            } else {
                lane
            }
        }
        _sequencerState.value = _sequencerState.value.copy(lanes = updated)
    }

    fun toggleSequencerLaneMute(laneId: Int) {
        val updated = _sequencerState.value.lanes.map { lane ->
            if (lane.id == laneId) lane.copy(isMuted = !lane.isMuted) else lane
        }
        _sequencerState.value = _sequencerState.value.copy(lanes = updated)
    }

    fun setSequencerSwing(swing: Float) {
        _sequencerState.value = _sequencerState.value.copy(swing = swing.coerceIn(0f, 0.45f))
    }

    fun toggleSequencerPlayback() {
        if (seqPlayingFlag.get()) stopSequencer() else startSequencer()
    }

    fun startSequencer() {
        stopSequencer()
        seqPlayingFlag.set(true)
        _sequencerState.value = _sequencerState.value.copy(isPlaying = true)

        seqJob = scope.launch {
            val sr = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            var track: AudioTrack? = null
            try {
                track = buildMonoTrack(sr, minBuf)
                track.play()
                var step = 0

                while (seqPlayingFlag.get() && isActive) {
                    val state = _sequencerState.value
                    _sequencerState.value = state.copy(currentStep = step)

                    val baseStepSec = 60f / state.bpm / 4f
                    val swingFactor = if (step % 2 == 1) 1f + state.swing else 1f - state.swing * 0.5f
                    val stepFrames = max(256, (baseStepSec * swingFactor * sr).toInt())
                    val stepAudio = FloatArray(stepFrames)

                    for (lane in state.lanes) {
                        if (!lane.isMuted && lane.steps[step]) {
                            val mono = if (lane.buffer.sampleRate != sr || lane.buffer.channels != 1) {
                                lane.buffer.convertChannels(1).resample(sr)
                            } else {
                                lane.buffer
                            }
                            val copyFrames = min(stepFrames, mono.frameCount)
                            for (i in 0 until copyFrames) {
                                stepAudio[i] += mono.samples[i] * lane.volume
                            }
                        }
                    }

                    val gain = effectiveMasterGain()
                    val shorts = ShortArray(stepFrames) { i ->
                        ((stepAudio[i] * gain).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    track.write(shorts, 0, shorts.size)
                    step = (step + 1) % 16
                }
            } catch (_: Exception) {
            } finally {
                runCatching { track?.pause(); track?.flush(); track?.release() }
                seqPlayingFlag.set(false)
                _sequencerState.value = _sequencerState.value.copy(isPlaying = false, currentStep = 0)
            }
        }
    }

    fun stopSequencer() {
        seqPlayingFlag.set(false)
        seqJob?.cancel()
        seqJob = null
        _sequencerState.value = _sequencerState.value.copy(isPlaying = false, currentStep = 0)
    }

    fun renderSequenceToBuffer(bars: Int = 1, sampleRate: Int = 44100): AudioBuffer {
        val state = _sequencerState.value
        val stepFrames = max(256, ((60f / state.bpm / 4f) * sampleRate).toInt())
        val totalSteps = 16 * bars.coerceIn(1, 4)
        val totalFrames = stepFrames * totalSteps
        val mix = FloatArray(totalFrames)

        for (stepIdx in 0 until totalSteps) {
            val patternStep = stepIdx % 16
            val startFrame = stepIdx * stepFrames
            for (lane in state.lanes) {
                if (!lane.isMuted && lane.steps[patternStep]) {
                    val mono = lane.buffer.convertChannels(1).resample(sampleRate)
                    val endFrame = min(totalFrames, startFrame + mono.frameCount)
                    for (f in startFrame until endFrame) {
                        mix[f] += mono.samples[f - startFrame] * lane.volume
                    }
                }
            }
        }
        for (i in mix.indices) mix[i] = mix[i].coerceIn(-1f, 1f)
        return AudioBuffer(mix, sampleRate, 1, "Seq Beat (${state.bpm.toInt()} BPM)")
    }

    // --- MULTI-TRACK TIMELINE WITH PLAYHEAD ---

    fun addClipToTimeline(trackId: Int, buffer: AudioBuffer, startTimeSec: Float) {
        val clip = TimelineClip(
            id = "clip_${System.nanoTime()}",
            label = buffer.name.take(18),
            buffer = buffer,
            startTimeSec = startTimeSec.coerceAtLeast(0f)
        )
        val updatedTracks = _timelineState.value.tracks.map { track ->
            if (track.id == trackId) track.copy(clips = track.clips + clip) else track
        }
        val maxEnd = updatedTracks.flatMap { it.clips }.maxOfOrNull { it.startTimeSec + it.buffer.durationSeconds } ?: 8f
        _timelineState.value = _timelineState.value.copy(
            tracks = updatedTracks,
            totalDurationSec = max(8f, maxEnd + 1f)
        )
    }

    fun moveTimelineClip(trackId: Int, clipId: String, newStartSec: Float) {
        val updatedTracks = _timelineState.value.tracks.map { track ->
            if (track.id == trackId) {
                track.copy(
                    clips = track.clips.map { clip ->
                        if (clip.id == clipId) clip.copy(startTimeSec = newStartSec.coerceAtLeast(0f)) else clip
                    }
                )
            } else {
                track
            }
        }
        val maxEnd = updatedTracks.flatMap { it.clips }.maxOfOrNull { it.startTimeSec + it.buffer.durationSeconds } ?: 8f
        _timelineState.value = _timelineState.value.copy(
            tracks = updatedTracks,
            totalDurationSec = max(8f, maxEnd + 1f)
        )
    }

    fun removeTimelineClip(trackId: Int, clipId: String) {
        val updatedTracks = _timelineState.value.tracks.map { track ->
            if (track.id == trackId) {
                track.copy(clips = track.clips.filterNot { it.id == clipId })
            } else {
                track
            }
        }
        _timelineState.value = _timelineState.value.copy(tracks = updatedTracks)
    }

    fun toggleTimelineTrackMute(trackId: Int) {
        val updatedTracks = _timelineState.value.tracks.map { track ->
            if (track.id == trackId) track.copy(isMuted = !track.isMuted) else track
        }
        _timelineState.value = _timelineState.value.copy(tracks = updatedTracks)
    }

    fun toggleTimelineTrackSolo(trackId: Int) {
        val updatedTracks = _timelineState.value.tracks.map { track ->
            if (track.id == trackId) track.copy(isSolo = !track.isSolo) else track
        }
        _timelineState.value = _timelineState.value.copy(tracks = updatedTracks)
    }

    fun seekTimelinePlayhead(sec: Float) {
        val maxSec = _timelineState.value.totalDurationSec
        _timelineState.value = _timelineState.value.copy(playheadSec = sec.coerceIn(0f, maxSec))
    }

    fun toggleTimelinePlayback() {
        if (timelinePlayingFlag.get()) stopTimeline() else startTimeline()
    }

    fun startTimeline() {
        stopTimeline()
        val mixdown = mixdownTimeline(sampleRate = 44100)
        if (mixdown.frameCount == 0) return

        timelinePlayingFlag.set(true)
        _timelineState.value = _timelineState.value.copy(isPlaying = true)

        timelineJob = scope.launch {
            val sr = mixdown.sampleRate
            val minBuf = AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            var track: AudioTrack? = null
            try {
                track = buildMonoTrack(sr, minBuf)
                track.play()
                val chunkFrames = 1024
                var cursorFrame = (_timelineState.value.playheadSec * sr).toInt()
                    .coerceIn(0, max(0, mixdown.frameCount - 1))

                while (timelinePlayingFlag.get() && isActive && cursorFrame < mixdown.frameCount) {
                    val count = min(chunkFrames, mixdown.frameCount - cursorFrame)
                    val gain = effectiveMasterGain()
                    val shorts = ShortArray(count) { i ->
                        ((mixdown.samples[cursorFrame + i] * gain).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    track.write(shorts, 0, shorts.size)
                    cursorFrame += count
                    _timelineState.value = _timelineState.value.copy(
                        playheadSec = cursorFrame.toFloat() / sr.toFloat()
                    )
                }
            } catch (_: Exception) {
            } finally {
                runCatching { track?.pause(); track?.flush(); track?.release() }
                timelinePlayingFlag.set(false)
                _timelineState.value = _timelineState.value.copy(isPlaying = false)
            }
        }
    }

    fun stopTimeline() {
        timelinePlayingFlag.set(false)
        timelineJob?.cancel()
        timelineJob = null
        _timelineState.value = _timelineState.value.copy(isPlaying = false, playheadSec = 0f)
    }

    fun mixdownTimeline(sampleRate: Int = 44100): AudioBuffer {
        val state = _timelineState.value
        val hasSolo = state.tracks.any { it.isSolo }
        val activeTracks = state.tracks.filter { track ->
            !track.isMuted && (!hasSolo || track.isSolo) && track.clips.isNotEmpty()
        }
        if (activeTracks.isEmpty()) {
            return AudioBuffer(FloatArray(0), sampleRate, 1, "Timeline Empty")
        }
        val maxEndSec = activeTracks.flatMap { it.clips }
            .maxOfOrNull { it.startTimeSec + it.buffer.durationSeconds } ?: 1f
        val totalFrames = max(1, (maxEndSec * sampleRate).toInt())
        val mix = FloatArray(totalFrames)

        for (track in activeTracks) {
            for (clip in track.clips) {
                val mono = clip.buffer.convertChannels(1).resample(sampleRate)
                val startFrame = (clip.startTimeSec * sampleRate).toInt().coerceAtLeast(0)
                val endFrame = min(totalFrames, startFrame + mono.frameCount)
                for (f in startFrame until endFrame) {
                    mix[f] += mono.samples[f - startFrame] * track.gain
                }
            }
        }
        for (i in mix.indices) mix[i] = mix[i].coerceIn(-1f, 1f)
        return AudioBuffer(mix, sampleRate, 1, "Timeline Mixdown")
    }

    private fun buildMonoTrack(sampleRate: Int, bufferBytes: Int): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun release() {
        stopSyncedTransport()
        stopLooper()
        stopSequencer()
        stopTimeline()
    }
}
