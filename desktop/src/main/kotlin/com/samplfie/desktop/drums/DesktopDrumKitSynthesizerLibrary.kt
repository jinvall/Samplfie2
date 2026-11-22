package com.samplfie.desktop.drums

import com.samplfie.desktop.model.DesktopAudioBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Pure Kotlin DSP percussion synthesizer for Linux Desktop achieving 1:1 acoustic & electric parity.
 */
object DesktopDrumKitSynthesizerLibrary {

    fun synthesizeHitComposite(
        piece: PlacedKitPiece,
        mountedAddons: List<MountedAddonPiece>,
        beaterSide: BeaterSide = BeaterSide.NONE,
        sampleRate: Int = 44100
    ): DesktopAudioBuffer {
        val mainBuf = synthesizePiece(piece, beaterSide, sampleRate)
        if (piece.isMuted) {
            return DesktopAudioBuffer(FloatArray(min(256, mainBuf.frameCount)), sampleRate, 1, "${piece.customLabel} (MUTE)")
        }
        val activeAddons = mountedAddons.filter { addon ->
            addon.targetSlotId == piece.slotId &&
                addon.triggerCoupledWithHost &&
                (piece.category != DrumPieceCategory.DOUBLE_PEDAL ||
                    addon.beaterSide == BeaterSide.NONE ||
                    beaterSide == BeaterSide.NONE ||
                    addon.beaterSide == beaterSide)
        }
        if (activeAddons.isEmpty()) return mainBuf

        val addonBuffers = activeAddons.map { synthesizeAddon(it, sampleRate) }
        val maxFrames = maxOf(mainBuf.frameCount, addonBuffers.maxOfOrNull { it.frameCount } ?: 0)
        val mix = FloatArray(maxFrames)
        for (i in 0 until mainBuf.frameCount) mix[i] += mainBuf.samples[i]
        addonBuffers.forEach { ab ->
            for (i in 0 until ab.frameCount) mix[i] += ab.samples[i]
        }
        for (i in mix.indices) mix[i] = tanh(mix[i].toDouble()).toFloat().coerceIn(-1f, 1f)
        val tag = activeAddons.joinToString("+") { it.addonType.shortTag }
        val beaterTag = if (piece.category == DrumPieceCategory.DOUBLE_PEDAL && beaterSide != BeaterSide.NONE) {
            " ${beaterSide.shortTag}"
        } else ""
        return DesktopAudioBuffer(mix, sampleRate, 1, "${piece.customLabel}$beaterTag+$tag")
    }

    fun synthesizePiece(
        piece: PlacedKitPiece,
        beaterSide: BeaterSide = BeaterSide.NONE,
        sampleRate: Int = 44100
    ): DesktopAudioBuffer {
        val pitch = 2.0.pow(piece.pitchSemitones / 12.0).toFloat()
        val decay = piece.decayScale.coerceIn(0.35f, 2.2f)
        val vol = piece.volume.coerceIn(0f, 1.2f)

        val raw = when (piece.componentId) {
            "kick_ac_maple" -> synthAcousticKick(sampleRate, pitch, decay, 1.0f)
            "dkick_ac_twin" -> synthAcousticKick(sampleRate, pitch * if (beaterSide == BeaterSide.RIGHT) 1.045f else 0.985f, decay * 0.92f, 1.12f)
            "kick_el_808" -> synth808Kick(sampleRate, pitch, decay)
            "dkick_el_909" -> synth909Kick(sampleRate, pitch * if (beaterSide == BeaterSide.RIGHT) 1.05f else 0.97f, decay * 0.9f)
            "snare_ac_maple" -> synthAcousticSnare(sampleRate, pitch, decay, 188f, 0.58f)
            "snare_ac_piccolo" -> synthAcousticSnare(sampleRate, pitch * 1.25f, decay * 0.72f, 248f, 0.65f)
            "snare_el_909" -> synthElectricSnare(sampleRate, pitch, decay)
            "snare_el_clap" -> synthElectricClap(sampleRate, pitch, decay)
            "hat_ac_bronze" -> synthAcousticHiHat(sampleRate, pitch, decay)
            "hat_el_crisp" -> synthElectricHiHat(sampleRate, pitch, decay)
            "tom_hi_ac" -> synthAcousticTom(sampleRate, 175f * pitch, decay * 0.85f)
            "tom_mid_ac" -> synthAcousticTom(sampleRate, 132f * pitch, decay * 0.95f)
            "tom_flr_ac" -> synthAcousticTom(sampleRate, 84f * pitch, decay * 1.15f)
            "tom_hi_el" -> synthElectricTom(sampleRate, 320f * pitch, 135f * pitch, decay * 0.85f)
            "tom_mid_el" -> synthElectricTom(sampleRate, 240f * pitch, 98f * pitch, decay * 0.95f)
            "tom_flr_el" -> synthElectricTom(sampleRate, 165f * pitch, 56f * pitch, decay * 1.15f)
            "crash_ac_dark" -> synthAcousticCymbal(sampleRate, pitch, decay, 0.85f, 1.0f)
            "crash_el_synth" -> synthElectricCymbal(sampleRate, pitch, decay, 0.72f)
            "ride_ac_ping" -> synthAcousticRide(sampleRate, pitch, decay)
            "ride_el_bell" -> synthElectricRide(sampleRate, pitch, decay)
            "china_ac_trash" -> synthAcousticCymbal(sampleRate, pitch * 1.14f, decay * 0.65f, 0.55f, 1.38f)
            "china_el_cyber" -> synthCyberZap(sampleRate, pitch, decay)
            else -> synthAcousticSnare(sampleRate, pitch, decay, 188f, 0.55f)
        }
        val scaled = FloatArray(raw.size) { i -> (raw[i] * vol).coerceIn(-1f, 1f) }
        val suffix = if (piece.category == DrumPieceCategory.DOUBLE_PEDAL && beaterSide != BeaterSide.NONE) {
            " (${beaterSide.shortTag})"
        } else ""
        return DesktopAudioBuffer(scaled, sampleRate, 1, "${piece.customLabel}$suffix")
    }

    fun synthesizeAddon(addon: MountedAddonPiece, sampleRate: Int = 44100): DesktopAudioBuffer {
        val pitch = 2.0.pow(addon.pitchSemitones / 12.0).toFloat()
        val vol = addon.volume.coerceIn(0f, 1.2f)
        val raw = when (addon.addonType) {
            DrumAddonType.COWBELL ->
                if (addon.style == DrumKitStyle.ACOUSTIC) synthAcousticCowbell(sampleRate, pitch)
                else synth808Cowbell(sampleRate, pitch)
            DrumAddonType.TAMBOURINE ->
                if (addon.style == DrumKitStyle.ACOUSTIC) synthAcousticTambourine(sampleRate, pitch)
                else synthElectricTambourine(sampleRate, pitch)
            DrumAddonType.MARACA ->
                if (addon.style == DrumKitStyle.ACOUSTIC) synthAcousticMaraca(sampleRate, pitch)
                else synthElectricMaraca(sampleRate, pitch)
            DrumAddonType.JAM_BLOCK ->
                if (addon.style == DrumKitStyle.ACOUSTIC) synthAcousticWoodblock(sampleRate, pitch)
                else synthElectricJamBlock(sampleRate, pitch)
            DrumAddonType.CHIME_SPLASHER ->
                if (addon.style == DrumKitStyle.ACOUSTIC) synthAcousticChime(sampleRate, pitch)
                else synthElectricChime(sampleRate, pitch)
        }
        val scaled = FloatArray(raw.size) { i -> (raw[i] * vol).coerceIn(-1f, 1f) }
        return DesktopAudioBuffer(scaled, sampleRate, 1, "${addon.style.shortTag.take(2)} ${addon.addonType.shortTag}")
    }

    private fun synthAcousticKick(sr: Int, pitch: Float, decay: Float, beaterNuance: Float): FloatArray {
        val frames = (sr * (0.28f * decay).coerceIn(0.14f, 0.55f)).toInt()
        val out = FloatArray(frames)
        val rng = Random(101)
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val freq = (52f + 108f * exp(-t * 42f)) * pitch
            phase += 2.0 * PI * freq / sr
            val click = (rng.nextFloat() * 2f - 1f) * exp(-t * 210f) * 0.42f * beaterNuance
            val shell = sin(phase).toFloat() * exp(-t * (12.5f / decay)) * 0.85f
            val overtone = sin(phase * 1.98).toFloat() * exp(-t * 26f) * 0.16f
            out[i] = tanh((shell + click + overtone).toDouble()).toFloat()
        }
        return out
    }

    private fun synthAcousticSnare(sr: Int, pitch: Float, decay: Float, bodyHz: Float, wireMix: Float): FloatArray {
        val frames = (sr * (0.24f * decay).coerceIn(0.10f, 0.48f)).toInt()
        val out = FloatArray(frames)
        val rng = Random(202)
        var lp = 0f
        var ph1 = 0.0
        var ph2 = 0.0
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val f = bodyHz * pitch * (1f + 0.35f * exp(-t * 45f))
            ph1 += 2.0 * PI * f / sr
            ph2 += 2.0 * PI * (f * 1.56) / sr
            val shell = (sin(ph1) * 0.52 + sin(ph2) * 0.24).toFloat() * exp(-t * (18f / decay))
            val white = rng.nextFloat() * 2f - 1f
            lp += 0.28f * (white - lp)
            val wires = (white - lp) * exp(-t * (13f / decay)) * wireMix
            val stick = exp(-t * 280f) * 0.3f
            out[i] = tanh((shell + wires + stick).toDouble()).toFloat()
        }
        return out
    }

    private fun synthAcousticHiHat(sr: Int, pitch: Float, decay: Float): FloatArray {
        val frames = (sr * (0.12f * decay).coerceIn(0.05f, 0.30f)).toInt()
        val out = FloatArray(frames)
        val rng = Random(303)
        var prev = 0f
        val modes = doubleArrayOf(3120.0, 4490.0, 5810.0, 7240.0)
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            var bronze = 0.0
            for (m in modes) bronze += sin(2.0 * PI * m * pitch * t) * 0.12
            val white = rng.nextFloat() * 2f - 1f
            val hp = white - prev * 0.88f
            prev = white
            val env = exp(-t * (38f / decay))
            out[i] = ((bronze.toFloat() * 0.5f + hp * 0.55f) * env).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthAcousticTom(sr: Int, baseHz: Float, decay: Float): FloatArray {
        val frames = (sr * (0.36f * decay).coerceIn(0.15f, 0.70f)).toInt()
        val out = FloatArray(frames)
        var ph = 0.0
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val f = baseHz * (1f + 0.42f * exp(-t * 24f))
            ph += 2.0 * PI * f / sr
            val head = sin(ph).toFloat() * exp(-t * (8.5f / decay)) * 0.82f
            val res = sin(ph * 1.49).toFloat() * exp(-t * (14f / decay)) * 0.22f
            out[i] = tanh((head + res).toDouble()).toFloat()
        }
        return out
    }

    private fun synthAcousticCymbal(sr: Int, pitch: Float, decay: Float, durationSec: Float, metallicDensity: Float): FloatArray {
        val frames = (sr * (durationSec * decay).coerceIn(0.25f, 1.4f)).toInt()
        val out = FloatArray(frames)
        val rng = Random(404)
        var prev = 0f
        val partials = doubleArrayOf(612.0, 935.0, 1480.0, 2310.0, 3890.0, 5420.0)
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            var ring = 0.0
            partials.forEachIndexed { idx, hz ->
                ring += sin(2.0 * PI * hz * pitch * metallicDensity * t) * (0.14 / (1 + idx * 0.2))
            }
            val n = rng.nextFloat() * 2f - 1f
            val shimmer = n - prev * 0.84f
            prev = n
            val env = exp(-t * (4.2f / decay))
            out[i] = ((ring.toFloat() * 0.45f + shimmer * 0.48f) * env).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthAcousticRide(sr: Int, pitch: Float, decay: Float): FloatArray {
        val frames = (sr * (0.72f * decay).coerceIn(0.25f, 1.2f)).toInt()
        val out = FloatArray(frames)
        val rng = Random(505)
        var prev = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val bell = (sin(2.0 * PI * 910.0 * pitch * t) * 0.35 +
                sin(2.0 * PI * 2140.0 * pitch * t) * 0.25 +
                sin(2.0 * PI * 3690.0 * pitch * t) * 0.16).toFloat()
            val bow = (rng.nextFloat() * 2f - 1f - prev * 0.8f) * 0.22f
            prev = rng.nextFloat() * 2f - 1f
            val stick = exp(-t * 140f) * 0.3f
            val env = exp(-t * (5.2f / decay))
            out[i] = ((bell + bow) * env + stick).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synth808Kick(sr: Int, pitch: Float, decay: Float): FloatArray {
        val frames = (sr * (0.42f * decay).coerceIn(0.18f, 0.85f)).toInt()
        val out = FloatArray(frames)
        var ph = 0.0
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val f = (45f + 145f * exp(-t * 28f)) * pitch
            ph += 2.0 * PI * f / sr
            out[i] = (sin(ph) * exp(-t * (6.8f / decay)) * 0.95).toFloat().coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synth909Kick(sr: Int, pitch: Float, decay: Float): FloatArray {
        val frames = (sr * (0.30f * decay).coerceIn(0.12f, 0.6f)).toInt()
        val out = FloatArray(frames)
        var ph = 0.0
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val f = (54f + 190f * exp(-t * 48f)) * pitch
            ph += 2.0 * PI * f / sr
            val punch = sin(ph).toFloat() * exp(-t * (10.5f / decay))
            val click = sin(2.0 * PI * 1400.0 * t).toFloat() * exp(-t * 190f) * 0.45f
            out[i] = tanh((punch + click) * 1.15).toFloat()
        }
        return out
    }

    private fun synthElectricSnare(sr: Int, pitch: Float, decay: Float): FloatArray {
        val frames = (sr * (0.22f * decay).coerceIn(0.1f, 0.45f)).toInt()
        val out = FloatArray(frames)
        val rng = Random(606)
        var lp = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val body = sin(2.0 * PI * 185.0 * pitch * t).toFloat() * exp(-t * 24f)
            val noise = rng.nextFloat() * 2f - 1f
            lp += 0.35f * (noise - lp)
            out[i] = (body * 0.56f + (noise - lp) * exp(-t * (14f / decay)) * 0.68f).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthElectricClap(sr: Int, pitch: Float, decay: Float): FloatArray {
        val frames = (sr * (0.24f * decay).coerceIn(0.12f, 0.5f)).toInt()
        val out = FloatArray(frames)
        val rng = Random(707)
        var prev = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val n = rng.nextFloat() * 2f - 1f
            val bp = n - prev * 0.65f
            prev = n
            val burstEnv = when {
                t < 0.011f -> 1f
                t < 0.022f -> 0.85f
                t < 0.034f -> 0.78f
                else -> exp(-(t - 0.034f) * (15f / decay))
            }
            val tone = sin(2.0 * PI * 980.0 * pitch * t).toFloat() * 0.14f
            out[i] = ((bp * 0.75f + tone) * burstEnv).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthElectricHiHat(sr: Int, pitch: Float, decay: Float): FloatArray {
        val frames = (sr * (0.10f * decay).coerceIn(0.04f, 0.25f)).toInt()
        val out = FloatArray(frames)
        val rng = Random(808)
        var prev = 0f
        val sqFreqs = doubleArrayOf(205.0, 304.0, 369.0, 522.0, 800.0)
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            var metallic = 0f
            for (f in sqFreqs) metallic += if (sin(2.0 * PI * f * pitch * t) >= 0) 0.12f else -0.12f
            val n = rng.nextFloat() * 2f - 1f
            val hp = n - prev * 0.85f
            prev = n
            out[i] = ((metallic * 0.55f + hp * 0.45f) * exp(-t * (45f / decay))).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthElectricTom(sr: Int, startHz: Float, endHz: Float, decay: Float): FloatArray {
        val frames = (sr * (0.30f * decay).coerceIn(0.14f, 0.6f)).toInt()
        val out = FloatArray(frames)
        var ph = 0.0
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val freq = endHz + (startHz - endHz) * exp(-t * 18f)
            ph += 2.0 * PI * freq / sr
            out[i] = (sin(ph) * exp(-t * (9f / decay)) * 0.85).toFloat()
        }
        return out
    }

    private fun synthElectricCymbal(sr: Int, pitch: Float, decay: Float, durationSec: Float): FloatArray {
        val frames = (sr * (durationSec * decay).coerceIn(0.2f, 1.1f)).toInt()
        val out = FloatArray(frames)
        val rng = Random(909)
        var prev = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val ring = sin(2.0 * PI * 540.0 * pitch * t + 2.2 * sin(2.0 * PI * 811.0 * t)).toFloat() * 0.25f
            val hp = (rng.nextFloat() * 2f - 1f) - prev * 0.85f
            prev = rng.nextFloat() * 2f - 1f
            out[i] = ((ring + hp * 0.65f) * exp(-t * (5.2f / decay))).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthElectricRide(sr: Int, pitch: Float, decay: Float): FloatArray {
        val frames = (sr * (0.55f * decay).coerceIn(0.2f, 0.9f)).toInt()
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val fm = sin(2.0 * PI * 1120.0 * pitch * t + 1.4 * sin(2.0 * PI * 1790.0 * t)).toFloat()
            out[i] = (fm * exp(-t * (6.2f / decay)) * 0.7f).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthCyberZap(sr: Int, pitch: Float, decay: Float): FloatArray {
        val frames = (sr * (0.20f * decay).coerceIn(0.08f, 0.45f)).toInt()
        val out = FloatArray(frames)
        var ph = 0.0
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val freq = (260f + 780f * exp(-t * 32f)) * pitch
            ph += 2.0 * PI * freq / sr
            out[i] = (sin(ph) * exp(-t * (16f / decay)) * 0.78).toFloat()
        }
        return out
    }

    private fun synthAcousticCowbell(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.28f).toInt()
        val out = FloatArray(frames)
        val f1 = 540.0 * pitch
        val f2 = 815.0 * pitch
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val tone = sin(2.0 * PI * f1 * t) * 0.55 + sin(2.0 * PI * f2 * t) * 0.45 +
                sin(2.0 * PI * (f2 * 1.47) * t) * 0.16 * exp(-t * 28f)
            val strike = exp(-t * 180f) * 0.25
            out[i] = tanh((tone * exp(-t * 11.5f) + strike) * 1.1).toFloat()
        }
        return out
    }

    private fun synth808Cowbell(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.22f).toInt()
        val out = FloatArray(frames)
        val f1 = 540.0 * pitch
        val f2 = 800.0 * pitch
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val sq1 = if (sin(2.0 * PI * f1 * t) >= 0.0) 0.5f else -0.5f
            val sq2 = if (sin(2.0 * PI * f2 * t) >= 0.0) 0.45f else -0.45f
            out[i] = ((sq1 + sq2) * exp(-t * 16f) * 0.72f).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthAcousticTambourine(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.24f).toInt()
        val out = FloatArray(frames)
        val rng = Random(1111)
        var prev = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val n = rng.nextFloat() * 2f - 1f
            val hp = n - prev * 0.9f
            prev = n
            val jingleOsc = (sin(2.0 * PI * 4280.0 * pitch * t) + sin(2.0 * PI * 6120.0 * pitch * t)).toFloat() * 0.25f
            val env = exp(-t * 14f) * (0.65f + 0.35f * sin(2.0 * PI * 32.0 * t).toFloat())
            out[i] = ((hp * 0.65f + jingleOsc) * env).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthElectricTambourine(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.19f).toInt()
        val out = FloatArray(frames)
        val rng = Random(1212)
        var prev = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val n = rng.nextFloat() * 2f - 1f
            val hp = n - prev * 0.92f
            prev = n
            val bell = sin(2.0 * PI * 5250.0 * pitch * t).toFloat() * 0.32f
            out[i] = ((hp * 0.68f + bell) * exp(-t * 19f)).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthAcousticMaraca(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.11f).toInt()
        val out = FloatArray(frames)
        val rng = Random(1313)
        var prev = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val n = rng.nextFloat() * 2f - 1f
            val seedNoise = n - prev * 0.72f
            prev = n
            val rise = 1f - exp(-t * 240f * pitch)
            val fall = exp(-t * 36f)
            out[i] = (seedNoise * rise * fall * 0.85f).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthElectricMaraca(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.085f).toInt()
        val out = FloatArray(frames)
        val rng = Random(1414)
        var prev = 0f
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val n = rng.nextFloat() * 2f - 1f
            val sharp = n - prev * 0.84f
            prev = n
            val chirp = sin(2.0 * PI * 3600.0 * pitch * t).toFloat() * 0.14f
            out[i] = ((sharp * 0.78f + chirp) * exp(-t * 44f)).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthAcousticWoodblock(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.13f).toInt()
        val out = FloatArray(frames)
        val f = 760.0 * pitch
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val wood = (sin(2.0 * PI * f * t) * 0.78 + sin(2.0 * PI * (f * 2.13) * t) * 0.22).toFloat()
            out[i] = (wood * exp(-t * 34f)).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthElectricJamBlock(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.11f).toInt()
        val out = FloatArray(frames)
        val f = 920.0 * pitch
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val fm = sin(2.0 * PI * f * t + 1.5 * sin(2.0 * PI * (f * 1.41) * t) * exp(-t * 25f)).toFloat()
            out[i] = (fm * exp(-t * 38f) * 0.82f).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthAcousticChime(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.45f).toInt()
        val out = FloatArray(frames)
        val freqs = doubleArrayOf(2400.0, 2950.0, 3620.0, 4410.0)
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            var mix = 0.0
            freqs.forEachIndexed { idx, hz ->
                val delayStart = idx * 0.025f
                if (t >= delayStart) {
                    val dt = t - delayStart
                    mix += sin(2.0 * PI * hz * pitch * dt) * exp(-dt * 8f) * 0.25
                }
            }
            out[i] = mix.toFloat().coerceIn(-1f, 1f)
        }
        return out
    }

    private fun synthElectricChime(sr: Int, pitch: Float): FloatArray {
        val frames = (sr * 0.36f).toInt()
        val out = FloatArray(frames)
        val freqs = doubleArrayOf(2600.0, 3250.0, 4120.0, 5200.0)
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            var mix = 0.0
            freqs.forEachIndexed { idx, hz ->
                val delayStart = idx * 0.018f
                if (t >= delayStart) {
                    val dt = t - delayStart
                    mix += sin(2.0 * PI * hz * pitch * dt) * exp(-dt * 10f) * 0.24
                }
            }
            out[i] = mix.toFloat().coerceIn(-1f, 1f)
        }
        return out
    }
}
