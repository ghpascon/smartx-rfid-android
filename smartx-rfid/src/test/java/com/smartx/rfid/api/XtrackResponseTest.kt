package com.smartx.rfid.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

class XtrackResponseTest {

    @Test
    fun `filtered GetUser accepts data object`() = withResponse(
        """{"data":{"ID":"1","ACTIVE":"1","LOGIN":"ADMIN","ROLE":"1"}}"""
    ) { client ->
        val users = runBlocking { client.getUsers("ADMIN") }

        assertEquals(1, users.size)
        assertEquals("ADMIN", users.single()["login"])
        assertEquals("1", users.single()["role"])
    }

    @Test
    fun `unfiltered GetUser accepts data list`() = withResponse(
        """{"data":[{"LOGIN":"ADMIN"},{"LOGIN":"FELIPE"}]}"""
    ) { client ->
        val users = runBlocking { client.getUsers() }

        assertEquals(listOf("ADMIN", "FELIPE"), users.map { it["login"] })
    }

    @Test
    fun `GetObject accepts a single object in data`() = withResponse(
        """{"data":{"ID":"42","IDCODE":"ASSET-42"}}"""
    ) { client ->
        val objects = runBlocking { client.getObjects(idCodes = listOf("ASSET-42")) }

        assertEquals(1, objects.size)
        assertEquals("ASSET-42", objects.single()["idcode"])
    }

    @Test
    fun `filtered GetObject treats string response as no matches`() = withResponse(
        "\"No objects found\""
    ) { client ->
        val objects = runBlocking { client.getObjects(idCodes = listOf("UNKNOWN-ASSET")) }

        assertEquals(emptyList<Map<String, Any?>>(), objects)
    }

    @Test
    fun `string response to filtered GetUser means no matching user`() = withResponse(
        "\"User not found\""
    ) { client ->
        val users = runBlocking { client.getUsers("UNKNOWN") }

        assertEquals(emptyList<Map<String, Any?>>(), users)
    }

    @Test
    fun `login reports invalid credentials for string response`() = withResponse(
        "\"User not found\""
    ) { client ->
        val result = runBlocking { client.login("UNKNOWN", "wrong-password") }

        assertFalse(result.isSuccess)
        assertEquals("Usuário ou senha inválidos", result.error)
    }

    @Test
    fun `response parser rejects trailing content`() = withResponse(
        """{"data":[]} trailing"""
    ) { client ->
        val error = runCatching { runBlocking { client.getUsers() } }.exceptionOrNull()

        assertEquals(true, error is IOException)
        assertEquals(
            "Resposta inválida do Xtrack: conteúdo extra após o JSON",
            error?.message
        )
    }

    @Test
    fun `write and move methods parse object responses`() = withResponse(
        """{"STATUS":"OK"}""",
        requestCount = 4
    ) { client ->
        runBlocking {
            client.insertLog("test", "unit-test", "message", "INFO")
            assertEquals(
                "OK",
                client.insertObjects(listOf(mapOf("IDCODE" to "ASSET-1", "HOMELOCATION_NAME" to "TEST")))["status"]
            )
            assertEquals("OK", client.moveCondition(listOf("ASSET-1"), "GOOD")["status"])
            assertEquals("OK", client.moveLocation(listOf("ASSET-1"), "TEST")["status"])
        }
    }

    private fun withResponse(body: String, requestCount: Int = 1, test: (Xtrack) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            repeat(requestCount) {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray(StandardCharsets.UTF_8))
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        serverThread.start()
        try {
            test(Xtrack("http://127.0.0.1:${server.localPort}/req"))
        } finally {
            server.close()
            serverThread.join(2_000)
        }
    }
}
