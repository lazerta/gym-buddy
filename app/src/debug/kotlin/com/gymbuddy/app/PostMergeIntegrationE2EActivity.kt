package com.gymbuddy.app

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only entrypoint for the composed production/Room/runtime regressions. */
class PostMergeIntegrationE2EActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val status=TextView(this).apply { text="Post-merge integration tests" }
        setContentView(status)
        Thread({
            val suite=PostMergeIntegrationSuite(applicationContext)
            val cases=listOf<Pair<String,()->Unit>>(
                "attempt_checkpoint_atomicity" to suite::attemptAndRecoveryCheckpointCommitAtomically,
                "stopped_set_rejects_movement" to suite::failedFinalizationDoesNotAdmitMoreMovement,
                "retry_preserves_stop_time" to suite::retryPreservesOriginalStopTime,
                "successful_finish_is_immutable" to suite::successfulFinalizationIsImmutable,
                "runtime_room_controller_rep_agreement" to suite::runtimeControllerAndRecoveryAgreeOnRetriedLastRep,
                "rest_uses_committed_end" to suite::restUsesCommittedCompletionTime,
            )
            val results=JSONArray()
            cases.forEach { (name,run)->
                val result=JSONObject().put("name",name)
                try { run();result.put("passed",true) }
                catch(t:Throwable) { result.put("passed",false).put("error",t.stackTraceToString()) }
                results.put(result)
            }
            val passed=(0 until results.length()).all { results.getJSONObject(it).getBoolean("passed") }
            val payload=JSONObject().put("schema_version",1).put("passed",passed).put("tests",results)
            filesDir.resolve("postmerge-integration-e2e.json").writeText(payload.toString())
            runOnUiThread { status.text=if(passed)"POSTMERGE_INTEGRATION_PASS" else payload.toString() }
        },"postmerge-integration-test").start()
    }
}
