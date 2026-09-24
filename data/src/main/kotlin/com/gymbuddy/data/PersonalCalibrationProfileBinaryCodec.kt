package com.gymbuddy.data

import com.gymbuddy.domain.profile.BaselineStatistic
import com.gymbuddy.domain.profile.ExerciseBaseline
import com.gymbuddy.domain.profile.ExerciseBaselineKey
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import com.gymbuddy.domain.profile.ViewClass
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

internal object PersonalCalibrationProfileBinaryCodec {
    private const val FORMAT_VERSION=1

    fun encode(profile:PersonalCalibrationProfile):String{
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{out->
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(profile.calibrationProfileId)
            out.writeInt(profile.profileVersion)
            out.writeUTF(profile.semanticHash)
            out.writeDouble(profile.sourceConfidence)
            writeNullableDoubleMap(out,profile.normalizedBodyGeometry)
            writeNullableDoubleMap(out,profile.cameraSetupPreferences)
            out.writeInt(profile.exerciseBaselines.size)
            profile.exerciseBaselines.forEach{writeBaseline(out,it)}
            writeStringSet(out,profile.equipmentAssociations)
            writeNullableDoubleMap(out,profile.lateralityBaseline)
            writeNullableDoubleMap(out,profile.cueEffectiveness)
            writeStringSet(out,profile.evidenceReferences)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(payload:String):PersonalCalibrationProfile{
        val raw=Base64.getDecoder().decode(payload)
        return DataInputStream(ByteArrayInputStream(raw)).use{input->
            require(input.readInt()==FORMAT_VERSION){"unsupported personal calibration payload version"}
            val calibrationProfileId=input.readUTF()
            val profileVersion=input.readInt()
            val semanticHash=input.readUTF()
            val sourceConfidence=input.readDouble()
            val normalizedBodyGeometry=readNullableDoubleMap(input)
            val cameraSetupPreferences=readNullableDoubleMap(input)
            val baselines=List(input.readInt()){readBaseline(input)}
            val equipmentAssociations=readStringSet(input)
            val lateralityBaseline=readNullableDoubleMap(input)
            val cueEffectiveness=readNullableDoubleMap(input)
            val evidenceReferences=readStringSet(input)
            require(input.available()==0){"unexpected trailing personal calibration payload bytes"}
            PersonalCalibrationProfile(
                calibrationProfileId=calibrationProfileId,
                profileVersion=profileVersion,
                semanticHash=semanticHash,
                sourceConfidence=sourceConfidence,
                normalizedBodyGeometry=normalizedBodyGeometry,
                cameraSetupPreferences=cameraSetupPreferences,
                exerciseBaselines=baselines,
                equipmentAssociations=equipmentAssociations,
                lateralityBaseline=lateralityBaseline,
                cueEffectiveness=cueEffectiveness,
                evidenceReferences=evidenceReferences,
            )
        }
    }

    private fun writeBaseline(out:DataOutputStream,baseline:ExerciseBaseline){
        out.writeUTF(baseline.profileId)
        out.writeInt(baseline.profileVersion)
        out.writeUTF(baseline.semanticHash)
        out.writeUTF(baseline.key.exerciseProfileId)
        out.writeInt(baseline.key.exerciseProfileVersion)
        writeNullableString(out,baseline.key.equipmentProfileId)
        writeNullableString(out,baseline.key.viewClass?.name)
        val metrics=baseline.metricStatistics.toSortedMap()
        out.writeInt(metrics.size)
        metrics.forEach{(metricId,statistic)->
            out.writeUTF(metricId)
            writeNullableDouble(out,statistic.median)
            writeNullableDouble(out,statistic.lowerBound)
            writeNullableDouble(out,statistic.upperBound)
            out.writeInt(statistic.sampleCount)
            out.writeInt(statistic.sessionCount)
            out.writeDouble(statistic.confidence)
        }
    }

    private fun readBaseline(input:DataInputStream):ExerciseBaseline{
        val profileId=input.readUTF()
        val profileVersion=input.readInt()
        val semanticHash=input.readUTF()
        val key=ExerciseBaselineKey(
            exerciseProfileId=input.readUTF(),
            exerciseProfileVersion=input.readInt(),
            equipmentProfileId=readNullableString(input),
            viewClass=readNullableString(input)?.let(ViewClass::valueOf),
        )
        val metrics=linkedMapOf<String,BaselineStatistic>()
        repeat(input.readInt()){
            val metricId=input.readUTF()
            metrics[metricId]=BaselineStatistic(
                median=readNullableDouble(input),
                lowerBound=readNullableDouble(input),
                upperBound=readNullableDouble(input),
                sampleCount=input.readInt(),
                sessionCount=input.readInt(),
                confidence=input.readDouble(),
            )
        }
        return ExerciseBaseline(profileId,profileVersion,semanticHash,key,metrics)
    }

    private fun writeNullableDoubleMap(out:DataOutputStream,values:Map<String,Double?>){
        val sorted=values.toSortedMap()
        out.writeInt(sorted.size)
        sorted.forEach{(key,value)->out.writeUTF(key);writeNullableDouble(out,value)}
    }

    private fun readNullableDoubleMap(input:DataInputStream):Map<String,Double?>{
        val result=linkedMapOf<String,Double?>()
        repeat(input.readInt()){result[input.readUTF()]=readNullableDouble(input)}
        return result
    }

    private fun writeStringSet(out:DataOutputStream,values:Set<String>){
        val sorted=values.sorted()
        out.writeInt(sorted.size)
        sorted.forEach(out::writeUTF)
    }

    private fun readStringSet(input:DataInputStream):Set<String>{
        val result=linkedSetOf<String>()
        repeat(input.readInt()){result+=input.readUTF()}
        return result
    }

    private fun writeNullableDouble(out:DataOutputStream,value:Double?){
        out.writeBoolean(value!=null)
        if(value!=null)out.writeDouble(value)
    }

    private fun readNullableDouble(input:DataInputStream):Double?=
        if(input.readBoolean())input.readDouble() else null

    private fun writeNullableString(out:DataOutputStream,value:String?){
        out.writeBoolean(value!=null)
        if(value!=null)out.writeUTF(value)
    }

    private fun readNullableString(input:DataInputStream):String?=
        if(input.readBoolean())input.readUTF() else null
}
