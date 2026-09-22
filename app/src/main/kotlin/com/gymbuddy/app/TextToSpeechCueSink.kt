package com.gymbuddy.app

import android.content.Context
import android.speech.tts.TextToSpeech
import com.gymbuddy.domain.coaching.CueEvent

class TextToSpeechCueSink(
    context: Context,
) : CueFeedbackSink, AutoCloseable {
    @Volatile
    private var ready = false

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    override fun onCue(cue: CueEvent) {
        if (!ready) return
        tts.speak(
            CueTextCatalog.text(cue.ruleId),
            TextToSpeech.QUEUE_FLUSH,
            null,
            cue.cueId,
        )
    }

    override fun clear() {
        if (ready) tts.stop()
    }

    override fun close() {
        ready = false
        tts.stop()
        tts.shutdown()
    }
}
