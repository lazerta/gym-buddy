package com.gymbuddy.app

import com.gymbuddy.frames.SimulatorFrameDescriptor
import com.gymbuddy.frames.SimulatorResultEnvelope
import com.gymbuddy.frames.SimulatorSession
import com.gymbuddy.frames.SimulatorTransport
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HttpSimulatorTransport(
    baseUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
) : SimulatorTransport {

    private val root = baseUrl.trimEnd('/')

    override fun loadSession(): SimulatorSession {
        val json = JSONObject(
            requestBytes(
                path = "/v1/session",
                method = "GET",
            ).toString(Charsets.UTF_8)
        )

        val framesJson = json.getJSONArray("frames")
        val frames = buildList {
            for (i in 0 until framesJson.length()) {
                val item = framesJson.getJSONObject(i)
                add(
                    SimulatorFrameDescriptor(
                        frameId = item.getLong("frame_id"),
                        timestampUs = item.getLong("timestamp_us"),
                        width = item.getInt("width"),
                        height = item.getInt("height"),
                        mimeType = item.getString("mime_type"),
                    )
                )
            }
        }

        return SimulatorSession(
            schemaVersion = json.getInt("schema_version"),
            sessionId = json.getString("session_id"),
            exerciseId = json.getString("exercise_id"),
            fps = json.getDouble("fps"),
            width = json.getInt("width"),
            height = json.getInt("height"),
            frames = frames,
        )
    }

    override fun loadFrame(
        frame: SimulatorFrameDescriptor,
    ): ByteArray {
        return requestBytes(
            path = "/v1/frames/" + frame.frameId,
            method = "GET",
        )
    }

    override fun submitResult(
        result: SimulatorResultEnvelope,
    ) {
        val payload = JSONObject()
            .put("schema_version", result.schemaVersion)
            .put("session_id", result.sessionId)
            .put("frame_id", result.frameId)
            .put("timestamp_us", result.timestampUs)
            .put("analysis", toJsonValue(result.analysis))

        requestBytes(
            path = "/v1/results",
            method = "POST",
            body = payload.toString()
                .toByteArray(Charsets.UTF_8),
        )
    }

    private fun requestBytes(
        path: String,
        method: String,
        body: ByteArray? = null,
    ): ByteArray {
        val connection = URL(root + path)
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty(
                "Accept",
                "application/json,image/jpeg,image/png",
            )

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json",
                )
                connection.outputStream.use { output ->
                    output.write(body)
                }
            }

            val code = connection.responseCode
            if (code in 200..299) {
                return connection.inputStream.use {
                    it.readBytes()
                }
            }

            val errorText = connection.errorStream
                ?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                .orEmpty()

            error(
                "HTTP " + code +
                    " from " + path +
                    ": " + errorText
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun toJsonValue(value: Any?): Any? {
        return when (value) {
            null -> JSONObject.NULL

            is Map<*, *> -> JSONObject().apply {
                value.forEach { entry ->
                    val key = entry.key
                    require(key is String) {
                        "JSON object keys must be strings"
                    }
                    put(key, toJsonValue(entry.value))
                }
            }

            is Iterable<*> -> JSONArray().apply {
                value.forEach {
                    put(toJsonValue(it))
                }
            }

            is Array<*> -> JSONArray().apply {
                value.forEach {
                    put(toJsonValue(it))
                }
            }

            else -> value
        }
    }
}
