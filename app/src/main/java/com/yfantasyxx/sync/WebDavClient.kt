package com.yfantasyxx.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * 上传回执。
 *
 * @param bytes 实际写出的字节数
 * @param verified 是否已在服务器上回查到这份文件；false 表示服务器没给出可校验的信息
 */
data class UploadReceipt(
    val bytes: Long,
    val verified: Boolean,
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

    /**
     * 上传文件。
     *
     * **写完必须能查回来**：PUT 返回 2xx 不代表文件真的落盘了（目录只读、
     * 网关吞请求、空间超限后返回畸形的 200 都可能）。所以这里补一次
     * Depth 0 的 PROPFIND，逐项核对：
     * - 服务器上找不到这份文件 → 直接算失败；
     * - 服务器报的大小和本地不一致 → 直接算失败；
     * - 服务器不支持查大小 → 成功但不标记 verified。
     */
    suspend fun upload(path: String, bytes: ByteArray): Result<UploadReceipt> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (bytes.isEmpty()) throw IOException("内容为空，已取消上传")
                val body = bytes.toRequestBody("application/octet-stream".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl$path")
                    .put(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        throw IOException(
                            "上传失败(${response.code})：${response.message}${bodySnippet(response)}"
                        )
                    }
                }
                verifyUploaded(path, bytes.size.toLong())
            }
        }

    /** 查询单个文件的信息；返回 success(null) 表示服务器明确说「不存在」 */
    suspend fun stat(path: String): Result<RemoteBackupFile?> =
        withContext(Dispatchers.IO) { statNow(path) }

    private fun statNow(path: String): Result<RemoteBackupFile?> = runCatching {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .method("PROPFIND", PROPFIND_DEPTH1_BODY)
            .header("Depth", "0")
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 || response.code == 410 -> null
                response.code !in 200..299 ->
                    throw IOException("查询文件失败(${response.code})：${response.message}")

                else -> parseSingleFile(response.body?.string().orEmpty())
            }
        }
    }

    /** 上传后回查；查不到就抛异常，绝不假装成功 */
    private suspend fun verifyUploaded(path: String, expected: Long): UploadReceipt {
        var found: RemoteBackupFile? = null
        for (attempt in 0..1) {
            val result = statNow(path)
            // 查询本身失败（服务器不支持 PROPFIND 之类）：不推翻已经 2xx 的上传
            if (result.isFailure) return UploadReceipt(expected, verified = false)
            found = result.getOrNull()
            if (found != null) break
            // 有些服务写完要过一下才能查到，等一下再试一次
            if (attempt == 0) delay(250)
        }
        val file = found
            ?: throw IOException("上传返回成功，但服务器上找不到这个文件（目录可能不可写）")
        // 有些服务对单文件 PROPFIND 不返回长度，报 0 或 -1；只有拿到明确的正数才比对
        if (file.size > 0 && file.size != expected) {
            throw IOException("上传后大小不符：服务器 ${file.size} 字节，本地 $expected 字节")
        }
        return UploadReceipt(expected, verified = file.size == expected)
    }

    /**
     * 试写测试：建目录 → 传一个几字节的临时文件 → 回查 → 删掉。
     * 用来区分「能连上」和「能写进去」——只连上不代表备份能成。
     */
    suspend fun writeTest(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureDirectory(BACKUP_DIRECTORY).getOrThrow()
            val testPath = "$BACKUP_DIRECTORY/$WRITE_TEST_FILE"
            upload(testPath, "yfantasyxx-write-test".toByteArray()).getOrThrow()
            // 删不掉不影响结论，忽略
            delete(testPath).getOrNull()
            Unit
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

    /** 解析 Depth 0 的单个文件响应 */
    private fun parseSingleFile(xml: String): RemoteBackupFile? {
        val block = RESPONSE_BLOCK.find(xml)?.groupValues?.get(1) ?: return null
        val href = tagValue(block, "href") ?: return null
        val name = href.substringAfterLast('/')
        if (name.isEmpty()) return null
        val size = tagValue(block, "getcontentlength")?.trim()?.toLongOrNull() ?: -1L
        val modified = tagValue(block, "getlastmodified")?.trim()?.let { parseHttpDate(it) }
        return RemoteBackupFile(name, size, modified)
    }

    /** 失败时带一点响应正文，方便看出是权限、配额还是路径问题 */
    private fun bodySnippet(response: okhttp3.Response, limit: Int = 120): String {
        val text = runCatching { response.body?.string() }.getOrNull()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return ""
        return "（" + text.replace(WHITESPACE, " ").take(limit) + "）"
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

        /** 试写测试用的临时文件名 */
        private const val WRITE_TEST_FILE = ".write-test"

        private val WHITESPACE = Regex("\\s+")

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
