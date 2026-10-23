package com.yfantasyxx.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端（MKCOL / PUT / GET / PROPFIND / DELETE），基于 OkHttp。
 * 兼容坚果云、Nextcloud、InfiniCLOUD、自建 WebDAV 等服务。
 */
class WebDavClient(
    baseUrl: String,
    username: String,
    password: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Authorization", Credentials.basic(username, password))
                .build()
            chain.proceed(request)
        }
        .build()

    /** 规范化基础 URL（确保以 / 结尾） */
    val baseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    /** 测试连接：对目录做 Depth 0 PROPFIND */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl)
                .method("PROPFIND", PROPFIND_DEPTH0_BODY)
                .header("Depth", "0")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw IOException("认证失败：请检查用户名和密码")
                if (response.code !in 200..299) {
                    throw IOException("服务器返回 ${response.code}：${response.message}")
                }
            }
        }
    }

    /** 确保目录存在（逐级 MKCOL，已存在则忽略） */
    suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val segments = path.trim('/').split('/')
            var current = baseUrl
            for (segment in segments) {
                if (segment.isBlank()) continue
                current = "$current$segment/"
                val request = Request.Builder()
                    .url(current)
                    .method("MKCOL", EMPTY_BODY)
                    .build()
                client.newCall(request).execute().use { response ->
                    // 405 = 已存在，正常
                    if (response.code != 201 && response.code != 405) {
                        throw IOException("创建目录失败(${response.code})：${response.message}")
                    }
                }
            }
        }
    }

    /** 上传文件 */
    suspend fun upload(path: String, bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = bytes.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl$path")
                .put(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    throw IOException("上传失败(${response.code})：${response.message}")
                }
            }
        }
    }

    /** 下载文件 */
    suspend fun download(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) throw IOException("文件不存在：$path")
                if (response.code !in 200..299) {
                    throw IOException("下载失败(${response.code})：${response.message}")
                }
                response.body?.bytes() ?: throw IOException("响应为空")
            }
        }
    }

    /** 列出目录中的备份文件（按名称） */
    suspend fun listBackups(directory: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (directory.isEmpty()) baseUrl else baseUrl + directory.trim('/') + "/"
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_DEPTH1_BODY)
                .header("Depth", "1")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    throw IOException("列出文件失败(${response.code})：${response.message}")
                }
                val xml = response.body?.string().orEmpty()
                parseFileNames(xml)
            }
        }
    }

    /** 删除远端文件 */
    suspend fun delete(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    throw IOException("删除失败(${response.code})：${response.message}")
                }
            }
        }
    }

    private fun parseFileNames(xml: String): List<String> =
        Regex("<d:href>(.*?)</d:href>", RegexOption.IGNORE_CASE)
            .findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.contains(BACKUP_PREFIX) && it.endsWith(".db") }
            .map { it.substringAfterLast('/') }
            .distinct()
            .sorted()
            .toList()

    companion object {
        const val BACKUP_PREFIX = "yfantasyxx-backup-"
        const val BACKUP_DIRECTORY = "YFantasyXX"

        private val EMPTY_BODY: RequestBody = object : RequestBody() {
            override fun contentType() = null
            override fun writeTo(sink: BufferedSink) = Unit
        }

        private val PROPFIND_DEPTH0_BODY: RequestBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop><d:displayname/></d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml".toMediaType())

        private val PROPFIND_DEPTH1_BODY: RequestBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/></d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml".toMediaType())
    }
}
