package com.gymbuddy.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gymbuddy.domain.coaching.CueEvent
import com.gymbuddy.domain.persistence.CueDeliveryRecord
import com.gymbuddy.domain.persistence.CueDeliveryState

class TextToSpeechCueSink(
    context:Context,
    private val onDelivery:(CueDeliveryRecord)->Unit = {},
):CueFeedbackSink,AutoCloseable {
    @Volatile private var ready=false
    @Volatile private var activeCueId:String?=null
    private lateinit var tts:TextToSpeech

    init {
        tts=TextToSpeech(context.applicationContext){status->
            if(status!=TextToSpeech.SUCCESS){ready=false;return@TextToSpeech}
            val localVoices=tts.voices?.filterNot{it.isNetworkConnectionRequired}.orEmpty()
            val localVoice=localVoices.filter{it.locale.language==java.util.Locale.ENGLISH.language}.sortedBy{it.name}.firstOrNull()
                ?: localVoices.sortedBy{it.name}.firstOrNull()
            ready=localVoice!=null&&tts.setVoice(localVoice)!=TextToSpeech.ERROR
        }
        tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
            override fun onStart(utteranceId:String){activeCueId=utteranceId;onDelivery(CueDeliveryRecord(utteranceId,CueDeliveryState.STARTED))}
            override fun onDone(utteranceId:String){if(activeCueId==utteranceId)activeCueId=null;onDelivery(CueDeliveryRecord(utteranceId,CueDeliveryState.COMPLETED))}
            @Deprecated("Deprecated in Java") override fun onError(utteranceId:String){if(activeCueId==utteranceId)activeCueId=null;onDelivery(CueDeliveryRecord(utteranceId,CueDeliveryState.FAILED))}
            override fun onError(utteranceId:String,errorCode:Int){onError(utteranceId)}
            override fun onStop(utteranceId:String,interrupted:Boolean){
                if(activeCueId==utteranceId){activeCueId=null;onDelivery(CueDeliveryRecord(utteranceId,CueDeliveryState.CANCELLED))}
            }
        })
    }

    override fun onCue(cue:CueEvent){
        if(!ready){onDelivery(CueDeliveryRecord(cue.cueId,CueDeliveryState.FAILED));return}
        val result=tts.speak(CueTextCatalog.text(cue.ruleId),TextToSpeech.QUEUE_FLUSH,null,cue.cueId)
        if(result==TextToSpeech.ERROR)onDelivery(CueDeliveryRecord(cue.cueId,CueDeliveryState.FAILED))
    }
    override fun clear(){
        activeCueId?.let{onDelivery(CueDeliveryRecord(it,CueDeliveryState.CANCELLED));activeCueId=null}
        if(ready)tts.stop()
    }
    override fun close(){clear();ready=false;tts.stop();tts.shutdown()}
}
