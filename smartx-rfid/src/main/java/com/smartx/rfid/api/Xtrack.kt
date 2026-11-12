package com.smartx.rfid.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
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
            "name" to (location["name"]?.toString() ?: ""),
            "department" to (location["department"]?.toString() ?: ""),
            "usr1" to (location["usr1"]?.toString() ?: "")
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

    /** Busca todas as categorias e retorna cada uma com as chaves `id` e `name`. */
    suspend fun getCategories(): List<Map<String, String>> = post("GetCategory").map { category ->
        mapOf(
            "id" to (category["id"]?.toString() ?: ""),
            "name" to (category["name"]?.toString() ?: "")
        )
    }

    /** Busca todos os departamentos e retorna cada um com as chaves `id` e `name`. */
    suspend fun getDepartments(): List<Map<String, String>> = post("GetDepartment").map { department ->
        mapOf(
            "id" to (department["id"]?.toString() ?: ""),
            "name" to (department["name"]?.toString() ?: "")
        )
    }

    /** Busca todos os grupos e retorna cada um com as chaves `id` e `name`. */
    suspend fun getGroups(): List<Map<String, String>> = post("GetGroup").map { group ->
        mapOf(
            "id" to (group["id"]?.toString() ?: ""),
            "name" to (group["name"]?.toString() ?: "")
        )
    }

    /** Busca todos os custodians e retorna cada um com as chaves `id` e `name`. */
    suspend fun getCustodians(): List<Map<String, String>> = post("GetCustodian").map { custodian ->
        mapOf(
            "id" to (custodian["id"]?.toString() ?: ""),
            "name" to (custodian["name"]?.toString() ?: "")
        )
    }

    /** Busca todos os centros de custo e retorna cada um com as chaves `id` e `name`. */
    suspend fun getCostCenters(): List<Map<String, String>> = post("GetCostCenter").map { costCenter ->
        mapOf(
            "id" to (costCenter["id"]?.toString() ?: ""),
            "name" to (costCenter["name"]?.toString() ?: "")
        )
    }

    /** Busca modelos de item e retorna também os campos de categoria disponíveis na resposta. */
    suspend fun getItemModels(): List<Map<String, String>> = post("GetItemModel").map { itemModel ->
        buildMap {
            itemModel.forEach { (key, value) ->
                put(key.lowercase(Locale.ROOT), value?.toString() ?: "")
            }
            put("id", itemModel["id"]?.toString() ?: "")
            put("name", itemModel["idcode"]?.toString() ?: "")
            put("description", itemModel["description"]?.toString() ?: "")
            put(
                "category_id",
                itemModel["category_id"]?.toString()
                    ?: itemModel["categoryid"]?.toString()
                    ?: itemModel["category"]?.toString()
                    ?: ""
            )
            put(
                "category_name",
                itemModel["category_name"]?.toString()
                    ?: itemModel["categoryname"]?.toString()
                    ?: ""
            )
            put("usr1", itemModel["usr1"]?.toString() ?: "")
        }
    }

    /** Busca todas as roles e retorna cada uma com as chaves `id` e `name`. */
    suspend fun getRoles(): List<Map<String, String>> = post("GetRole").map { role ->
        mapOf(
            "id" to (role["id"]?.toString() ?: ""),
            "name" to (role["name"]?.toString() ?: "")
        )
    }

    /** Busca disposições paginadas ou filtra por nome; retorna `id` e `name`. */
    suspend fun getDispositions(
        name: String? = null,
        offset: Int = 0,
        limit: Int = 1000
    ): List<Map<String, String>> {
        require(offset >= 0) { "offset não pode ser negativo" }
        require(limit > 0) { "limit deve ser maior que zero" }
        require(name == null || name.isNotBlank()) { "name não pode ser vazio" }

        return post(
            command = "GetDisposition",
            dispositionName = name?.trim(),
            offset = offset,
            limit = limit
        ).map { disposition ->
            mapOf(
                "id" to (disposition["id"]?.toString() ?: ""),
                "name" to (disposition["name"]?.toString() ?: "")
            )
        }
    }

    /** Busca objetos, opcionalmente filtrando pelos EPCs ou IDCODEs informados. */
    suspend fun getObjects(
        epcs: List<String>? = null,
        idCodes: List<String>? = null
    ): List<Map<String, Any?>> {
        require(epcs == null || idCodes == null) { "Informe EPCs ou IDCODEs, não ambos" }
        return post(
            command = "GetObject",
            objectEpcs = epcs,
            objectIdCodes = idCodes
        )
    }

    /** Busca usuários; resposta filtrada como objeto vira lista unitária. Chaves em minúsculas. */
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

    /** Insere vários objetos. `IDCODE` e `HOMELOCATION_NAME` são obrigatórios. */
    suspend fun insertObjects(objects: List<Map<String, *>>): Map<String, Any?> {
        val fields = listOf(
            "ACTIVE", "IDCODE", "DESCRIPTION", "SERIALNUMBER", "QUANTITY", "ITEMMODEL_IDCODE",
            "DEPARTMENT_NAME", "CONDITION_NAME", "DISPOSITION_NAME", "LOCATION_NAME",
            "HOMELOCATION_NAME", "GROUP_NAME", "CUSTODIAN_NAME", "DISPOSAL_NAME",
            "COSTCENTER_NAME", "CONTAINER_IDCODE", "CATEGORY_NAME", "LATITUDE", "LONGITUDE",
            "USRDATA1", "USRDATA2", "USRDATA3", "USRDATA4", "USRDATA5", "USRDATA6",
            "USRDATA7", "USRDATA8", "USRDATA9", "IDETYPE1", "IDECODE1", "IDETYPE2",
            "IDECODE2", "IDETYPE3", "IDECODE3", "IDETYPE4", "IDECODE4", "IMAGEFILE", "IMAGEDATA",
            "LAST_SEEN", "CREATE_DATE", "DUE_DATE"
        )
        val data = JSONArray()
        objects.forEachIndexed { index, input ->
            val values = input.entries.associate { (key, value) -> key.uppercase(Locale.ROOT) to value }
            val idCode = values["IDCODE"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("IDCODE é obrigatório no objeto na posição $index")
            val homeLocation = values["HOMELOCATION_NAME"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("HOMELOCATION_NAME é obrigatório no objeto na posição $index")
            val location = values["LOCATION_NAME"]?.toString()?.takeIf { it.isNotBlank() } ?: homeLocation

            val objectData = JSONObject()
            fields.forEach { field ->
                val value = when (field) {
                    "ACTIVE" -> values[field]?.toString() ?: "1"
                    "IDCODE" -> idCode
                    "HOMELOCATION_NAME" -> homeLocation
                    "LOCATION_NAME" -> location
                    "IDETYPE1" -> "BARCODE"
                    "IDETYPE2" -> "RFID"
                    else -> values[field]?.toString() ?: ""
                }
                objectData.put(field, value)
            }
            data.put(JSONObject().put("object", objectData))
        }

        val responseBody = executePost(
            JSONObject()
                .put("command", "ImportObject")
                .put("terminal", "ERP")
                .put("data", data)
        )
        return parseResponseObject(responseBody)
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
        return parseResponseObject(responseBody)
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
        return parseResponseObject(responseBody)
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

    /** Executa um POST Xtrack e normaliza `data` de todos os endpoints Get para uma lista. */
    private suspend fun post(
        command: String,
        userLogin: String? = null,
        objectEpcs: List<String>? = null,
        objectIdCodes: List<String>? = null,
        dispositionName: String? = null,
        offset: Int = 0,
        limit: Int = 1000
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
        if (command == "GetObject" && !objectIdCodes.isNullOrEmpty()) {
            val objectFilters = JSONArray().apply {
                objectIdCodes.forEach { idCode ->
                    put(JSONObject().put("object", JSONObject().put("IDCODE", idCode)))
                }
            }
            request.put("data", objectFilters)
        }
        if (command == "GetDisposition") {
            val data = JSONObject()
            if (dispositionName != null) {
                data.put("disposition", JSONObject().put("NAME", dispositionName))
            } else {
                data.put("offset", offset)
                    .put("limit", limit)
            }
            request.put("data", data)
        }

        val responseBody = executePost(request)
        val response = parseResponse(responseBody)
        val noMatchStringResponse = command == "GetUser" || command == "GetObject"
        if (noMatchStringResponse && (response == null || response is String)) return emptyList()
        val responseObject = response as? Map<*, *>
            ?: throw IOException("Resposta inválida do Xtrack: objeto JSON esperado")
        val data = responseObject["data"]
        val records = when (data) {
            is List<*> -> data
            // Endpoints Get podem retornar um objeto quando a busca resulta em um registro.
            is Map<*, *> -> listOf(data)
            // GetUser/GetObject podem sinalizar ausência de resultados com string ou sem data.
            null, is String -> if (noMatchStringResponse) emptyList() else {
                throw IOException("Resposta inválida do Xtrack: campo 'data' ausente ou inválido")
            }
            else -> throw IOException("Resposta inválida do Xtrack: campo 'data' ausente ou inválido")
        }

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

    /** Faz parse de qualquer valor JSON, inclusive respostas primitivas como strings. */
    private fun parseResponse(responseBody: String): Any? {
        if (responseBody.isBlank()) {
            throw IOException("Resposta inválida do Xtrack: corpo JSON vazio")
        }
        return try {
            val tokener = JSONTokener(responseBody)
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') {
                throw IOException("Resposta inválida do Xtrack: conteúdo extra após o JSON")
            }
            normalizeJson(value)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Resposta inválida do Xtrack: JSON malformado", error)
        }
    }

    /** Faz parse de uma resposta que deve ser um objeto JSON. */
    private fun parseResponseObject(responseBody: String): Map<String, Any?> {
        val response = parseResponse(responseBody) as? Map<*, *>
            ?: throw IOException("Resposta inválida do Xtrack: objeto JSON esperado")
        return response.entries.associate { (key, value) ->
            (key as? String ?: throw IOException("Resposta inválida do Xtrack: chave de resposta inválida")) to value
        }
    }

    /** Converte objetos/arrays JSON para estruturas Kotlin e normaliza as chaves para minúsculas. */
    private fun normalizeJson(value: Any?): Any? = when (value) {
        is JSONObject -> buildMap {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key.lowercase(Locale.ROOT), normalizeJson(value.get(key)))
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
