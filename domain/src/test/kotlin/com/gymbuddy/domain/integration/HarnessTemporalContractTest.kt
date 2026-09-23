package com.gymbuddy.domain.integration

import com.gymbuddy.domain.movement.MovementPrimitiveInterpreter
import com.gymbuddy.domain.movement.MovementSignalFrame
import com.gymbuddy.domain.movement.MovementSignalObservation
import com.gymbuddy.domain.movement.RepCompletionKind
import com.gymbuddy.domain.movement.RepDetectorConfig
import com.gymbuddy.domain.movement.SignalUnknownReason
import com.gymbuddy.domain.movement.TemporalRepDetector
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class HarnessTemporalContractTest {
    @Test
    fun harnessTemporalContractsMatchProductionTemporalStateMachine() {
        val fixturePath=System.getenv(FIXTURE_ENV)
        assumeTrue(
            "$FIXTURE_ENV is supplied by the read-only harness workflow",
            !fixturePath.isNullOrBlank(),
        )

        val fixture=loadFixture(File(requireNotNull(fixturePath)))
        assertEquals(EXPECTED_HARNESS_COMMIT,fixture.harnessCommit)
        assertEquals(36,fixture.cases.size)

        fixture.cases.forEach { contract ->
            val bundle=requireNotNull(
                InitialExerciseProfiles.resolveByExternalId(contract.exerciseId)
            )
            val sequence=bundle.profile.movementPrimitiveSequence
            val definitions=bundle.profile.signalProfile.definitions
                .associateBy { it.signalId }
            val progressSignalIds=sequence.steps
                .flatMap { it.progressSignalIds }
                .toSet()

            assertTrue(
                "${contract.key}: no progress signals",
                progressSignalIds.isNotEmpty(),
            )

            val interpreter=MovementPrimitiveInterpreter()
            val detector=TemporalRepDetector(
                stepConfigs=RepDetectorConfig.fromSequence(sequence),
            )
            var completed=0

            contract.frames.forEach { frame ->
                val values=progressSignalIds.associateWith { signalId ->
                    val definition=requireNotNull(definitions[signalId])

                    if(frame.valid) {
                        MovementSignalObservation(
                            signalId,
                            definition.unit,
                            frame.progress,
                            .95,
                        )
                    } else {
                        MovementSignalObservation(
                            signalId,
                            definition.unit,
                            null,
                            0.0,
                            SignalUnknownReason.MISSING_LANDMARK,
                        )
                    }
                }

                val primitiveFrame=interpreter.interpret(
                    MovementSignalFrame(
                        frame.timestampUs,
                        values,
                    ),
                    sequence,
                )

                completed += detector.update(primitiveFrame)
                    .count { it.kind==RepCompletionKind.COMPLETED }
            }

            assertEquals(
                "${contract.key} from harness ${fixture.harnessCommit}",
                contract.expectedCompletedReps,
                completed,
            )
        }
    }

    private fun loadFixture(file:File):Fixture {
        val lines=file.readLines()
        val metadata=lines
            .takeWhile { it.startsWith("#") }
            .associate { line ->
                val parts=line
                    .removePrefix("# ")
                    .split('=',limit=2)
                parts[0] to parts.getOrElse(1){""}
            }

        val cases=linkedMapOf<String,MutableContract>()

        lines
            .dropWhile { it.startsWith("#") }
            .drop(1)
            .filter { it.isNotBlank() }
            .forEach { line ->
                val p=line.split('\t')
                require(p.size==9) {
                    "Malformed harness fixture row: $line"
                }

                val contract=cases.getOrPut(p[0]) {
                    MutableContract(
                        key=p[0],
                        exerciseId=p[1],
                        caseId=p[3],
                        expectedCompletedReps=p[5].toInt(),
                    )
                }

                require(
                    contract.exerciseId==p[1] &&
                        contract.caseId==p[3] &&
                        contract.expectedCompletedReps==p[5].toInt()
                )

                contract.frames += Frame(
                    timestampUs=p[6].toLong(),
                    progress=p[7].toDouble(),
                    valid=p[8].toBooleanStrict(),
                )
            }

        return Fixture(
            harnessCommit=requireNotNull(metadata["harness_commit"]),
            cases=cases.values.map { it.freeze() },
        )
    }

    private data class Fixture(
        val harnessCommit:String,
        val cases:List<Contract>,
    )

    private data class Frame(
        val timestampUs:Long,
        val progress:Double,
        val valid:Boolean,
    )

    private data class Contract(
        val key:String,
        val exerciseId:String,
        val caseId:String,
        val expectedCompletedReps:Int,
        val frames:List<Frame>,
    )

    private data class MutableContract(
        val key:String,
        val exerciseId:String,
        val caseId:String,
        val expectedCompletedReps:Int,
        val frames:MutableList<Frame> = mutableListOf(),
    ) {
        fun freeze()=Contract(
            key,
            exerciseId,
            caseId,
            expectedCompletedReps,
            frames.toList(),
        )
    }

    companion object {
        private const val FIXTURE_ENV=
            "GYM_BUDDY_HARNESS_TEMPORAL_FIXTURE"
        private const val EXPECTED_HARNESS_COMMIT=
            "5558620bd0353547a51a14229edd4055c1d4afd9"
    }
}
