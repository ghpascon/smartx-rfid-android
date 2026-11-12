package com.smartx.rfid.api

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

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
    fun `catalog methods preserve additional API fields and device aliases`() = withResponse(
        """{"data":[{"ID":"7","UID":"RFID-7","NAME":"Leitor","ACTIVE":"1","EXTRA":"kept"}]}"""
    ) { client ->
        val device = runBlocking { client.getDevices() }.single()

        assertEquals("7", device["id"])
        assertEquals("RFID-7", device["name"])
        assertEquals("Leitor", device["description"])
        assertEquals("1", device["active"])
        assertEquals("kept", device["extra"])
    }

    @Test
    fun `GetDisposition requests default pagination and maps records`() {
        val requestBody = AtomicReference<String>()
        withResponse(
            """{"data":[{"ID":"3","NAME":"ROUPA"}]}""",
            requestObserver = requestBody::set
        ) { client ->
            val dispositions = runBlocking { client.getDispositions() }

            assertEquals(listOf(mapOf("id" to "3", "name" to "ROUPA")), dispositions)
        }

        val data = JSONObject(requestBody.get()).getJSONObject("data")
        assertEquals(0, data.getInt("offset"))
        assertEquals(1000, data.getInt("limit"))
    }

    @Test
    fun `GetDisposition filters by exact name`() {
        val requestBody = AtomicReference<String>()
        withResponse(
            """{"data":{"ID":"3","NAME":"ROUPA"}}""",
            requestObserver = requestBody::set
        ) { client ->
            val dispositions = runBlocking { client.getDispositions(name = "ROUPA") }

            assertEquals(listOf(mapOf("id" to "3", "name" to "ROUPA")), dispositions)
        }

        val request = JSONObject(requestBody.get())
        val data = request.getJSONObject("data")
        assertEquals("ROUPA", data.getJSONObject("disposition").getString("NAME"))
        assertFalse(data.has("offset"))
        assertFalse(data.has("limit"))
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

    @Test
    fun `insertObjects sends image Base64 as IMAGEDATA`() {
        val requestBody = AtomicReference<String>()
        withResponse(
            """{"STATUS":"OK"}""",
            requestObserver = requestBody::set
        ) { client ->
            runBlocking {
                client.insertObjects(
                    listOf(
                        mapOf(
                            "IDCODE" to "ASSET-IMAGE-1",
                            "HOMELOCATION_NAME" to "TEST",
                            "IMAGEDATA" to "aGVsbG8="
                        )
                    )
                )
            }
        }

        val request = JSONObject(requestBody.get())
        val objectData = request.getJSONObject("data").getJSONObject("object")
        assertEquals("aGVsbG8=", objectData.getString("IMAGEDATA"))
        assertEquals("1", objectData.getString("QUANTITY"))
    }

    @Test
    fun `insertObjects keeps array payload when importing multiple objects`() {
        val requestBody = AtomicReference<String>()
        withResponse(
            """{"STATUS":"OK"}""",
            requestObserver = requestBody::set
        ) { client ->
            runBlocking {
                client.insertObjects(
                    listOf(
                        mapOf("IDCODE" to "ASSET-1", "HOMELOCATION_NAME" to "TEST"),
                        mapOf("IDCODE" to "ASSET-2", "HOMELOCATION_NAME" to "TEST")
                    )
                )
            }
        }

        val data = JSONObject(requestBody.get()).getJSONArray("data")
        assertEquals(2, data.length())
        assertEquals("ASSET-1", data.getJSONObject(0).getJSONObject("object").getString("IDCODE"))
        assertEquals("ASSET-2", data.getJSONObject(1).getJSONObject("object").getString("IDCODE"))
    }

    private fun withResponse(
        body: String,
        requestCount: Int = 1,
        requestObserver: ((String) -> Unit)? = null,
        test: (Xtrack) -> Unit
    ) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            repeat(requestCount) {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val requestHeaders = ByteArrayOutputStream()
                    var matchedHeaderTerminator = 0
                    while (matchedHeaderTerminator < 4) {
                        val byte = input.read()
                        if (byte == -1) break
                        requestHeaders.write(byte)
                        matchedHeaderTerminator = when {
                            matchedHeaderTerminator == 0 && byte == '\r'.code -> 1
                            matchedHeaderTerminator == 1 && byte == '\n'.code -> 2
                            matchedHeaderTerminator == 2 && byte == '\r'.code -> 3
                            matchedHeaderTerminator == 3 && byte == '\n'.code -> 4
                            byte == '\r'.code -> 1
                            else -> 0
                        }
                    }
                    val headerText = requestHeaders.toString(StandardCharsets.UTF_8.name())
                    val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)")
                        .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val requestBody = input.readNBytes(contentLength)
                    requestObserver?.invoke(String(requestBody, StandardCharsets.UTF_8))
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
