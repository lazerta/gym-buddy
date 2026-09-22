package com.gymbuddy.app

object CueTextCatalog {
    fun text(ruleId:String):String = when(ruleId){
        "bilateral_asymmetry" -> "Keep both sides moving together."
        else -> "Adjust your form."
    }
}
class CallbackVisualFeedbackSink(private val onShow:(String)->Unit,private val onClear:()->Unit={}):CueFeedbackSink{
    override fun onCue(cue:com.gymbuddy.domain.coaching.CueEvent)=onShow(CueTextCatalog.text(cue.ruleId))
    override fun clear()=onClear()
}
