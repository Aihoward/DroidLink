package com.droidlink.app
import android.media.MediaCodec
import android.util.Log
import java.net.ServerSocket
import java.net.Socket

class DroidLinkServer {

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var codecConfig: ByteArray? = null
    private var codecConfigFlags: Int = 0
    private var codecConfigTimeUs: Long = 0L
    fun sendVideoPacket(
        data: ByteArray,
        flags: Int,
        presentationTimeUs: Long
    ) {
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {

            codecConfig = data.copyOf()
            codecConfigFlags = flags
            codecConfigTimeUs = presentationTimeUs

            Log.d(
                "DroidLink",
                "Saved H264 codec config: ${data.size} bytes"
            )
        }
        val client = clientSocket

        if (client == null || client.isClosed) {
            return
        }

        try {

            val output = client.getOutputStream()

            val size = data.size

            output.write(
                byteArrayOf(
                    (size shr 24).toByte(),
                    (size shr 16).toByte(),
                    (size shr 8).toByte(),
                    size.toByte()
                )
            )
            output.write(
                byteArrayOf(
                    (flags shr 24).toByte(),
                    (flags shr 16).toByte(),
                    (flags shr 8).toByte(),
                    flags.toByte()
                )
            )

            output.write(
                byteArrayOf(
                    (presentationTimeUs shr 56).toByte(),
                    (presentationTimeUs shr 48).toByte(),
                    (presentationTimeUs shr 40).toByte(),
                    (presentationTimeUs shr 32).toByte(),
                    (presentationTimeUs shr 24).toByte(),
                    (presentationTimeUs shr 16).toByte(),
                    (presentationTimeUs shr 8).toByte(),
                    presentationTimeUs.toByte()
                )
            )

            output.write(data)
            output.flush()

            Log.d(
                "DroidLink",
                "Sent video packet: ${data.size} bytes"
            )

        } catch (e: Exception) {

            Log.e(
                "DroidLink",
                "Video send failed: ${e.message}"
            )
        }
    }
    fun start() {

        if (serverThread?.isAlive == true) {
            Log.d("DroidLink", "Legacy socket server is already running")
            return
        }

        serverThread = Thread {

            try {

                serverSocket = ServerSocket(5000)

                Log.d(
                    "DroidLink",
                    "Server listening on port 5000"
                )

                clientSocket = serverSocket?.accept()

                Log.d(
                    "DroidLink",
                    "Client connected: ${clientSocket?.inetAddress?.hostAddress}"
                )
                codecConfig?.let { config ->

                    sendVideoPacket(
                        config,
                        codecConfigFlags,
                        codecConfigTimeUs
                    )

                    Log.d(
                        "DroidLink",
                        "Sent saved H264 codec config to client"
                    )
                }
            } catch (e: Exception) {

                Log.e(
                    "DroidLink",
                    "Server error: ${e.message}"
                )
            }

        }.apply {
            name = "DroidLinkServer"
            start()
        }
    }

    fun stop() {

        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (_: Exception) {
        }

        clientSocket = null
        serverSocket = null
        serverThread = null

        Log.d("DroidLink", "Legacy socket server stopped")
    }
}
