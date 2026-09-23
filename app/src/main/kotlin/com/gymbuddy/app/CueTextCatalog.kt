package com.gymbuddy.app

object CueTextCatalog {
    fun text(ruleId:String):String = when(ruleId){
        "bilateral_asymmetry" -> "Keep both sides moving together."
        "press_elbow_path_flare" -> "Keep your elbows slightly closer in."
        "lateral_raise_over_elevation" -> "Stop around shoulder height."
        else -> "Adjust your form."
    }
}
class CallbackVisualFeedbackSink(private val onShow:(String)->Unit,private val onClear:()->Unit={}):CueFeedbackSink{
    override fun onCue(cue:com.gymbuddy.domain.coaching.CueEvent)=onShow(CueTextCatalog.text(cue.ruleId))
    override fun clear()=onClear()
}
