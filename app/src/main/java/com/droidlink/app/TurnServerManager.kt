package com.droidlink.app

import android.util.Log
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object TurnServerManager {

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

                val responseBody =
                    stream.bufferedReader()
                        .use { it.readText() }

                Log.d(
                    "DroidLink",
                    "TURN Worker response: $responseCode"
                )

                if (responseCode !in 200..299) {

                    throw IOException(
                        "TURN Worker failed: " +
                                "$responseCode $responseBody"
                    )
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

                for (
                index in
                0 until serverArray.length()
                ) {

                    val server =
                        serverArray
                            .getJSONObject(index)

                    val urlsArray =
                        server.getJSONArray(
                            "urls"
                        )

                    val urls =
                        mutableListOf<String>()

                    for (
                    urlIndex in
                    0 until urlsArray.length()
                    ) {

                        urls.add(
                            urlsArray.getString(
                                urlIndex
                            )
                        )
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