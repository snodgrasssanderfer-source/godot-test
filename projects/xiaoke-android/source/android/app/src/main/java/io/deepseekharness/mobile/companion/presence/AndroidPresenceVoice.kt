package io.deepseekharness.mobile.companion.presence

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import io.deepseekharness.mobile.companion.presence.PresenceProtocol.*

/** System ASR/TTS only. Every method is called on main; binder callbacks are marshalled to main. */
internal class AndroidPresenceVoice(
    private val context: Context,
    private val main: Handler,
    private val worker: Executor,
    private val emit: (Event) -> Unit,
    private val valid: (TurnToken) -> Boolean,
    private val started: (Present) -> Unit,
    private val mouth: (Float) -> Unit,
    private val report: (String) -> Unit,
) {
    private data class SynthJob(val request: Synthesize, val file: File, var cancelled: Boolean = false)
    private var listenTicket: InputTicket? = null
    private var continuous = false
    private var allowNetworkAsr = false
    private var allowNetworkTts = false
    private var recognizer: SpeechRecognizer? = null
    private var recognitionEpoch = 0L
    private var retries = 0
    private var microphoneRequested = false
    private var blockedUntil = 0L
    private var speakerActive = false
    private var headphoneBargeIn = false
    private var headphoneRoute = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var ttsEpoch = 0L
    private val synthJobs = linkedMapOf<String, SynthJob>()
    private val audioFiles = linkedMapOf<String, File>()
    private var player: MediaPlayer? = null
    private var playing: Present? = null
    private var focus: AudioFocusRequest? = null
    private val manager = context.getSystemService(AudioManager::class.java)
    private val rearm = Runnable { startRecognition() }
    private val mouthTick = object : Runnable {
        override fun run() {
            val current = playing ?: return
            val p = player ?: return
            val position = runCatching { p.currentPosition }.getOrDefault(0)
            mouth(current.audio.volumes.getOrNull(position / current.audio.sliceLengthMs) ?: 0f)
            main.postDelayed(this, 40)
        }
    }
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun configure(networkAsr: Boolean, networkTts: Boolean, allowHeadphoneBargeIn: Boolean = false) {
        allowNetworkAsr = networkAsr; allowNetworkTts = networkTts; headphoneBargeIn = allowHeadphoneBargeIn
    }
    fun headphoneBargeInActive() = headphoneBargeIn && headphoneRoute && speakerActive
    private fun canHearDuringPlayback() = !speakerActive || headphoneBargeInActive()
    private fun updateHeadphoneRoute(device: android.media.AudioDeviceInfo?) {
        val was = headphoneRoute
        headphoneRoute = device?.type in setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        if (speakerActive && !canHearDuringPlayback()) { main.removeCallbacks(rearm); destroyRecognizer() }
        else if (was != headphoneRoute && microphoneRequested) scheduleRecognition(0)
    }
    fun listen(ticket: InputTicket, repeat: Boolean) {
        stopListening(); listenTicket = ticket; continuous = repeat; microphoneRequested = true; retries = 0
        scheduleRecognition(0)
    }
    fun stopListening() {
        microphoneRequested = false; listenTicket = null; main.removeCallbacks(rearm); destroyRecognizer()
    }
    private fun destroyRecognizer() {
        recognitionEpoch++
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }; recognizer = null
    }
    private fun scheduleRecognition(delay: Long) {
        main.removeCallbacks(rearm)
        if (microphoneRequested && canHearDuringPlayback()) main.postDelayed(rearm, maxOf(delay, blockedUntil - SystemClock.elapsedRealtime(), 0L))
    }
    private fun startRecognition() {
        if (!microphoneRequested || !canHearDuringPlayback()) return
        if (SystemClock.elapsedRealtime() < blockedUntil) { scheduleRecognition(0); return }
        val ticket = listenTicket ?: return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphoneRequested = false; report("microphone-permission-required"); return
        }
        val local = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!local && !allowNetworkAsr) { microphoneRequested = false; report("on-device-asr-unavailable"); return }
        if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) { microphoneRequested = false; report("system-asr-unavailable"); return }
        destroyRecognizer()
        val generation = recognitionEpoch
        val id = UUID.randomUUID().toString()
        var began = false
        fun live() = generation == recognitionEpoch && microphoneRequested && listenTicket == ticket && canHearDuringPlayback()
        fun finish(text: String?, retry: Boolean) {
            if (!live()) return
            if (!text.isNullOrBlank() && !began) { began = true; emit(SpeechStarted(ticket, id)) }
            if (began) emit(RecognizedSpeech(ticket, id, text.orEmpty()))
            destroyRecognizer()
            if (!continuous) microphoneRequested = false
            if (retry && continuous && microphoneRequested) scheduleRecognition(700)
        }
        try {
            val service = if (local && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = service
            service.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() { if (live() && !began) { began = true; emit(SpeechStarted(ticket, id)) } }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (!live()) return
                    val ordinary = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (!ordinary) { retries++; report("asr-error-$error") }
                    if (retries >= 3 || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) continuous = false
                    finish(null, true)
                }
                override fun onResults(results: Bundle?) {
                    if (!live()) return
                    retries = 0
                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(), true)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !allowNetworkAsr)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (_: Exception) { microphoneRequested = false; destroyRecognizer(); report("asr-start-failed") }
    }

    fun synthesize(request: Synthesize, audible: Boolean) {
        if (!valid(request.turn)) return
        if (!audible) { emit(Synthesized(request.turn, request.sequence, Audio(null, request.sentence))); return }
        if (ttsFailed) { emit(SynthesisFailed(request.turn, request.sequence)); return }
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "xiaoke-presence-$id.wav")
        synthJobs[id] = SynthJob(request, file)
        if (ttsReady) submitSynthesis(id) else if (tts == null) {
            val generation = ++ttsEpoch
            tts = TextToSpeech(context) { status -> main.post {
                if (generation != ttsEpoch) return@post
                ttsReady = status == TextToSpeech.SUCCESS; ttsFailed = !ttsReady
                if (ttsReady) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { main.post { synthesisDone(utteranceId, true) } }
                        @Deprecated("Android callback") override fun onError(utteranceId: String?) { main.post { synthesisDone(utteranceId, false) } }
                        override fun onError(utteranceId: String?, errorCode: Int) { main.post { synthesisDone(utteranceId, false) } }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { synthesisDone(utteranceId, false) } }
                    })
                    synthJobs.keys.toList().forEach(::submitSynthesis)
                } else {
                    report("system-tts-unavailable")
                    synthJobs.keys.toList().forEach { synthesisDone(it, false) }
                }
            } }
        }
    }
    private fun submitSynthesis(id: String) {
        val job = synthJobs[id] ?: return
        if (job.cancelled || !valid(job.request.turn)) { synthesisDone(id, false); return }
        val service = tts ?: return
        try {
            val voices = service.voices.orEmpty().filter { allowNetworkTts || !it.isNetworkConnectionRequired }
            val preferred = voices.firstOrNull { it.locale.language == Locale.getDefault().language } ?: voices.firstOrNull()
            if (preferred == null) { report("local-tts-voice-unavailable"); synthesisDone(id, false); return }
            check(service.setVoice(preferred) == TextToSpeech.SUCCESS)
            service.setAudioAttributes(audioAttributes)
            if (service.synthesizeToFile(job.request.sentence.ttsText, Bundle(), job.file, id) != TextToSpeech.SUCCESS) synthesisDone(id, false)
        } catch (_: Exception) { synthesisDone(id, false) }
    }
    private fun synthesisDone(id: String?, success: Boolean) {
        val job = synthJobs.remove(id) ?: return
        if (!success || job.cancelled || !valid(job.request.turn) || !job.file.isFile || job.file.length() == 0L) {
            job.file.delete()
            if (!job.cancelled && valid(job.request.turn)) emit(SynthesisFailed(job.request.turn, job.request.sequence))
            return
        }
        worker.execute {
            val envelope = runCatching { if (job.file.length() <= 8 * 1024 * 1024) WavEnvelope.read(job.file.readBytes(), 20) else emptyList() }.getOrDefault(emptyList())
            main.post {
                if (!valid(job.request.turn)) { job.file.delete(); return@post }
                val ref = "tts:$id"; audioFiles[ref] = job.file
                emit(Synthesized(job.request.turn, job.request.sequence, Audio(ref, job.request.sentence, envelope, 20, false)))
            }
        }
    }
    fun present(request: Present) {
        if (!valid(request.turn)) { release(request.audio.audioRef); return }
        stopPlayback(null)
        playing = request
        if (request.audio.audioRef == null) {
            started(request); emit(PlaybackStarted(request.turn, request.sequence))
            main.postDelayed({ if (playing === request && valid(request.turn)) { playing = null; emit(PlaybackEnded(request.turn, request.sequence)) } },
                (request.audio.sentence.displayText.length * 70L).coerceIn(900L, 5_000L))
            return
        }
        val file = audioFiles[request.audio.audioRef]
        if (file == null) { emit(Failed(request.turn, "tts-audio-missing")); return }
        speakerActive = true; main.removeCallbacks(rearm); destroyRecognizer()
        val audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener({ change -> if (change < 0 && playing === request) emit(Failed(request.turn, "audio-focus-lost")) }, main).build()
        focus = audioFocus
        if (manager.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stopPlayback(request.turn); emit(Failed(request.turn, "audio-focus-denied")); return
        }
        try {
            val p = MediaPlayer(); player = p
            if (Build.VERSION.SDK_INT >= 28) p.addOnRoutingChangedListener({ routing -> updateHeadphoneRoute(routing.routedDevice) }, main)
            p.setAudioAttributes(audioAttributes); p.setDataSource(file.absolutePath)
            p.setOnPreparedListener {
                if (playing !== request || !valid(request.turn)) { stopPlayback(request.turn); return@setOnPreparedListener }
                try { p.start(); updateHeadphoneRoute(if (Build.VERSION.SDK_INT >= 28) p.routedDevice else null); started(request); emit(PlaybackStarted(request.turn, request.sequence)); main.post(mouthTick) }
                catch (_: Exception) { stopPlayback(request.turn); emit(Failed(request.turn, "audio-start-failed")) }
            }
            p.setOnCompletionListener { if (playing === request) { stopPlayback(request.turn); emit(PlaybackEnded(request.turn, request.sequence)) } }
            p.setOnErrorListener { _, _, _ -> if (playing === request) { stopPlayback(request.turn); emit(Failed(request.turn, "audio-playback-failed")) }; true }
            p.prepareAsync()
        } catch (_: Exception) { stopPlayback(request.turn); emit(Failed(request.turn, "audio-prepare-failed")) }
    }
    fun stopPlayback(turn: TurnToken?) {
        if (turn != null && playing?.turn != turn) return
        val wasAudible = speakerActive
        playing = null
        main.removeCallbacks(mouthTick); mouth(0f)
        player?.let { runCatching { it.setOnCompletionListener(null); it.setOnPreparedListener(null); it.setOnErrorListener(null); it.stop() }; runCatching { it.release() } }; player = null
        focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null; speakerActive = false; headphoneRoute = false
        if (wasAudible) blockedUntil = SystemClock.elapsedRealtime() + 700
        scheduleRecognition(0)
    }
    fun cancel(turn: TurnToken) {
        val jobs = synthJobs.filterValues { it.request.turn == turn }
        jobs.values.forEach { it.cancelled = true }
        if (jobs.isNotEmpty()) tts?.stop()
        // Some system engines never issue onStop. Keep tombstones briefly, then delete files.
        main.postDelayed({ jobs.keys.forEach { id -> synthJobs.remove(id)?.file?.delete() } }, 5_000)
    }
    fun release(ref: String?) { if (ref != null) audioFiles.remove(ref)?.delete() }
    fun shutdown() {
        stopListening(); stopPlayback(null)
        ttsEpoch++
        tts?.stop(); tts?.shutdown(); tts = null; ttsReady = false; ttsFailed = false
        synthJobs.values.forEach { it.cancelled = true; it.file.delete() }; synthJobs.clear()
        audioFiles.values.forEach { it.delete() }; audioFiles.clear()
    }
}
