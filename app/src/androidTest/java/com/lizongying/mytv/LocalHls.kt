package com.lizongying.mytv

import android.content.res.AssetManager
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Two HLS lines served over loopback. Tests release the broken segment after observing playback. */
internal class LocalHls(assets: AssetManager) : Closeable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    // The first two byte ranges in 540.m3u8 contain eight seconds of video.
    private val clip = assets.open("resolution-test/540.ts").use { it.readBytes().copyOf(167132) }
    val firstLoads = AtomicInteger()
    val healthyLoads = AtomicInteger()
    val breakSegment = Semaphore(0)
    private val base = "http://127.0.0.1:" + socket.localPort
    val broken = "$base/broken.php"
    val healthy = "$base/healthy.php"
    val invalid = "$base/invalid.php"

    private fun playlist(broken: Boolean) = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:8\n#EXTINF:8,\nclip.ts\n")
        if (broken) append("#EXTINF:4,\nbad.ts\n")
        append("#EXT-X-ENDLIST\n")
    }.toByteArray()

    private val server = thread(name = "local-hls") {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
            workers.execute { client.use { runCatching { serve(it) } } }
        }
    }

    private fun serve(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (!reader.readLine().isNullOrEmpty()) Unit
        val body = when (path) {
            "/broken.php" -> {
                firstLoads.incrementAndGet()
                Thread.sleep(1500)
                playlist(true)
            }
            "/healthy.php" -> { healthyLoads.incrementAndGet(); playlist(false) }
            "/clip.ts" -> clip
            "/bad.ts" -> {
                breakSegment.tryAcquire(15, TimeUnit.SECONDS)
                ByteArray(8192)
            }
            else -> "invalid playlist".toByteArray()
        }
        client.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
        }
    }

    override fun close() {
        socket.close()
        server.join()
        workers.shutdownNow()
    }
}
