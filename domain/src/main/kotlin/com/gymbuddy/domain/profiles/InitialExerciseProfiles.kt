package com.gymbuddy.domain.profiles

import com.gymbuddy.domain.profile.*

data class ExerciseBundle(val definition: ExerciseDefinition, val profile: ExerciseProfile, val equipment: EquipmentProfile?)

object InitialExerciseProfiles {
    private fun camera(id:String, required:Set<String>) = CameraProfile(
        "$id-camera",1,"$id-camera-v1",ViewClass.SIDE_OBLIQUE,setOf(ViewClass.SIDE_OBLIQUE,ViewClass.SIDE,ViewClass.FRONT_OBLIQUE),
        setOf(LensFacing.BACK),required.map{LandmarkRequirement(it,.55,.50)}.toSet(),NumericRange(.20,.95),.80,350,
        setOf(CameraGuidanceAction.CAMERA_READY,CameraGuidanceAction.CANNOT_ASSESS,CameraGuidanceAction.MOVE_CLOSER,CameraGuidanceAction.MOVE_FARTHER,CameraGuidanceAction.ADJUST_ANGLE)
    )
    private fun cue(id:String)=CuePolicy("$id-cue",1,"$id-cue-v1",3,2,15_000,2)
    private fun bilateralSignals(id:String,left:List<String>,right:List<String>,scale:Double,offset:Double)=SignalProfile(
        "$id-signals",1,"$id-signals-v1",listOf(
            SignalDefinition("left_progress",SignalKind.JOINT_ANGLE,SignalUnit.NORMALIZED,left.toSet(),mapOf("value_scale" to scale,"value_offset" to offset,"min_signal_confidence" to .50),left),
            SignalDefinition("right_progress",SignalKind.JOINT_ANGLE,SignalUnit.NORMALIZED,right.toSet(),mapOf("value_scale" to scale,"value_offset" to offset,"min_signal_confidence" to .50),right),
        )
    )
    private fun primitive(id:String,p:MovementPrimitive)=MovementPrimitiveSequence("$id-primitive",1,"$id-primitive-v1",listOf(MovementPrimitiveStep("cycle",p,listOf("left_progress","right_progress"),mapOf("start_max" to .20,"end_min" to .80,"stable_start_us" to 100_000.0,"pause_velocity_threshold" to .025))))
    private fun metrics(id:String)=MetricProfile("$id-metrics",1,"$id-metrics-v1",listOf(
        MetricDefinition("left_rom",setOf("left_progress"),SignalUnit.NORMALIZED,MetricAggregation.RANGE),
        MetricDefinition("bilateral_asymmetry",setOf("left_progress","right_progress"),SignalUnit.NORMALIZED,MetricAggregation.ABS_DIFFERENCE),
    ))
    private fun rules(id:String)=FormRuleSet("$id-rules",1,"$id-rules-v1",listOf(FormRule("bilateral_asymmetry",1,setOf("left_progress","right_progress"),.60,FormRuleSeverity.MINOR,FormComparison.MAX_ABS_DIFFERENCE,.18)))
    private fun profile(id:String, equipment:EquipmentType, camera:CameraProfile, signals:SignalProfile, primitive:MovementPrimitiveSequence)=ExerciseProfile(
        "$id-profile",1,"$id-profile-v1",id,LateralityMode.BILATERAL,setOf(equipment),camera,signals,primitive,metrics(id),rules(id),cue(id),
        setOf(ProfileCapability.CAMERA_GUIDANCE,ProfileCapability.REP_DETECTION,ProfileCapability.FORM_ANALYSIS,ProfileCapability.BILATERAL_TIMING)
    )

    val inclineDumbbellPress: ExerciseBundle by lazy {
        val id="incline_dumbbell_press"
        val req=setOf("left_shoulder","right_shoulder","left_elbow","right_elbow","left_wrist","right_wrist","left_hip","right_hip")
        val sig=bilateralSignals(id,listOf("left_shoulder","left_elbow","left_wrist"),listOf("right_shoulder","right_elbow","right_wrist"),-1.0/90.0,160.0/90.0)
        val p=profile(id,EquipmentType.DUMBBELL,camera(id,req),sig,primitive(id,MovementPrimitive.PRESS))
        ExerciseBundle(ExerciseDefinition(id,1,"$id-def-v1","Incline Dumbbell Press",setOf("incline db press","incline dumbbell bench press"),MovementFamily.PRESS),p,EquipmentProfile("dumbbell-generic",1,"dumbbell-generic-v1",EquipmentType.DUMBBELL,setOf(id)))
    }
    val smithMachineSquat: ExerciseBundle by lazy {
        val id="smith_machine_squat"
        val req=setOf("left_shoulder","right_shoulder","left_hip","right_hip","left_knee","right_knee","left_ankle","right_ankle")
        val sig=bilateralSignals(id,listOf("left_hip","left_knee","left_ankle"),listOf("right_hip","right_knee","right_ankle"),-1.0/80.0,170.0/80.0)
        val p=profile(id,EquipmentType.SMITH_MACHINE,camera(id,req),sig,primitive(id,MovementPrimitive.SQUAT))
        ExerciseBundle(ExerciseDefinition(id,1,"$id-def-v1","Smith Machine Squat",setOf("smith squat","smith_squat"),MovementFamily.SQUAT),p,EquipmentProfile("smith-generic",1,"smith-generic-v1",EquipmentType.SMITH_MACHINE,setOf(id)))
    }
    val dumbbellLateralRaise: ExerciseBundle by lazy {
        val id="dumbbell_lateral_raise"
        val req=setOf("left_shoulder","right_shoulder","left_elbow","right_elbow","left_hip","right_hip")
        val sig=bilateralSignals(id,listOf("left_hip","left_shoulder","left_elbow"),listOf("right_hip","right_shoulder","right_elbow"),1.0/70.0,-20.0/70.0)
        val p=profile(id,EquipmentType.DUMBBELL,camera(id,req),sig,primitive(id,MovementPrimitive.RAISE))
        ExerciseBundle(ExerciseDefinition(id,1,"$id-def-v1","Dumbbell Lateral Raise",setOf("lateral raise","db lateral raise"),MovementFamily.RAISE),p,EquipmentProfile("dumbbell-generic",1,"dumbbell-generic-v1",EquipmentType.DUMBBELL,setOf(id)))
    }

    val all: List<ExerciseBundle> by lazy { listOf(inclineDumbbellPress,smithMachineSquat,dumbbellLateralRaise) }
    fun resolveByExternalId(raw:String):ExerciseBundle? {
        val normalized=raw.trim().lowercase().replace('-', '_').replace(' ','_')
        return all.firstOrNull { b -> normalized==b.definition.exerciseId || b.definition.aliases.any { it.lowercase().replace(' ','_')==normalized } }
    }
}
