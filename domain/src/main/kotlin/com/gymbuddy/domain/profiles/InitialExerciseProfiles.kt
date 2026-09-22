package com.gymbuddy.domain.profiles

import com.gymbuddy.domain.profile.*

data class ExerciseBundle(val definition:ExerciseDefinition,val profile:ExerciseProfile,val equipment:EquipmentProfile?)

object InitialExerciseProfiles {
    private const val PRESS_ID="incline_dumbbell_press"
    private const val SQUAT_ID="smith_machine_squat"
    private const val RAISE_ID="dumbbell_lateral_raise"

    private val dumbbellGeneric by lazy { EquipmentProfile("dumbbell-generic",2,"dumbbell-generic-v2",EquipmentType.DUMBBELL,setOf(PRESS_ID,RAISE_ID)) }
    private val smithGeneric by lazy { EquipmentProfile("smith-generic",1,"smith-generic-v1",EquipmentType.SMITH_MACHINE,setOf(SQUAT_ID)) }

    private fun camera(id:String,version:Int,preferred:ViewClass,allowed:Set<ViewClass>,required:Set<String>)=CameraProfile(
        "$id-camera",version,"$id-camera-v$version",preferred,allowed,setOf(LensFacing.BACK),
        required.map{LandmarkRequirement(it,.55,.50)}.toSet(),NumericRange(.20,.95),.80,350,
        setOf(CameraGuidanceAction.CAMERA_READY,CameraGuidanceAction.CANNOT_ASSESS,CameraGuidanceAction.MOVE_CLOSER,CameraGuidanceAction.MOVE_FARTHER,CameraGuidanceAction.ADJUST_ANGLE)
    )
    private fun cue(id:String)=CuePolicy("$id-cue",1,"$id-cue-v1",3,2,15_000,2)
    private fun bilateralSignals(id:String,left:List<String>,right:List<String>,scale:Double,offset:Double)=SignalProfile(
        "$id-signals",2,"$id-signals-v2",listOf(
            SignalDefinition("left_progress",SignalKind.JOINT_ANGLE,SignalUnit.NORMALIZED,left.toSet(),mapOf("scale" to scale,"offset" to offset,"min_signal_confidence" to .50),left),
            SignalDefinition("right_progress",SignalKind.JOINT_ANGLE,SignalUnit.NORMALIZED,right.toSet(),mapOf("scale" to scale,"offset" to offset,"min_signal_confidence" to .50),right),
        )
    )
    private fun primitive(id:String,p:MovementPrimitive)=MovementPrimitiveSequence(
        "$id-primitive",2,"$id-primitive-v2",listOf(MovementPrimitiveStep("cycle",p,listOf("left_progress","right_progress"),mapOf(
            "start_max" to .20,"end_min" to .80,"stable_start_us" to 100_000.0,"pause_velocity_threshold" to .025,
            "minimum_rep_duration_us" to 250_000.0,"maximum_rep_duration_us" to 30_000_000.0,"maximum_pause_us" to 10_000_000.0,
            "minimum_confidence" to .50,"assistance_threshold" to .50,"uncertain_assistance_threshold" to .85
        )))
    )
    private fun metrics(id:String)=MetricProfile("$id-metrics",2,"$id-metrics-v2",listOf(
        MetricDefinition("left_rom",setOf("left_progress"),SignalUnit.NORMALIZED,MetricAggregation.RANGE),
        MetricDefinition("bilateral_asymmetry",setOf("left_progress","right_progress"),SignalUnit.NORMALIZED,MetricAggregation.ABS_DIFFERENCE),
        MetricDefinition("bilateral_timing_ms",setOf("left_progress","right_progress"),SignalUnit.MILLISECONDS,MetricAggregation.CROSSING_TIME_DIFFERENCE,mapOf("threshold" to .80)),
    ))
    private fun rules(id:String)=FormRuleSet("$id-rules",2,"$id-rules-v2",listOf(
        FormRule("bilateral_asymmetry",2,setOf("left_progress","right_progress"),.60,FormRuleSeverity.MINOR,FormComparison.MAX_ABS_DIFFERENCE,.18)
    ))
    private fun profile(id:String,equipment:EquipmentType,camera:CameraProfile,signals:SignalProfile,primitive:MovementPrimitiveSequence)=ExerciseProfile(
        "$id-profile",2,"$id-profile-v2",id,LateralityMode.BILATERAL,setOf(equipment),camera,signals,primitive,metrics(id),rules(id),cue(id),
        setOf(ProfileCapability.CAMERA_GUIDANCE,ProfileCapability.REP_DETECTION,ProfileCapability.FORM_ANALYSIS,ProfileCapability.BILATERAL_TIMING)
    )

    val inclineDumbbellPress:ExerciseBundle by lazy{
        val req=setOf("left_shoulder","right_shoulder","left_elbow","right_elbow","left_wrist","right_wrist","left_hip","right_hip")
        val sig=bilateralSignals(PRESS_ID,listOf("left_shoulder","left_elbow","left_wrist"),listOf("right_shoulder","right_elbow","right_wrist"),-1.0/90.0,160.0/90.0)
        val p=profile(PRESS_ID,EquipmentType.DUMBBELL,camera(PRESS_ID,2,ViewClass.SIDE_OBLIQUE,setOf(ViewClass.SIDE_OBLIQUE,ViewClass.SIDE),req),sig,primitive(PRESS_ID,MovementPrimitive.PRESS))
        ExerciseBundle(ExerciseDefinition(PRESS_ID,1,"$PRESS_ID-def-v1","Incline Dumbbell Press",setOf("incline db press","incline dumbbell bench press"),MovementFamily.PRESS),p,dumbbellGeneric)
    }
    val smithMachineSquat:ExerciseBundle by lazy{
        val req=setOf("left_shoulder","right_shoulder","left_hip","right_hip","left_knee","right_knee","left_ankle","right_ankle")
        val sig=bilateralSignals(SQUAT_ID,listOf("left_hip","left_knee","left_ankle"),listOf("right_hip","right_knee","right_ankle"),-1.0/80.0,170.0/80.0)
        val p=profile(SQUAT_ID,EquipmentType.SMITH_MACHINE,camera(SQUAT_ID,2,ViewClass.SIDE,setOf(ViewClass.SIDE,ViewClass.SIDE_OBLIQUE),req),sig,primitive(SQUAT_ID,MovementPrimitive.SQUAT))
        ExerciseBundle(ExerciseDefinition(SQUAT_ID,1,"$SQUAT_ID-def-v1","Smith Machine Squat",setOf("smith squat","smith_squat"),MovementFamily.SQUAT),p,smithGeneric)
    }
    val dumbbellLateralRaise:ExerciseBundle by lazy{
        val req=setOf("left_shoulder","right_shoulder","left_elbow","right_elbow","left_wrist","right_wrist","left_hip","right_hip")
        val sig=bilateralSignals(RAISE_ID,listOf("left_hip","left_shoulder","left_elbow"),listOf("right_hip","right_shoulder","right_elbow"),1.0/70.0,-20.0/70.0)
        val p=profile(RAISE_ID,EquipmentType.DUMBBELL,camera(RAISE_ID,2,ViewClass.FRONT,setOf(ViewClass.FRONT,ViewClass.FRONT_OBLIQUE),req),sig,primitive(RAISE_ID,MovementPrimitive.RAISE))
        ExerciseBundle(ExerciseDefinition(RAISE_ID,1,"$RAISE_ID-def-v1","Dumbbell Lateral Raise",setOf("lateral raise","db lateral raise"),MovementFamily.RAISE),p,dumbbellGeneric)
    }
    val all by lazy{listOf(inclineDumbbellPress,smithMachineSquat,dumbbellLateralRaise)}
    fun resolveByExternalId(raw:String):ExerciseBundle?{val n=raw.trim().lowercase().replace('-','_').replace(' ','_');return all.firstOrNull{b->n==b.definition.exerciseId||b.definition.aliases.any{it.lowercase().replace(' ','_')==n}}}
}
