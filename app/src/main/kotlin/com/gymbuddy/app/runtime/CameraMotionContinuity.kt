package com.gymbuddy.app.runtime

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal data class CameraMotionSample(
    val luminance:IntArray,
){
    init{require(luminance.isNotEmpty())}
}

internal class CameraMotionContinuityEstimator(
    private val changedPixelDelta:Int=40,
){
    private var previous:CameraMotionSample?=null

    init{require(changedPixelDelta in 1..255)}

    fun reset(){previous=null}

    fun update(sample:CameraMotionSample):Double?{
        val prior=previous
        previous=sample
        if(prior==null||prior.luminance.size!=sample.luminance.size)return null
        var changed=0
        sample.luminance.indices.forEach{index->
            if(abs(sample.luminance[index]-prior.luminance[index])>=changedPixelDelta)changed++
        }
        return changed.toDouble()/sample.luminance.size
    }
}

internal object CameraMotionSampler{
    fun sample(
        bitmap:Bitmap,
        columns:Int=24,
        rows:Int=18,
        borderFraction:Double=.25,
    ):CameraMotionSample{
        require(columns>=4&&rows>=4)
        require(borderFraction in 0.10..0.45)
        val values=ArrayList<Int>(columns*rows)
        for(row in 0 until rows){
            val y=((row+.5)*bitmap.height/rows).toInt().coerceIn(0,bitmap.height-1)
            for(column in 0 until columns){
                val nx=(column+.5)/columns
                val ny=(row+.5)/rows
                val border=nx<=borderFraction||nx>=1.0-borderFraction||
                    ny<=borderFraction||ny>=1.0-borderFraction
                if(!border)continue
                val x=((column+.5)*bitmap.width/columns).toInt().coerceIn(0,bitmap.width-1)
                val color=bitmap.getPixel(x,y)
                val red=(color shr 16) and 0xff
                val green=(color shr 8) and 0xff
                val blue=color and 0xff
                values+=(77*red+150*green+29*blue) shr 8
            }
        }
        return CameraMotionSample(values.toIntArray())
    }
}

class CameraMotionSignalStore{
    private val scores=ConcurrentHashMap<Long,Double>()

    fun publish(timestampUs:Long,score:Double?){
        require(timestampUs>=0L)
        if(score==null)scores.remove(timestampUs)
        else{
            require(score.isFinite()&&score in 0.0..1.0)
            scores[timestampUs]=score
        }
    }

    fun consume(timestampUs:Long):Double?=scores.remove(timestampUs)

    fun clear(){scores.clear()}
}
