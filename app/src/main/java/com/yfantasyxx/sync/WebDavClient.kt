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
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 远端备份文件信息。
 *
 * @param name 文件名，如 yfantasyxx-backup-20261025-140322.db
 * @param size 字节数；-1 表示服务器没返回内容长度
 * @param lastModified 服务器上的最后修改时间（毫秒）；null 表示没有
 */
data class RemoteBackupFile(
    val name: String,
    val size: Long = -1L,
    val lastModified: Long? = null,
)

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

    /** 列出目录中的备份文件（只取名字） */
    suspend fun listBackups(directory: String): Result<List<String>> =
        listBackupFiles(directory).map { list -> list.map { it.name } }

    /** 列出目录中的备份文件（含大小与修改时间） */
    suspend fun listBackupFiles(directory: String): Result<List<RemoteBackupFile>> =
        withContext(Dispatchers.IO) {
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
                    parseBackupFiles(xml)
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

    /**
     * 解析 PROPFIND 的 multistatus 响应，取出备份文件及其大小、修改时间。
     *
     * 各家服务器的命名空间前缀不统一（d: / D: / 无前缀），
     * 所以标签都写成「前缀可选」的正则。
     */
    private fun parseBackupFiles(xml: String): List<RemoteBackupFile> {
        val out = mutableListOf<RemoteBackupFile>()
        for (block in RESPONSE_BLOCK.findAll(xml).map { it.groupValues[1] }) {
            val href = tagValue(block, "href") ?: continue
            val name = href.substringAfterLast('/')
            if (!name.startsWith(BACKUP_PREFIX) || !name.endsWith(".db")) continue
            val size = tagValue(block, "getcontentlength")
                ?.trim()
                ?.toLongOrNull()
                ?: -1L
            val modified = tagValue(block, "getlastmodified")
                ?.trim()
                ?.let { parseHttpDate(it) }
            out += RemoteBackupFile(name, size, modified)
        }
        return out.distinctBy { it.name }.sortedBy { it.name }
    }

    /** 取某个标签的文本内容，取不到返回 null */
    private fun tagValue(block: String, tag: String): String? {
        val re = Regex(
            "<(?:[A-Za-z0-9_.-]+:)?$tag\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?$tag>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return re.find(block)?.groupValues?.get(1)
    }

    /** 解析 HTTP 日期（RFC 1123 / ISO 8601） */
    private fun parseHttpDate(raw: String): Long? {
        val text = raw.trim()
        for (pattern in HTTP_DATE_PATTERNS) {
            val fmt = SimpleDateFormat(pattern, Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            // 宽松模式：某些服务器返回的星期几和日期对不上，strict 会直接解析失败；
            // 靠「必须整串匹配完」来防止把垃圾串当成日期
            fmt.isLenient = true
            val pos = ParsePosition(0)
            val parsed = fmt.parse(text, pos)
            if (parsed != null && pos.index == text.length) return parsed.time
        }
        return null
    }

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

        /** 单个 <response> 块（命名空间前缀可选） */
        private val RESPONSE_BLOCK = Regex(
            "<(?:[A-Za-z0-9_.-]+:)?response\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?response>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        private val HTTP_DATE_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
    }
}
