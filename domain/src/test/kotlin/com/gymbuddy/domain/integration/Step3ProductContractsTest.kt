package com.gymbuddy.domain.integration

import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.engine.MovementInterpretationEngine
import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.*
import org.junit.Test

class Step3ProductContractsTest {
    @Test fun searchIsDeterministicAndAliasAware(){
        val candidates=InitialExerciseProfiles.all.map{
            ExerciseSearch.Candidate(it.definition.exerciseId,it.definition.displayName,it.definition.aliases)
        }
        assertEquals("incline_dumbbell_press",ExerciseSearch.search("incline db",candidates).first().exerciseId)
        assertEquals("smith_machine_squat",ExerciseSearch.search("smith squat",candidates).single().exerciseId)
        assertTrue(ExerciseSearch.search("not-a-real-exercise",candidates).isEmpty())
        assertEquals(
            ExerciseSearch.search("raise",candidates),
            ExerciseSearch.search("  RAISE  ",candidates),
        )
    }

    @Test fun equipmentContextSpecializesProfileWithoutChangingCompatibility(){
        val bundle=InitialExerciseProfiles.smithMachineSquat
        val base=requireNotNull(bundle.equipment)
        val context=EquipmentContextRecord("smith-a",base.profileId,"Smith A",123L)
        val specialized=context.specialize(base)
        assertEquals("smith-a",specialized.profileId)
        assertNotEquals(base.semanticHash,specialized.semanticHash)
        assertEquals(base.compatibleExerciseIds,specialized.compatibleExerciseIds)
        assertEquals(base.equipmentType,specialized.equipmentType)
    }

    @Test fun loadSemanticsDoNotCollapseEqualNumbers(){
        val total=LoadSnapshot(40.0,"lb",LoadBasis.TOTAL,LoadSource.USER_ENTERED)
        val each=LoadSnapshot(40.0,"lb",LoadBasis.PER_IMPLEMENT,LoadSource.USER_ENTERED)
        val stack=LoadSnapshot(40.0,null,LoadBasis.STACK,LoadSource.USER_ENTERED)
        assertNotEquals(total,each)
        assertNotEquals(total,stack)
        assertEquals(40.0,total.value,0.0)
    }

    @Test fun repEvidenceCarriesPhaseIntervalsAndHonestAssistanceState(){
        val bundle=InitialExerciseProfiles.dumbbellLateralRaise
        val config=AnalysisConfigResolver.resolve(bundle.definition,bundle.profile,bundle.equipment)
        val event=RepDetectionEvent(
            ordinal=1,
            stepId="cycle",
            primitive=MovementPrimitive.RAISE,
            startedAtUs=100_000L,
            completedAtUs=700_000L,
            kind=RepCompletionKind.COMPLETED,
            classification=RepClassification.NORMAL,
            minConfidence=.95,
            maxAssistance=null,
        )
        val signalFrames=listOf(100_000L,300_000L,500_000L,700_000L).mapIndexed{index,ts->
            MovementSignalFrame(ts,mapOf(
                "left_progress" to MovementSignalObservation("left_progress",SignalUnit.NORMALIZED,listOf(.1,.7,.5,.1)[index],.95),
                "right_progress" to MovementSignalObservation("right_progress",SignalUnit.NORMALIZED,listOf(.1,.7,.5,.1)[index],.95),
            ))
        }
        val phases=listOf(
            PrimitivePhase.OUTBOUND,PrimitivePhase.END_RANGE,PrimitivePhase.RETURNING,PrimitivePhase.START,
        )
        val primitiveFrames=signalFrames.mapIndexed{index,frame->
            MovementPrimitiveFrame(frame.timestampUs,mapOf(
                "cycle" to MovementPrimitiveObservation(
                    "cycle",MovementPrimitive.RAISE,frame.timestampUs,phases[index],null,null,.95,
                )
            ))
        }
        val rep=RepEvidenceBuilder().build(event,signalFrames,config,"set-x",primitiveFrames)
        assertTrue(rep.phaseIntervals.size>=3)
        assertEquals(AssistanceAssessmentState.NOT_ASSESSED,rep.assistanceAssessment)
        assertNull(rep.assistanceScore)
        assertTrue(rep.phaseIntervals.all{it.durationUs>=0L})
    }

    @Test fun invalidAttemptEvidenceIsNamespacedAndStable(){
        val config=AnalysisConfigResolver.resolve(
            InitialExerciseProfiles.dumbbellLateralRaise.definition,
            InitialExerciseProfiles.dumbbellLateralRaise.profile,
            InitialExerciseProfiles.dumbbellLateralRaise.equipment,
        )
        val engine=MovementInterpretationEngine(config,idNamespace="set-x")
        val pose=normalizedLateralRaisePose(20.0)
        engine.process(0L,pose)
        engine.process(120_000L,pose)
        engine.process(300_000L,normalizedLateralRaisePose(55.0))
        val interrupted=engine.onInterruption(450_000L)
        assertEquals(1,interrupted.invalidAttempts.size)
        val attempt=interrupted.invalidAttempts.single()
        assertTrue(attempt.attemptId.startsWith("set-x/attempt-"))
        assertEquals(RepInvalidReason.INTERRUPTED,attempt.reason)
    }

    @Test fun recurringEvidenceBeatsIsolatedObservation(){
        val evidence=PersistedSetEvidence(
            set=SetRecord("set","exec",1,0L),
            analysisProvenance=provenance(),
            reps=emptyList(),
            observations=listOf(
                observation("o1","r1","bilateral_asymmetry",FormObservationState.DEVIATION),
                observation("o2","r2","bilateral_asymmetry",FormObservationState.DEVIATION),
                observation("o3","r3","press_elbow_path_flare",FormObservationState.DEVIATION),
            ),
            cues=emptyList(),responses=emptyList(),tracking=null,summary=null,
        )
        val summary=EvidenceSummaryEngine{rule->"text:$rule"}.summarize(evidence)
        assertEquals("bilateral_asymmetry",summary.focusRuleId)
        assertEquals("text:bilateral_asymmetry",summary.focusText)
        assertEquals(listOf("bilateral_asymmetry"),summary.recurringRuleIds)
    }

    @Test fun gptAnalysisRequiresCurrentSetInSourceSet(){
        assertThrows(IllegalArgumentException::class.java){
            GptAnalysisRecord(
                analysisId="a",setId="set-a",schemaVersion=1,modelLabel="model",
                createdAtEpochMs=1L,sourceSetIds=setOf("other"),summary="summary",
            )
        }
    }

    private fun observation(id:String,rep:String,rule:String,state:FormObservationState)=FormObservation(
        id,rep,rule,1,state,FormRuleSeverity.MINOR,.95,.2
    )

    private fun provenance():AnalysisProvenance{
        val ref=ProfileVersionRef("p",1,"h")
        return AnalysisProvenance("e",1,"eh",ref,ref,ref,ref,ref,ref,ref,null,null)
    }

    private fun normalizedLateralRaisePose(angle:Double):NormalizedPose{
        val hipL=BodyLocalLandmark(com.gymbuddy.domain.pose.PoseLandmarkId.LEFT_HIP,-.25,.45,0.0,.95,.95)
        val hipR=BodyLocalLandmark(com.gymbuddy.domain.pose.PoseLandmarkId.RIGHT_HIP,.25,.45,0.0,.95,.95)
        val shoulderL=BodyLocalLandmark(com.gymbuddy.domain.pose.PoseLandmarkId.LEFT_SHOULDER,-.25,-.20,0.0,.95,.95)
        val shoulderR=BodyLocalLandmark(com.gymbuddy.domain.pose.PoseLandmarkId.RIGHT_SHOULDER,.25,-.20,0.0,.95,.95)
        fun elbow(hip:BodyLocalLandmark,shoulder:BodyLocalLandmark,side:Double):BodyLocalLandmark{
            val base=kotlin.math.atan2(hip.y-shoulder.y,hip.x-shoulder.x)
            val a=base+Math.toRadians(angle*side)
            val id=if(side<0)com.gymbuddy.domain.pose.PoseLandmarkId.LEFT_ELBOW else com.gymbuddy.domain.pose.PoseLandmarkId.RIGHT_ELBOW
            return BodyLocalLandmark(id,shoulder.x+kotlin.math.cos(a)*.35,shoulder.y+kotlin.math.sin(a)*.35,0.0,.95,.95)
        }
        val elbowL=elbow(hipL,shoulderL,-1.0)
        val elbowR=elbow(hipR,shoulderR,1.0)
        return NormalizedPose(0,1.0,listOf(hipL,hipR,shoulderL,shoulderR,elbowL,elbowR).associateBy{it.landmarkId})
    }
}
