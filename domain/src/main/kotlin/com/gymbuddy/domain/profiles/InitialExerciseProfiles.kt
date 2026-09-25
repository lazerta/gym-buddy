package com.gymbuddy.domain.profiles

import com.gymbuddy.domain.profile.*

data class ExerciseBundle(val definition:ExerciseDefinition,val profile:ExerciseProfile,val equipment:EquipmentProfile?)

object InitialExerciseProfiles {
    // v4 records coordinated primary-signal interruption semantics. Existing
    // metric, signal and rule definitions are unchanged; historical v3 sets
    // retain their original provenance and are not silently reinterpreted.
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
    private fun inclinePressSignals()=SignalProfile(
        "$PRESS_ID-signals",3,"$PRESS_ID-signals-v3",listOf(
            SignalDefinition(
                "left_progress",SignalKind.JOINT_ANGLE,SignalUnit.NORMALIZED,
                setOf("left_shoulder","left_elbow","left_wrist"),
                mapOf("scale" to -1.0/90.0,"offset" to 160.0/90.0,"min_signal_confidence" to .50),
                listOf("left_shoulder","left_elbow","left_wrist"),
            ),
            SignalDefinition(
                "right_progress",SignalKind.JOINT_ANGLE,SignalUnit.NORMALIZED,
                setOf("right_shoulder","right_elbow","right_wrist"),
                mapOf("scale" to -1.0/90.0,"offset" to 160.0/90.0,"min_signal_confidence" to .50),
                listOf("right_shoulder","right_elbow","right_wrist"),
            ),
            SignalDefinition(
                "left_elbow_path_angle",SignalKind.JOINT_ANGLE,SignalUnit.DEGREES,
                setOf("left_hip","left_shoulder","left_elbow"),
                mapOf("min_signal_confidence" to .50),
                listOf("left_hip","left_shoulder","left_elbow"),
            ),
            SignalDefinition(
                "right_elbow_path_angle",SignalKind.JOINT_ANGLE,SignalUnit.DEGREES,
                setOf("right_hip","right_shoulder","right_elbow"),
                mapOf("min_signal_confidence" to .50),
                listOf("right_hip","right_shoulder","right_elbow"),
            ),
        )
    )
    private fun lateralRaiseSignals()=SignalProfile(
        "$RAISE_ID-signals",3,"$RAISE_ID-signals-v3",listOf(
            SignalDefinition(
                "left_progress",SignalKind.JOINT_ANGLE,SignalUnit.NORMALIZED,
                setOf("left_hip","left_shoulder","left_elbow"),
                mapOf("scale" to 1.0/70.0,"offset" to -20.0/70.0,"min_signal_confidence" to .50),
                listOf("left_hip","left_shoulder","left_elbow"),
            ),
            SignalDefinition(
                "right_progress",SignalKind.JOINT_ANGLE,SignalUnit.NORMALIZED,
                setOf("right_hip","right_shoulder","right_elbow"),
                mapOf("scale" to 1.0/70.0,"offset" to -20.0/70.0,"min_signal_confidence" to .50),
                listOf("right_hip","right_shoulder","right_elbow"),
            ),
            SignalDefinition(
                "left_arm_elevation_deg",SignalKind.JOINT_ANGLE,SignalUnit.DEGREES,
                setOf("left_hip","left_shoulder","left_elbow"),
                mapOf("min_signal_confidence" to .50),
                listOf("left_hip","left_shoulder","left_elbow"),
            ),
            SignalDefinition(
                "right_arm_elevation_deg",SignalKind.JOINT_ANGLE,SignalUnit.DEGREES,
                setOf("right_hip","right_shoulder","right_elbow"),
                mapOf("min_signal_confidence" to .50),
                listOf("right_hip","right_shoulder","right_elbow"),
            ),
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
    private fun inclinePressMetrics()=MetricProfile("$PRESS_ID-metrics",3,"$PRESS_ID-metrics-v3",listOf(
        MetricDefinition("left_rom",setOf("left_progress"),SignalUnit.NORMALIZED,MetricAggregation.RANGE),
        MetricDefinition("right_rom",setOf("right_progress"),SignalUnit.NORMALIZED,MetricAggregation.RANGE),
        MetricDefinition("bilateral_asymmetry",setOf("left_progress","right_progress"),SignalUnit.NORMALIZED,MetricAggregation.ABS_DIFFERENCE),
        MetricDefinition("bilateral_timing_ms",setOf("left_progress","right_progress"),SignalUnit.MILLISECONDS,MetricAggregation.CROSSING_TIME_DIFFERENCE,mapOf("threshold" to .80)),
        MetricDefinition("press_elbow_path_flare_deg",setOf("left_elbow_path_angle","right_elbow_path_angle"),SignalUnit.DEGREES,MetricAggregation.MAX),
    ))
    private fun smithSquatMetrics()=MetricProfile("$SQUAT_ID-metrics",3,"$SQUAT_ID-metrics-v3",listOf(
        MetricDefinition("left_rom",setOf("left_progress"),SignalUnit.NORMALIZED,MetricAggregation.RANGE),
        MetricDefinition("right_rom",setOf("right_progress"),SignalUnit.NORMALIZED,MetricAggregation.RANGE),
        MetricDefinition("bilateral_asymmetry",setOf("left_progress","right_progress"),SignalUnit.NORMALIZED,MetricAggregation.ABS_DIFFERENCE),
        MetricDefinition("bilateral_timing_ms",setOf("left_progress","right_progress"),SignalUnit.MILLISECONDS,MetricAggregation.CROSSING_TIME_DIFFERENCE,mapOf("threshold" to .80)),
    ))
    private fun lateralRaiseMetrics()=MetricProfile("$RAISE_ID-metrics",3,"$RAISE_ID-metrics-v3",listOf(
        MetricDefinition("left_rom",setOf("left_progress"),SignalUnit.NORMALIZED,MetricAggregation.RANGE),
        MetricDefinition("right_rom",setOf("right_progress"),SignalUnit.NORMALIZED,MetricAggregation.RANGE),
        MetricDefinition("bilateral_asymmetry",setOf("left_progress","right_progress"),SignalUnit.NORMALIZED,MetricAggregation.ABS_DIFFERENCE),
        MetricDefinition("bilateral_timing_ms",setOf("left_progress","right_progress"),SignalUnit.MILLISECONDS,MetricAggregation.CROSSING_TIME_DIFFERENCE,mapOf("threshold" to .80)),
        MetricDefinition("arm_elevation_peak_deg",setOf("left_arm_elevation_deg","right_arm_elevation_deg"),SignalUnit.DEGREES,MetricAggregation.MAX),
    ))
    private fun rules(id:String)=FormRuleSet("$id-rules",2,"$id-rules-v2",listOf(
        FormRule("bilateral_asymmetry",2,setOf("left_progress","right_progress"),.60,FormRuleSeverity.MINOR,FormComparison.MAX_ABS_DIFFERENCE,.18)
    ))
    private fun inclinePressRules()=FormRuleSet("$PRESS_ID-rules",3,"$PRESS_ID-rules-v3",listOf(
        FormRule("bilateral_asymmetry",2,setOf("left_progress","right_progress"),.60,FormRuleSeverity.MINOR,FormComparison.MAX_ABS_DIFFERENCE,.18),
        FormRule("press_elbow_path_flare",1,setOf("left_elbow_path_angle","right_elbow_path_angle"),.60,FormRuleSeverity.MINOR,FormComparison.MAX_VALUE,80.0),
    ))
    private fun lateralRaiseRules()=FormRuleSet("$RAISE_ID-rules",3,"$RAISE_ID-rules-v3",listOf(
        FormRule("bilateral_asymmetry",2,setOf("left_progress","right_progress"),.60,FormRuleSeverity.MINOR,FormComparison.MAX_ABS_DIFFERENCE,.18),
        FormRule("lateral_raise_over_elevation",1,setOf("left_arm_elevation_deg","right_arm_elevation_deg"),.60,FormRuleSeverity.MINOR,FormComparison.MAX_VALUE,105.0),
    ))
    private fun profile(
        id:String,
        equipment:EquipmentType,
        camera:CameraProfile,
        signals:SignalProfile,
        primitive:MovementPrimitiveSequence,
        metricProfile:MetricProfile=metrics(id),
        formRuleSet:FormRuleSet=rules(id),
        version:Int=2,
    )=ExerciseProfile(
        "$id-profile",version,"$id-profile-v$version",id,LateralityMode.BILATERAL,setOf(equipment),camera,signals,primitive,metricProfile,formRuleSet,cue(id),
        setOf(ProfileCapability.CAMERA_GUIDANCE,ProfileCapability.REP_DETECTION,ProfileCapability.FORM_ANALYSIS,ProfileCapability.BILATERAL_TIMING)
    )

    val inclineDumbbellPress:ExerciseBundle by lazy{
        val req=setOf("left_shoulder","right_shoulder","left_elbow","right_elbow","left_wrist","right_wrist","left_hip","right_hip")
        val p=profile(
            PRESS_ID,
            EquipmentType.DUMBBELL,
            camera(PRESS_ID,2,ViewClass.SIDE_OBLIQUE,setOf(ViewClass.SIDE_OBLIQUE,ViewClass.SIDE),req),
            inclinePressSignals(),
            primitive(PRESS_ID,MovementPrimitive.PRESS),
            metricProfile=inclinePressMetrics(),
            formRuleSet=inclinePressRules(),
            version=4,
        )
        ExerciseBundle(ExerciseDefinition(PRESS_ID,1,"$PRESS_ID-def-v1","Incline Dumbbell Press",setOf("incline db press","incline dumbbell bench press"),MovementFamily.PRESS),p,dumbbellGeneric)
    }
    val smithMachineSquat:ExerciseBundle by lazy{
        val req=setOf("left_shoulder","right_shoulder","left_hip","right_hip","left_knee","right_knee","left_ankle","right_ankle")
        val sig=bilateralSignals(SQUAT_ID,listOf("left_hip","left_knee","left_ankle"),listOf("right_hip","right_knee","right_ankle"),-1.0/80.0,170.0/80.0)
        val p=profile(
            SQUAT_ID,
            EquipmentType.SMITH_MACHINE,
            camera(SQUAT_ID,2,ViewClass.SIDE,setOf(ViewClass.SIDE,ViewClass.SIDE_OBLIQUE),req),
            sig,
            primitive(SQUAT_ID,MovementPrimitive.SQUAT),
            metricProfile=smithSquatMetrics(),
            version=4,
        )
        ExerciseBundle(ExerciseDefinition(SQUAT_ID,1,"$SQUAT_ID-def-v1","Smith Machine Squat",setOf("smith squat","smith_squat"),MovementFamily.SQUAT),p,smithGeneric)
    }
    val dumbbellLateralRaise:ExerciseBundle by lazy{
        val req=setOf("left_shoulder","right_shoulder","left_elbow","right_elbow","left_wrist","right_wrist","left_hip","right_hip")
        val p=profile(
            RAISE_ID,
            EquipmentType.DUMBBELL,
            camera(RAISE_ID,2,ViewClass.FRONT,setOf(ViewClass.FRONT,ViewClass.FRONT_OBLIQUE),req),
            lateralRaiseSignals(),
            primitive(RAISE_ID,MovementPrimitive.RAISE),
            metricProfile=lateralRaiseMetrics(),
            formRuleSet=lateralRaiseRules(),
            version=4,
        )
        ExerciseBundle(ExerciseDefinition(RAISE_ID,1,"$RAISE_ID-def-v1","Dumbbell Lateral Raise",setOf("lateral raise","db lateral raise"),MovementFamily.RAISE),p,dumbbellGeneric)
    }
    val all by lazy{listOf(inclineDumbbellPress,smithMachineSquat,dumbbellLateralRaise)}
    fun resolveByExternalId(raw:String):ExerciseBundle?{val n=raw.trim().lowercase().replace('-','_').replace(' ','_');return all.firstOrNull{b->n==b.definition.exerciseId||b.definition.aliases.any{it.lowercase().replace(' ','_')==n}}}
}
