package com.samplfie.desktop.studio

import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.audio.WaveformEnvelope
import com.samplfie.desktop.model.DesktopAudioBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DesktopDrumKit {
    fun kick808(sampleRate: Int = 44100): DesktopAudioBuffer {
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
        return DesktopAudioBuffer(samples, sampleRate, 1, "808 Kick")
    }

    fun snare909(sampleRate: Int = 44100): DesktopAudioBuffer {
        val frames = (sampleRate * 0.22f).toInt()
        val samples = FloatArray(frames)
        val rng = Random(42)
        var lp = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sampleRate
            val body = sin(2.0 * PI * 185.0 * t).toFloat() * exp(-t * 24f)
            val noise = rng.nextFloat() * 2f - 1f
            lp += 0.35f * (noise - lp)
            val hpNoise = noise - lp
            val envNoise = exp(-t * 15f)
            samples[i] = (body * 0.55f + hpNoise * envNoise * 0.65f).coerceIn(-1f, 1f)
        }
        return DesktopAudioBuffer(samples, sampleRate, 1, "909 Snare")
    }

    fun hiHatClosed(sampleRate: Int = 44100): DesktopAudioBuffer {
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
        return DesktopAudioBuffer(samples, sampleRate, 1, "Hi-Hat")
    }

    fun percZap(sampleRate: Int = 44100): DesktopAudioBuffer {
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
        return DesktopAudioBuffer(samples, sampleRate, 1, "Perc Zap")
    }
}

data class DesktopLoopSlot(
    val id: Int,
    val label: String,
    val buffer: DesktopAudioBuffer? = null,
    val isActive: Boolean = false,
    val isMuted: Boolean = false,
    val bars: Int = 1,
    val volume: Float = 0.85f,
    val isSampleLocked: Boolean = false
)

data class DesktopLooperState(
    val slots: List<DesktopLoopSlot> = listOf(
        DesktopLoopSlot(0, "LOOP A"),
        DesktopLoopSlot(1, "LOOP B"),
        DesktopLoopSlot(2, "LOOP C"),
        DesktopLoopSlot(3, "LOOP D")
    ),
    val isRunning: Boolean = false,
    val bpm: Float = 124f,
    val barProgress: Float = 0f
)

data class DesktopLoopRefinerState(
    val selectedSlotId: Int = 0,
    val rawSlotBuffer: DesktopAudioBuffer? = null,
    val workingBuffer: DesktopAudioBuffer? = null,
    val envelope: WaveformEnvelope = WaveformEnvelope(FloatArray(0), FloatArray(0), FloatArray(0)),
    val loopStartFrac: Float = 0f,
    val loopEndFrac: Float = 1f,
    val fadeInMs: Float = 8f,
    val fadeOutMs: Float = 12f,
    val isReversed: Boolean = false,
    val statusNote: String = "LOAD A LOOP SLOT OR SEND DECK AUDIO TO REFINE"
)

data class DesktopSequencerLane(
    val id: Int,
    val name: String,
    val buffer: DesktopAudioBuffer,
    val steps: BooleanArray = BooleanArray(16),
    val isMuted: Boolean = false,
    val volume: Float = 0.85f,
    val isSampleLocked: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DesktopSequencerLane) return false
        return id == other.id && name == other.name && buffer == other.buffer &&
            isMuted == other.isMuted && volume == other.volume &&
            isSampleLocked == other.isSampleLocked && steps.contentEquals(other.steps)
    }

    override fun hashCode(): Int {
        var r = id
        r = 31 * r + name.hashCode()
        r = 31 * r + buffer.hashCode()
        r = 31 * r + steps.contentHashCode()
        r = 31 * r + isMuted.hashCode()
        r = 31 * r + volume.hashCode()
        r = 31 * r + isSampleLocked.hashCode()
        return r
    }
}

data class DesktopSequencerState(
    val lanes: List<DesktopSequencerLane> = defaultLanes(),
    val isPlaying: Boolean = false,
    val currentStep: Int = 0,
    val bpm: Float = 124f,
    val swing: Float = 0.08f,
    val companionOverdubRx: Boolean = true,
    val companionStepCursor: Int = 0
) {
    companion object {
        fun defaultLanes(): List<DesktopSequencerLane> {
            val kickSteps = BooleanArray(16) { it % 4 == 0 }
            val snareSteps = BooleanArray(16) { it == 4 || it == 12 }
            val hatSteps = BooleanArray(16) { it % 2 == 1 }
            val sampleSteps = BooleanArray(16) { it == 0 || it == 10 }
            return listOf(
                DesktopSequencerLane(0, "SAMPLE", DesktopDrumKit.percZap(), sampleSteps, volume = 0.9f),
                DesktopSequencerLane(1, "KICK 808", DesktopDrumKit.kick808(), kickSteps, volume = 0.95f),
                DesktopSequencerLane(2, "SNARE 909", DesktopDrumKit.snare909(), snareSteps, volume = 0.85f),
                DesktopSequencerLane(3, "HI-HAT", DesktopDrumKit.hiHatClosed(), hatSteps, volume = 0.7f)
            )
        }
    }
}

data class DesktopTimelineClip(
    val id: String,
    val label: String,
    val buffer: DesktopAudioBuffer,
    val startTimeSec: Float
)

data class DesktopTimelineTrack(
    val id: Int,
    val name: String,
    val clips: List<DesktopTimelineClip> = emptyList(),
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val gain: Float = 0.9f
)

data class DesktopTimelineState(
    val tracks: List<DesktopTimelineTrack> = listOf(
        DesktopTimelineTrack(0, "TRACK 1 · VOCAL / MIC"),
        DesktopTimelineTrack(1, "TRACK 2 · LOOPER MIX"),
        DesktopTimelineTrack(2, "TRACK 3 · SEQ BEAT"),
        DesktopTimelineTrack(3, "TRACK 4 · FX / DUB")
    ),
    val isPlaying: Boolean = false,
    val playheadSec: Float = 0f,
    val totalDurationSec: Float = 8.0f
)

data class DesktopSyncedPlayheadState(
    val isPlaying: Boolean = false,
    val bpm: Float = 124f,
    val playheadSec: Float = 0f,
    val bar: Int = 1,
    val beat: Int = 1,
    val phaseFraction: Float = 0f
)

/**
 * Full Looper, Loop Sample Refinement, 16-Step Sequencer, and Multi-Track Synced Timeline Engine
 * for Samplfie2 Desktop, achieving 1:1 parity with Android's StudioEngine.
 */
class DesktopArrangementEngine {

    private val _looperState = MutableStateFlow(DesktopLooperState())
    val looperState: StateFlow<DesktopLooperState> = _looperState.asStateFlow()

    private val _loopRefinerState = MutableStateFlow(DesktopLoopRefinerState())
    val loopRefinerState: StateFlow<DesktopLoopRefinerState> = _loopRefinerState.asStateFlow()

    private val _sequencerState = MutableStateFlow(DesktopSequencerState())
    val sequencerState: StateFlow<DesktopSequencerState> = _sequencerState.asStateFlow()

    private val _timelineState = MutableStateFlow(DesktopTimelineState())
    val timelineState: StateFlow<DesktopTimelineState> = _timelineState.asStateFlow()

    private val _syncedPlayhead = MutableStateFlow(DesktopSyncedPlayheadState())
    val syncedPlayhead: StateFlow<DesktopSyncedPlayheadState> = _syncedPlayhead.asStateFlow()

    private val looperRunning = AtomicBoolean(false)
    private val seqRunning = AtomicBoolean(false)
    private val timelineRunning = AtomicBoolean(false)
    private val masterSyncRunning = AtomicBoolean(false)

    private var looperThread: Thread? = null
    private var seqThread: Thread? = null
    private var timelineThread: Thread? = null
    private var syncThread: Thread? = null

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

    fun assignBufferToLoopSlot(slotId: Int, buffer: DesktopAudioBuffer, label: String = buffer.name) {
        val frozen = buffer.copy(samples = buffer.samples.copyOf())
        val updated = _looperState.value.slots.map { s ->
            if (s.id == slotId) {
                s.copy(
                    buffer = frozen,
                    label = label.take(16),
                    isActive = true,
                    isMuted = false,
                    isSampleLocked = true
                )
            } else s
        }
        _looperState.value = _looperState.value.copy(slots = updated)
        loadLoopSlotIntoRefiner(slotId)
    }

    fun toggleLoopSlotLock(slotId: Int) {
        val updated = _looperState.value.slots.map { s ->
            if (s.id == slotId && s.buffer != null) s.copy(isSampleLocked = !s.isSampleLocked) else s
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun toggleLoopSlotActive(slotId: Int) {
        val updated = _looperState.value.slots.map { s ->
            if (s.id == slotId && s.buffer != null) s.copy(isActive = !s.isActive) else s
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun setLoopSlotActive(slotId: Int, active: Boolean) {
        val updated = _looperState.value.slots.map { s ->
            if (s.id == slotId) s.copy(isActive = active && s.buffer != null) else s
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun toggleLoopSlotMute(slotId: Int) {
        val updated = _looperState.value.slots.map { s ->
            if (s.id == slotId) s.copy(isMuted = !s.isMuted) else s
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun clearLoopSlot(slotId: Int) {
        val updated = _looperState.value.slots.map { s ->
            if (s.id == slotId) DesktopLoopSlot(s.id, "LOOP ${'A' + s.id}") else s
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun setLoopSlotBars(slotId: Int, bars: Int) {
        val updated = _looperState.value.slots.map { s ->
            if (s.id == slotId) s.copy(bars = bars.coerceIn(1, 4)) else s
        }
        _looperState.value = _looperState.value.copy(slots = updated)
    }

    fun loadLoopSlotIntoRefiner(slotId: Int, fallbackBuffer: DesktopAudioBuffer? = null) {
        val curFadesIn = _loopRefinerState.value.fadeInMs
        val curFadesOut = _loopRefinerState.value.fadeOutMs
        val slot = _looperState.value.slots.firstOrNull { it.id == slotId }
        val buf = slot?.buffer ?: fallbackBuffer
        if (buf == null) {
            _loopRefinerState.value = DesktopLoopRefinerState(
                selectedSlotId = slotId,
                fadeInMs = curFadesIn,
                fadeOutMs = curFadesOut,
                statusNote = "SLOT ${'A' + slotId} EMPTY — LOAD AUDIO FROM DECK"
            )
            return
        }
        val frozen = buf.copy(samples = buf.samples.copyOf())
        if (slot?.buffer == null) {
            val updated = _looperState.value.slots.map {
                if (it.id == slotId) it.copy(buffer = frozen, label = frozen.name.take(16), isActive = true, isSampleLocked = true) else it
            }
            _looperState.value = _looperState.value.copy(slots = updated)
        }
        _loopRefinerState.value = DesktopLoopRefinerState(
            selectedSlotId = slotId,
            rawSlotBuffer = frozen,
            workingBuffer = frozen.copy(samples = frozen.samples.copyOf()),
            envelope = SignalAnalyzer.computeWaveformEnvelope(frozen, 110),
            loopStartFrac = 0f,
            loopEndFrac = 1f,
            fadeInMs = curFadesIn,
            fadeOutMs = curFadesOut,
            statusNote = "REFINING LOOP ${'A' + slotId} (${"%.2f".format(frozen.durationSeconds)}s)"
        )
    }

    fun setLoopRefinerFades(fadeInMs: Float, fadeOutMs: Float) {
        _loopRefinerState.value = _loopRefinerState.value.copy(
            fadeInMs = fadeInMs.coerceIn(0f, 2000f),
            fadeOutMs = fadeOutMs.coerceIn(0f, 2000f)
        )
    }

    fun updateLoopRefinerSelection(start: Float, end: Float) {
        val lo = minOf(start, end).coerceIn(0f, 0.98f)
        val hi = maxOf(start, end).coerceIn(lo + 0.02f, 1f)
        _loopRefinerState.value = _loopRefinerState.value.copy(loopStartFrac = lo, loopEndFrac = hi)
    }

    fun snapLoopSelectionZeroCross() {
        val st = _loopRefinerState.value
        val cur = st.workingBuffer ?: return
        val s = cur.snapFractionToZeroCrossing(st.loopStartFrac)
        val e = cur.snapFractionToZeroCrossing(st.loopEndFrac).coerceAtLeast((s + 0.02f).coerceAtMost(1f))
        _loopRefinerState.value = st.copy(
            loopStartFrac = s,
            loopEndFrac = e,
            statusNote = "SNAPPED LOOP MARKERS TO ZERO-CROSSINGS"
        )
    }

    fun trimLoopRefiner() {
        val st = _loopRefinerState.value
        val cur = st.workingBuffer ?: return
        val next = cur.extractSelection(st.loopStartFrac, st.loopEndFrac)
        updateRefinerWorkingBuffer(next, "TRIMMED LOOP (${"%.2f".format(next.durationSeconds)}s)")
    }

    fun applyLoopRefinerFades(fadeInMs: Float = _loopRefinerState.value.fadeInMs, fadeOutMs: Float = _loopRefinerState.value.fadeOutMs) {
        val cur = _loopRefinerState.value.workingBuffer ?: return
        val fin = fadeInMs.coerceIn(0f, 2000f)
        val fout = fadeOutMs.coerceIn(0f, 2000f)
        val next = cur.applyLinearFades(fin, fout)
        _loopRefinerState.value = _loopRefinerState.value.copy(fadeInMs = fin, fadeOutMs = fout)
        updateRefinerWorkingBuffer(next, "APPLIED ${fin.toInt()}/${fout.toInt()}ms FADES")
    }

    fun reverseLoopRefiner() {
        val st = _loopRefinerState.value
        val cur = st.workingBuffer ?: return
        _loopRefinerState.value = st.copy(isReversed = !st.isReversed)
        updateRefinerWorkingBuffer(cur.reversed(), "REVERSED LOOP SAMPLE")
    }

    fun normalizeLoopRefiner() {
        val cur = _loopRefinerState.value.workingBuffer ?: return
        updateRefinerWorkingBuffer(cur.peakNormalized(0.96f), "NORMALIZED PEAK (-0.35 dBFS)")
    }

    fun removeDcLoopRefiner() {
        val cur = _loopRefinerState.value.workingBuffer ?: return
        updateRefinerWorkingBuffer(cur.removeDcOffset(), "REMOVED DC OFFSET")
    }

    fun resetLoopRefiner() {
        val raw = _loopRefinerState.value.rawSlotBuffer ?: return
        _loopRefinerState.value = _loopRefinerState.value.copy(isReversed = false)
        updateRefinerWorkingBuffer(raw, "RESET LOOP SLOT TO ORIGINAL")
    }

    fun commitRefinerToSlot() {
        val st = _loopRefinerState.value
        val refined = st.workingBuffer ?: return
        assignBufferToLoopSlot(st.selectedSlotId, refined, refined.name)
        _loopRefinerState.value = _loopRefinerState.value.copy(
            statusNote = "COMMITTED REFINED LOOP TO SLOT ${'A' + st.selectedSlotId}"
        )
    }

    private fun updateRefinerWorkingBuffer(next: DesktopAudioBuffer, note: String) {
        _loopRefinerState.value = _loopRefinerState.value.copy(
            workingBuffer = next,
            envelope = SignalAnalyzer.computeWaveformEnvelope(next, 110),
            loopStartFrac = 0f,
            loopEndFrac = 1f,
            statusNote = note
        )
    }

    fun toggleLooperPlayback() {
        if (looperRunning.get()) stopLooper() else startLooper()
    }

    fun startLooper() {
        stopLooper()
        looperRunning.set(true)
        _looperState.value = _looperState.value.copy(isRunning = true)

        looperThread = thread(isDaemon = true, name = "SamplfieDesktopLooper") {
            val sr = 44100
            var line: SourceDataLine? = null
            try {
                line = openMonoOutputLine(sr, 2048)
                val chunkFrames = 512
                val sleepMs = (chunkFrames * 1000L) / sr
                var globalFrame = 0L

                while (looperRunning.get()) {
                    val state = _looperState.value
                    val barFrames = max(1, ((60f / state.bpm) * 4f * sr).toInt())
                    val chunk = FloatArray(chunkFrames)

                    for (slot in state.slots) {
                        val buf = slot.buffer ?: continue
                        if (!slot.isActive || slot.isMuted || buf.frameCount == 0) continue
                        val mono = if (buf.sampleRate != sr || buf.channels != 1) {
                            buf.convertChannels(1).resample(sr)
                        } else {
                            buf
                        }
                        val cycleFrames = barFrames * slot.bars
                        val srcFrames = mono.frameCount
                        for (i in 0 until chunkFrames) {
                            val pos = ((globalFrame + i) % cycleFrames).toInt()
                            if (pos < srcFrames) chunk[i] += mono.samples[pos] * slot.volume
                        }
                    }

                    val gain = effectiveMasterGain()
                    if (gain < 0.999f) {
                        for (i in chunk.indices) chunk[i] = (chunk[i] * gain).coerceIn(-1f, 1f)
                    }
                    val pcm = DesktopWavCodec.floatToPcm16Bytes(chunk)
                    if (line != null) line.write(pcm, 0, pcm.size) else Thread.sleep(sleepMs.coerceAtLeast(8L))

                    globalFrame += chunkFrames
                    val progress = ((globalFrame % barFrames).toFloat() / barFrames).coerceIn(0f, 1f)
                    _looperState.value = _looperState.value.copy(barProgress = progress)
                }
            } catch (_: Exception) {
            } finally {
                looperRunning.set(false)
                runCatching { line?.drain(); line?.stop(); line?.close() }
                _looperState.value = _looperState.value.copy(isRunning = false, barProgress = 0f)
            }
        }
    }

    fun stopLooper() {
        looperRunning.set(false)
        runCatching { looperThread?.join(150) }
        _looperState.value = _looperState.value.copy(isRunning = false, barProgress = 0f)
    }

    fun bounceLooperMixdown(sampleRate: Int = 44100): DesktopAudioBuffer {
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
                if (pos < mono.frameCount) mix[f] += mono.samples[pos] * slot.volume
            }
        }
        for (i in mix.indices) mix[i] = mix[i].coerceIn(-1f, 1f)
        return DesktopAudioBuffer(mix, sampleRate, 1, "Looper Mix (${state.bpm.toInt()} BPM)")
    }

    // --- 16-STEP SEQUENCER ---

    fun setSequencerSampleForLane(laneId: Int, buffer: DesktopAudioBuffer, lockSample: Boolean = true) {
        val frozen = buffer.copy(samples = buffer.samples.copyOf())
        val updated = _sequencerState.value.lanes.map { lane ->
            if (lane.id == laneId) {
                lane.copy(buffer = frozen, name = frozen.name.take(12), isSampleLocked = lockSample)
            } else lane
        }
        _sequencerState.value = _sequencerState.value.copy(lanes = updated)
    }

    fun setSequencerSampleIfUnlocked(laneId: Int, buffer: DesktopAudioBuffer) {
        val frozen = buffer.copy(samples = buffer.samples.copyOf())
        val updated = _sequencerState.value.lanes.map { lane ->
            if (lane.id == laneId && !lane.isSampleLocked) {
                lane.copy(buffer = frozen, name = frozen.name.take(12))
            } else lane
        }
        _sequencerState.value = _sequencerState.value.copy(lanes = updated)
    }

    fun toggleSequencerLaneLock(laneId: Int) {
        val updated = _sequencerState.value.lanes.map { lane ->
            if (lane.id == laneId) lane.copy(isSampleLocked = !lane.isSampleLocked) else lane
        }
        _sequencerState.value = _sequencerState.value.copy(lanes = updated)
    }

    fun toggleSequencerStep(laneId: Int, stepIndex: Int) {
        if (stepIndex !in 0 until 16) return
        val updated = _sequencerState.value.lanes.map { lane ->
            if (lane.id == laneId) {
                val copy = lane.steps.copyOf()
                copy[stepIndex] = !copy[stepIndex]
                lane.copy(steps = copy)
            } else lane
        }
        _sequencerState.value = _sequencerState.value.copy(lanes = updated)
    }

    fun applySequencerPatternMask(laneId: Int, stepMask: Int) {
        val updated = _sequencerState.value.lanes.map { lane ->
            if (lane.id == laneId) {
                val steps = BooleanArray(16) { i -> ((stepMask shr i) and 1) == 1 }
                lane.copy(steps = steps)
            } else lane
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

    private val patternUndoStack = ArrayDeque<List<BooleanArray>>()

    fun toggleCompanionOverdubRx() {
        val cur = _sequencerState.value
        _sequencerState.value = cur.copy(companionOverdubRx = !cur.companionOverdubRx)
    }

    fun recordCompanionStepHit(laneId: Int) {
        val cur = _sequencerState.value
        if (!cur.companionOverdubRx) return
        val step = if (cur.isPlaying) cur.currentStep else cur.companionStepCursor
        patternUndoStack.addLast(cur.lanes.map { it.steps.copyOf() })
        val updated = cur.lanes.map { lane ->
            if (lane.id == laneId) {
                val copy = lane.steps.copyOf()
                copy[step] = true
                lane.copy(steps = copy)
            } else lane
        }
        _sequencerState.value = cur.copy(
            lanes = updated,
            companionStepCursor = (step + 1) % 16
        )
    }

    fun undoSequencerPattern() {
        val prev = patternUndoStack.removeLastOrNull() ?: return
        val cur = _sequencerState.value
        val restored = cur.lanes.mapIndexed { idx, lane ->
            lane.copy(steps = prev.getOrElse(idx) { lane.steps })
        }
        _sequencerState.value = cur.copy(lanes = restored)
    }

    fun clearSequencerPattern() {
        val cur = _sequencerState.value
        patternUndoStack.addLast(cur.lanes.map { it.steps.copyOf() })
        val cleared = cur.lanes.map { it.copy(steps = BooleanArray(16)) }
        _sequencerState.value = cur.copy(lanes = cleared, companionStepCursor = 0)
    }

    fun toggleSequencerPlayback() {
        if (seqRunning.get()) stopSequencer() else startSequencer()
    }

    fun startSequencer() {
        stopSequencer()
        seqRunning.set(true)
        _sequencerState.value = _sequencerState.value.copy(isPlaying = true)

        seqThread = thread(isDaemon = true, name = "SamplfieDesktopSeq") {
            val sr = 44100
            var line: SourceDataLine? = null
            try {
                line = openMonoOutputLine(sr, 2048)
                var step = 0
                while (seqRunning.get()) {
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
                            } else lane.buffer
                            val n = min(stepFrames, mono.frameCount)
                            for (i in 0 until n) stepAudio[i] += mono.samples[i] * lane.volume
                        }
                    }

                    val gain = effectiveMasterGain()
                    if (gain < 0.999f) {
                        for (i in stepAudio.indices) stepAudio[i] = (stepAudio[i] * gain).coerceIn(-1f, 1f)
                    }
                    val pcm = DesktopWavCodec.floatToPcm16Bytes(stepAudio)
                    if (line != null) line.write(pcm, 0, pcm.size)
                    else Thread.sleep(((stepFrames * 1000L) / sr).coerceAtLeast(15L))

                    step = (step + 1) % 16
                }
            } catch (_: Exception) {
            } finally {
                seqRunning.set(false)
                runCatching { line?.drain(); line?.stop(); line?.close() }
                _sequencerState.value = _sequencerState.value.copy(isPlaying = false, currentStep = 0)
            }
        }
    }

    fun stopSequencer() {
        seqRunning.set(false)
        runCatching { seqThread?.join(150) }
        _sequencerState.value = _sequencerState.value.copy(isPlaying = false, currentStep = 0)
    }

    fun renderSequenceToBuffer(bars: Int = 2, sampleRate: Int = 44100): DesktopAudioBuffer {
        val state = _sequencerState.value
        val stepFrames = max(256, ((60f / state.bpm / 4f) * sampleRate).toInt())
        val totalSteps = 16 * bars.coerceIn(1, 4)
        val totalFrames = stepFrames * totalSteps
        val mix = FloatArray(totalFrames)
        for (stepIdx in 0 until totalSteps) {
            val patStep = stepIdx % 16
            val startFrame = stepIdx * stepFrames
            for (lane in state.lanes) {
                if (!lane.isMuted && lane.steps[patStep]) {
                    val mono = lane.buffer.convertChannels(1).resample(sampleRate)
                    val endFrame = min(totalFrames, startFrame + mono.frameCount)
                    for (f in startFrame until endFrame) mix[f] += mono.samples[f - startFrame] * lane.volume
                }
            }
        }
        for (i in mix.indices) mix[i] = mix[i].coerceIn(-1f, 1f)
        return DesktopAudioBuffer(mix, sampleRate, 1, "Seq Beat (${state.bpm.toInt()} BPM)")
    }

    // --- SYNCED TIMELINE ---

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
            playheadSec = clamped, bar = bar, beat = beat, phaseFraction = phase
        )
    }

    fun toggleSyncedTransport() {
        if (masterSyncRunning.get()) stopSyncedTransport() else startSyncedTransport()
    }

    fun startSyncedTransport() {
        stopSyncedTransport()
        masterSyncRunning.set(true)
        _syncedPlayhead.value = _syncedPlayhead.value.copy(isPlaying = true)
        startLooper()
        startSequencer()
        if (_timelineState.value.tracks.any { it.clips.isNotEmpty() }) startTimeline()

        syncThread = thread(isDaemon = true, name = "SamplfieDesktopSync") {
            val startPlayhead = _syncedPlayhead.value.playheadSec
            var elapsed = 0f
            val tickMs = 40L
            while (masterSyncRunning.get()) {
                Thread.sleep(tickMs)
                elapsed += tickMs / 1000f
                val tlSec = if (timelineRunning.get()) {
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
        masterSyncRunning.set(false)
        runCatching { syncThread?.join(100) }
        stopLooper()
        stopSequencer()
        stopTimeline()
        _syncedPlayhead.value = _syncedPlayhead.value.copy(isPlaying = false)
    }

    fun addClipToTimeline(trackId: Int, buffer: DesktopAudioBuffer, startTimeSec: Float) {
        val clip = DesktopTimelineClip(
            id = "clip_${System.nanoTime()}",
            label = buffer.name.take(18),
            buffer = buffer,
            startTimeSec = startTimeSec.coerceAtLeast(0f)
        )
        val updatedTracks = _timelineState.value.tracks.map { t ->
            if (t.id == trackId) t.copy(clips = t.clips + clip) else t
        }
        val maxEnd = updatedTracks.flatMap { it.clips }.maxOfOrNull { it.startTimeSec + it.buffer.durationSeconds } ?: 8f
        _timelineState.value = _timelineState.value.copy(tracks = updatedTracks, totalDurationSec = max(8f, maxEnd + 1f))
    }

    fun nudgeClip(trackId: Int, clipId: String, deltaSec: Float) {
        val updatedTracks = _timelineState.value.tracks.map { t ->
            if (t.id == trackId) {
                t.copy(clips = t.clips.map { c ->
                    if (c.id == clipId) c.copy(startTimeSec = (c.startTimeSec + deltaSec).coerceAtLeast(0f)) else c
                })
            } else t
        }
        val maxEnd = updatedTracks.flatMap { it.clips }.maxOfOrNull { it.startTimeSec + it.buffer.durationSeconds } ?: 8f
        _timelineState.value = _timelineState.value.copy(tracks = updatedTracks, totalDurationSec = max(8f, maxEnd + 1f))
    }

    fun removeTimelineClip(trackId: Int, clipId: String) {
        val updatedTracks = _timelineState.value.tracks.map { t ->
            if (t.id == trackId) t.copy(clips = t.clips.filterNot { it.id == clipId }) else t
        }
        _timelineState.value = _timelineState.value.copy(tracks = updatedTracks)
    }

    fun toggleTimelineMute(trackId: Int) {
        val updated = _timelineState.value.tracks.map { t -> if (t.id == trackId) t.copy(isMuted = !t.isMuted) else t }
        _timelineState.value = _timelineState.value.copy(tracks = updated)
    }

    fun toggleTimelineSolo(trackId: Int) {
        val updated = _timelineState.value.tracks.map { t -> if (t.id == trackId) t.copy(isSolo = !t.isSolo) else t }
        _timelineState.value = _timelineState.value.copy(tracks = updated)
    }

    fun toggleTimelinePlayback() {
        if (timelineRunning.get()) stopTimeline() else startTimeline()
    }

    fun startTimeline() {
        stopTimeline()
        val mixdown = mixdownTimeline(44100)
        if (mixdown.frameCount == 0) return

        timelineRunning.set(true)
        _timelineState.value = _timelineState.value.copy(isPlaying = true)

        timelineThread = thread(isDaemon = true, name = "SamplfieDesktopTimeline") {
            val sr = mixdown.sampleRate
            var line: SourceDataLine? = null
            try {
                line = openMonoOutputLine(sr, 2048)
                val chunkFrames = 1024
                var cursor = (_timelineState.value.playheadSec * sr).toInt().coerceIn(0, max(0, mixdown.frameCount - 1))
                while (timelineRunning.get() && cursor < mixdown.frameCount) {
                    val count = min(chunkFrames, mixdown.frameCount - cursor)
                    val gain = effectiveMasterGain()
                    val slice = mixdown.samples.copyOfRange(cursor, cursor + count)
                    if (gain < 0.999f) {
                        for (i in slice.indices) slice[i] = (slice[i] * gain).coerceIn(-1f, 1f)
                    }
                    val pcm = DesktopWavCodec.floatToPcm16Bytes(slice)
                    if (line != null) line.write(pcm, 0, pcm.size)
                    else Thread.sleep(((count * 1000L) / sr).coerceAtLeast(15L))
                    cursor += count
                    _timelineState.value = _timelineState.value.copy(playheadSec = cursor.toFloat() / sr)
                }
            } catch (_: Exception) {
            } finally {
                timelineRunning.set(false)
                runCatching { line?.drain(); line?.stop(); line?.close() }
                _timelineState.value = _timelineState.value.copy(isPlaying = false)
            }
        }
    }

    fun stopTimeline() {
        timelineRunning.set(false)
        runCatching { timelineThread?.join(150) }
        _timelineState.value = _timelineState.value.copy(isPlaying = false, playheadSec = 0f)
    }

    fun mixdownTimeline(sampleRate: Int = 44100): DesktopAudioBuffer {
        val state = _timelineState.value
        val hasSolo = state.tracks.any { it.isSolo }
        val activeTracks = state.tracks.filter { t -> !t.isMuted && (!hasSolo || t.isSolo) && t.clips.isNotEmpty() }
        if (activeTracks.isEmpty()) return DesktopAudioBuffer(FloatArray(0), sampleRate, 1, "Timeline Empty")
        val maxEndSec = activeTracks.flatMap { it.clips }.maxOfOrNull { it.startTimeSec + it.buffer.durationSeconds } ?: 1f
        val totalFrames = max(1, (maxEndSec * sampleRate).toInt())
        val mix = FloatArray(totalFrames)
        for (track in activeTracks) {
            for (clip in track.clips) {
                val mono = clip.buffer.convertChannels(1).resample(sampleRate)
                val startFrame = (clip.startTimeSec * sampleRate).toInt().coerceAtLeast(0)
                val endFrame = min(totalFrames, startFrame + mono.frameCount)
                for (f in startFrame until endFrame) mix[f] += mono.samples[f - startFrame] * track.gain
            }
        }
        for (i in mix.indices) mix[i] = mix[i].coerceIn(-1f, 1f)
        return DesktopAudioBuffer(mix, sampleRate, 1, "Timeline Mixdown")
    }

    private fun openMonoOutputLine(sampleRate: Int, bufBytes: Int): SourceDataLine? {
        return runCatching {
            val fmt = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, fmt)
            if (!AudioSystem.isLineSupported(info)) return null
            (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(fmt, bufBytes)
                start()
            }
        }.getOrNull()
    }

    fun release() {
        stopSyncedTransport()
        stopLooper()
        stopSequencer()
        stopTimeline()
    }
}
