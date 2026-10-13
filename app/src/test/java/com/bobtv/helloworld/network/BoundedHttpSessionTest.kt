package com.bobtv.helloworld.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class BoundedHttpSessionTest {
    private fun <T> withServer(body: ByteArray, status: String = "200 OK", block: (String) -> T): T {
        val server = ServerSocket(0)
        server.soTimeout = 2000
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) {}
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 " + status + "\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                    output.write(body)
                    output.flush()
                }
            } catch (_: IOException) { /* The bounded reader may deliberately close early. */ }
        }
        worker.start()
        try { return block("http://127.0.0.1:" + server.localPort + "/data") }
        finally { server.close(); worker.join(3000); check(!worker.isAlive) { "HTTP test server did not stop" } }
    }

    @Test fun readsHttpAndRejectsMissingResponses() {
        withServer("#EXTM3U".toByteArray()) { url ->
            BoundedHttpSession(1000).use {
                assertEquals("#EXTM3U", it.fetch(url, 100).bytes.toString(Charsets.UTF_8))
            }
        }
        withServer("missing".toByteArray(), "404 Not Found") { url ->
            BoundedHttpSession(1000).use {
                val error = assertThrows(IOException::class.java) { it.fetch(url, 100) }
                assertTrue(error.message!!.contains("HTTP 404"))
            }
        }
    }

    @Test fun manifestsCannotExceedTheirLimitButSamplesMayBePartial() {
        withServer(ByteArray(100_000)) { url ->
            BoundedHttpSession(1000).use {
                assertThrows(IOException::class.java) { it.fetch(url, 1024) }
            }
        }
        withServer(ByteArray(100_000)) { url ->
            BoundedHttpSession(1000).use {
                assertEquals(1024, it.fetch(url, 1024, complete = false).bytes.size)
            }
        }
    }

    @Test fun byteRangeOffsetsCannotSilentlyReadTheWrongPartOfAFile() {
        withServer(ByteArray(1000)) { url ->
            BoundedHttpSession(1000).use {
                assertThrows(IOException::class.java) { it.fetch(url, 100, offset = 100, complete = false) }
            }
        }
    }

    @Test fun deadlineAndCancellationPreventOpeningAnotherRequest() {
        var time = 0L
        val session = BoundedHttpSession(10_000, nanoTime = { time })
        time = 10_000_000_000L
        assertThrows(SocketTimeoutException::class.java) { session.fetch("http://127.0.0.1:1", 100) }
        session.cancel()
        assertThrows(CancellationException::class.java) { session.fetch("http://127.0.0.1:1", 100) }
    }
}
