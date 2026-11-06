package com.smartx.rfid.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class XtrackLoginResult(
    val user: Map<String, Any?>? = null,
    val locations: List<Map<String, String>> = emptyList(),
    val error: String? = null
) {
    val isSuccess: Boolean get() = user != null && error == null
}

/** Cliente da API Xtrack. Recebe a URL base para as chamadas que serão adicionadas. */
class Xtrack(baseUrl: String) {

    /** URL base da API, sem barras finais. */
    val baseUrl: String = baseUrl.trim().trimEnd('/').also { url ->
        require(url.isNotBlank()) { "A URL base do Xtrack não pode ser vazia" }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "A URL base do Xtrack deve começar com http:// ou https://"
        }
    }

    /** Busca todas as locations e retorna cada uma com as chaves `id` e `name`. */
    suspend fun getLocations(): List<Map<String, String>> = post("GetLocation").map { location ->
        mapOf(
            "id" to (location["id"]?.toString() ?: ""),
            "name" to (location["name"]?.toString() ?: "")
        )
    }

    /** Busca devices; `uid` é retornado como `name` e o campo `name` como `description`. */
    suspend fun getDevices(): List<Map<String, String>> = post("GetDevice").map { device ->
        mapOf(
            "id" to (device["id"]?.toString() ?: ""),
            "name" to (device["uid"]?.toString() ?: ""),
            "description" to (device["name"]?.toString() ?: "")
        )
    }

    /** Busca todas as conditions e retorna cada uma com as chaves `id` e `name`. */
    suspend fun getConditions(): List<Map<String, String>> = post("GetCondition").map { condition ->
        mapOf(
            "id" to (condition["id"]?.toString() ?: ""),
            "name" to (condition["name"]?.toString() ?: "")
        )
    }

    /** Busca todas as roles e retorna cada uma com as chaves `id` e `name`. */
    suspend fun getRoles(): List<Map<String, String>> = post("GetRole").map { role ->
        mapOf(
            "id" to (role["id"]?.toString() ?: ""),
            "name" to (role["name"]?.toString() ?: "")
        )
    }

    /** Busca objetos, opcionalmente filtrando pelos EPCs informados. */
    suspend fun getObjects(epcs: List<String>? = null): List<Map<String, Any?>> = post(
        command = "GetObject",
        objectEpcs = epcs
    )

    /** Busca usuários, opcionalmente filtrando pelo login. As chaves da resposta são minúsculas. */
    suspend fun getUsers(login: String? = null): List<Map<String, Any?>> = post(
        command = "GetUser",
        userLogin = login
    )

    /** Registra um evento no Xtrack. Erros HTTP ou de rede são lançados como exceção. */
    suspend fun insertLog(provider: String, source: String, message: String, loglevel: String) {
        val data = JSONObject()
            .put("provider", provider)
            .put("source", source)
            .put("message", message)
            .put("loglevel", loglevel)
        executePost(
            JSONObject()
                .put("command", "InsertLog")
                .put("terminal", "ERP")
                .put("data", data)
        )
    }

    /** Altera a condição dos objetos identificados por IDCODE e retorna a resposta (ex.: status). */
    suspend fun moveCondition(idCodes: List<String>, condition: String): Map<String, Any?> {
        val objects = JSONArray().apply {
            idCodes.forEach { idCode ->
                put(JSONObject()
                    .put("object", idCode)
                    .put("condition", condition))
            }
        }
        val responseBody = executePost(
            JSONObject()
                .put("command", "MoveCondition")
                .put("terminal", "SAPext")
                .put("data", objects)
        )
        val response = normalizeJson(JSONObject(responseBody)) as? Map<*, *>
            ?: throw IOException("Resposta inválida do Xtrack: objeto JSON esperado")
        return response.entries.associate { (key, value) ->
            (key as? String ?: throw IOException("Resposta inválida do Xtrack: chave de resposta inválida")) to value
        }
    }

    /** Move os objetos identificados por IDCODE para a location informada. */
    suspend fun moveLocation(idCodes: List<String>, location: String): Map<String, Any?> {
        val objects = JSONArray().apply {
            idCodes.forEach { idCode ->
                put(JSONObject()
                    .put("object", idCode)
                    .put("location", location))
            }
        }
        val responseBody = executePost(
            JSONObject()
                .put("command", "MoveLocation")
                .put("terminal", "SAPext")
                .put("data", objects)
        )
        val response = normalizeJson(JSONObject(responseBody)) as? Map<*, *>
            ?: throw IOException("Resposta inválida do Xtrack: objeto JSON esperado")
        return response.entries.associate { (key, value) ->
            (key as? String ?: throw IOException("Resposta inválida do Xtrack: chave de resposta inválida")) to value
        }
    }

    /** Autentica o usuário, adiciona `role_name` e retorna também as locations disponíveis. */
    suspend fun login(username: String, password: String): XtrackLoginResult {
        val normalizedUsername = username.trim().uppercase(Locale.ROOT)
        val normalizedPassword = password.trim().uppercase(Locale.ROOT)
        if (normalizedUsername.isEmpty() || normalizedPassword.isEmpty()) {
            return XtrackLoginResult(error = "Usuário e senha são obrigatórios")
        }

        return try {
            val expectedPasswordHash = md5(normalizedPassword)
            val user = getUsers(normalizedUsername).firstOrNull {
                it["login"]?.toString()?.trim()?.uppercase(Locale.ROOT) == normalizedUsername &&
                    it["password"]?.toString()?.uppercase(Locale.ROOT) == expectedPasswordHash
            } ?: return XtrackLoginResult(error = "Usuário ou senha inválidos")

            if (user["active"]?.toString() == "0") {
                return XtrackLoginResult(error = "Usuário inativo")
            }

            val roleId = user["role"]?.toString()
            val roleName = getRoles().firstOrNull { it["id"] == roleId }?.get("name")
            val enrichedUser = user
                .filterKeys { it != "password" }
                .toMutableMap()
                .apply { put("role_name", roleName) }

            XtrackLoginResult(
                user = enrichedUser,
                locations = getLocations()
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            XtrackLoginResult(error = error.message ?: "Falha ao autenticar no Xtrack")
        }
    }

    /** Executa um POST Xtrack e retorna os registros de `data` com as chaves normalizadas. */
    private suspend fun post(
        command: String,
        userLogin: String? = null,
        objectEpcs: List<String>? = null
    ): List<Map<String, Any?>> {
        val request = JSONObject()
            .put("command", command)
            .put("terminal", "ERP")
        if (command == "GetUser" && userLogin != null) {
            request.put("data", JSONObject().put("user", JSONObject().put("LOGIN", userLogin)))
        }
        if (command == "GetObject" && !objectEpcs.isNullOrEmpty()) {
            val objectFilters = JSONArray().apply {
                objectEpcs.forEach { epc ->
                    put(JSONObject().put("EPC", epc))
                }
            }
            request.put("data", JSONObject().put("object", objectFilters))
        }

        val responseBody = executePost(request)
        val response = normalizeJson(JSONObject(responseBody)) as? Map<*, *>
            ?: throw IOException("Resposta inválida do Xtrack: objeto JSON esperado")
        val records = response["data"] as? List<*>
            ?: throw IOException("Resposta inválida do Xtrack: campo 'data' ausente ou inválido")

        return records.mapIndexed { index, item ->
            val record = item as? Map<*, *>
                ?: throw IOException("Resposta inválida do Xtrack: registro na posição $index inválido")
            record.entries.associate { (key, value) ->
                (key as? String ?: throw IOException("Resposta inválida do Xtrack: chave de registro inválida")) to value
            }
        }
    }

    /** Envia o JSON ao endpoint e retorna o corpo da resposta em sucesso HTTP. */
    private suspend fun executePost(request: JSONObject): String = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = DEFAULT_TIMEOUT_MS
            readTimeout = DEFAULT_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val payload = request.toString()
            connection.outputStream.use { output ->
                output.write(payload.toByteArray(StandardCharsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = responseStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw IOException("Xtrack retornou HTTP $statusCode: $responseBody")
            }
            responseBody
        } finally {
            connection.disconnect()
        }
    }

    /** Converte objetos/arrays JSON para estruturas Kotlin e normaliza as chaves para minúsculas. */
    private fun normalizeJson(value: Any?): Any? = when (value) {
        is JSONObject -> buildMap {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key.lowercase(), normalizeJson(value.get(key)))
            }
        }
        is JSONArray -> (0 until value.length()).map { normalizeJson(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02X".format(byte) }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
