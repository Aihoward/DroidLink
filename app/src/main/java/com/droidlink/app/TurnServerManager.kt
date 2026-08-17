package com.droidlink.app

import android.util.Log
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object TurnServerManager {

    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val MAX_ICE_SERVERS = 12

    private const val WORKER_URL =
        "https://droidlink-turnn.camperkins30.workers.dev/"

    fun fetchIceServers(
        onResult: (Result<List<PeerConnection.IceServer>>) -> Unit
    ) {

        Thread {

            try {

                Log.d(
                    "DroidLink",
                    "Requesting TURN credentials..."
                )

                val connection =
                    URL(WORKER_URL)
                        .openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use { output ->
                    output.write(
                        "{}".toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                val responseCode =
                    connection.responseCode

                val stream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val responseBytes = stream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("TURN response exceeded safe limit")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val responseBody = responseBytes.toString(Charsets.UTF_8)

                Log.d(
                    "DroidLink",
                    "TURN Worker response: $responseCode"
                )

                if (responseCode !in 200..299) {

                    throw IOException("TURN Worker failed with HTTP $responseCode")
                }

                val json =
                    JSONObject(responseBody)

                val serverArray =
                    json.getJSONArray(
                        "iceServers"
                    )

                val servers =
                    mutableListOf<
                            PeerConnection.IceServer
                            >()

                if (serverArray.length() !in 1..MAX_ICE_SERVERS) throw IOException("TURN response contained an invalid server count")
                for (index in 0 until serverArray.length()) {

                    val server =
                        serverArray
                            .getJSONObject(index)

                    val urlsArray =
                        server.getJSONArray(
                            "urls"
                        )

                    val urls =
                        mutableListOf<String>()

                    if (urlsArray.length() !in 1..8) throw IOException("TURN response contained an invalid URL count")
                    for (urlIndex in 0 until urlsArray.length()) {
                        val iceUrl = urlsArray.getString(urlIndex)
                        if (iceUrl.length > 1024 || !(iceUrl.startsWith("stun:") || iceUrl.startsWith("turn:") || iceUrl.startsWith("turns:"))) {
                            throw IOException("TURN response contained an invalid ICE URL")
                        }
                        urls.add(iceUrl)
                    }

                    val builder =
                        PeerConnection.IceServer
                            .builder(urls)

                    val username =
                        server.optString(
                            "username",
                            ""
                        )

                    val credential =
                        server.optString(
                            "credential",
                            ""
                        )

                    if (username.length > 1024 || credential.length > 2048) throw IOException("TURN credentials exceeded safe limits")

                    if (
                        username.isNotEmpty()
                    ) {
                        builder.setUsername(
                            username
                        )
                    }

                    if (
                        credential.isNotEmpty()
                    ) {
                        builder.setPassword(
                            credential
                        )
                    }

                    servers.add(
                        builder.createIceServer()
                    )
                }

                Log.d(
                    "DroidLink",
                    "TURN READY: ${servers.size} ICE server entries"
                )

                onResult(
                    Result.success(
                        servers
                    )
                )

                connection.disconnect()

            } catch (
                error: Exception
            ) {

                Log.e(
                    "DroidLink",
                    "TURN ERROR",
                    error
                )

                onResult(
                    Result.failure(
                        error
                    )
                )
            }
        }.start()
    }
}
