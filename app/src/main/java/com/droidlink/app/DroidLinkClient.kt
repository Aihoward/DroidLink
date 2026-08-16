package com.droidlink.app
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

class DroidLinkClient {

    private var clientThread: Thread? = null
    private var socket: Socket? = null
    private var videoDecoder: MediaCodec? = null
    private var decoderSurface: Surface? = null
    fun setupDecoder(
        width: Int,
        height: Int,
        surface: Surface
    ) {

        decoderSurface = surface

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        )

        videoDecoder = MediaCodec.createDecoderByType(
            MediaFormat.MIMETYPE_VIDEO_AVC
        )

        videoDecoder?.configure(
            format,
            surface,
            null,
            0
        )

        videoDecoder?.start()

        Log.d(
            "DroidLink",
            "H264 decoder started: ${videoDecoder?.name}"
        )
    }
    private fun decodeVideoPacket(
        data: ByteArray,
        flags: Int,
        presentationTimeUs: Long
    ) {

        val decoder = videoDecoder ?: return

        val inputIndex = decoder.dequeueInputBuffer(
            50_000
        )

        if (inputIndex >= 0) {

            val inputBuffer =
                decoder.getInputBuffer(inputIndex)

            inputBuffer?.clear()
            inputBuffer?.put(data)

            decoder.queueInputBuffer(
                inputIndex,
                0,
                data.size,
                presentationTimeUs,
                flags
            )
        }

        val bufferInfo = MediaCodec.BufferInfo()

        var outputIndex =
            decoder.dequeueOutputBuffer(
                bufferInfo,
                0
            )

        while (outputIndex >= 0) {
            Log.d(
                "DroidLink",
                "Decoded video frame ready"
            )
            decoder.releaseOutputBuffer(
                outputIndex,
                true
            )

            outputIndex =
                decoder.dequeueOutputBuffer(
                    bufferInfo,
                    0
                )
        }
    }
    fun connect(
        hostAddress: String,
        port: Int = 5000
    ) {
        if (socket?.isConnected == true && socket?.isClosed == false) {
            Log.d(
                "DroidLink",
                "Client is already connected"
            )
            return
        }
        clientThread = Thread {

            try {

                Log.d(
                    "DroidLink",
                    "Connecting to $hostAddress:$port"
                )

                socket = Socket()

                socket?.connect(
                    InetSocketAddress(
                        hostAddress,
                        port
                    ),
                    5000
                )

                Log.d(
                    "DroidLink",
                    "Connected to DroidLink Host!"
                )
                val input = socket?.getInputStream()
                    ?: return@Thread

                while (!socket!!.isClosed) {

                    val sizeBytes = ByteArray(4)

                    var bytesRead = 0

                    while (bytesRead < 4) {

                        val count = input.read(
                            sizeBytes,
                            bytesRead,
                            4 - bytesRead
                        )

                        if (count == -1) {
                            throw Exception("Host disconnected")
                        }

                        bytesRead += count
                    }

                    val packetSize =
                        ((sizeBytes[0].toInt() and 0xFF) shl 24) or
                                ((sizeBytes[1].toInt() and 0xFF) shl 16) or
                                ((sizeBytes[2].toInt() and 0xFF) shl 8) or
                                (sizeBytes[3].toInt() and 0xFF)

                    val flagsBytes = ByteArray(4)

                    bytesRead = 0

                    while (bytesRead < 4) {

                        val count = input.read(
                            flagsBytes,
                            bytesRead,
                            4 - bytesRead
                        )

                        if (count == -1) {
                            throw Exception("Host disconnected")
                        }

                        bytesRead += count
                    }

                    val packetFlags =
                        ((flagsBytes[0].toInt() and 0xFF) shl 24) or
                                ((flagsBytes[1].toInt() and 0xFF) shl 16) or
                                ((flagsBytes[2].toInt() and 0xFF) shl 8) or
                                (flagsBytes[3].toInt() and 0xFF)

                    val timestampBytes = ByteArray(8)

                    bytesRead = 0

                    while (bytesRead < 8) {

                        val count = input.read(
                            timestampBytes,
                            bytesRead,
                            8 - bytesRead
                        )

                        if (count == -1) {
                            throw Exception("Host disconnected")
                        }

                        bytesRead += count
                    }

                    var presentationTimeUs = 0L

                    for (byte in timestampBytes) {
                        presentationTimeUs =
                            (presentationTimeUs shl 8) or
                                    (byte.toLong() and 0xFF)
                    }

                    val videoPacket = ByteArray(packetSize)

                    bytesRead = 0

                    while (bytesRead < packetSize) {

                        val count = input.read(
                            videoPacket,
                            bytesRead,
                            packetSize - bytesRead
                        )

                        if (count == -1) {
                            throw Exception("Host disconnected")
                        }

                        bytesRead += count
                    }

                    Log.d(
                        "DroidLink",
                        "Received H264 packet: ${videoPacket.size} bytes"
                    )
                    decodeVideoPacket(
                        videoPacket,
                        packetFlags,
                        presentationTimeUs
                    )
                }
            } catch (e: Exception) {

                Log.e(
                    "DroidLink",
                    "Client connection failed: ${e.message}"
                )
            }

        }.apply {
            name = "DroidLinkClient"
            start()
        }
    }

    fun disconnect() {

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        socket = null
        clientThread = null

        try {
            videoDecoder?.stop()
        } catch (_: Exception) {
        }

        try {
            videoDecoder?.release()
        } catch (_: Exception) {
        }

        videoDecoder = null
        decoderSurface = null

        Log.d("DroidLink", "Legacy socket client and decoder released")
    }
}
