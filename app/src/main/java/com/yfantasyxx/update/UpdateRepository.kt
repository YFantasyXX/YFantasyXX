package com.yfantasyxx.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import com.yfantasyxx.BuildConfig
import com.yfantasyxx.data.prefs.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** GitHub 上最新一个正式 Release 的信息 */
@Immutable
data class UpdateInfo(
    /** 从 tag 上去掉前缀 v，如 1.6 */
    val versionName: String,
    /** 发布说明里声明的 versionCode；老版本没写就是 null */
    val versionCode: Int?,
    val tagName: String,
    /** 已去掉 Markdown 标记的更新说明 */
    val notes: String,
    val sizeBytes: Long,
    /** github.com 的下载直链 */
    val downloadUrl: String,
    /** api.github.com 的附件地址，国内网络下往往比 github.com 更好连 */
    val apiAssetUrl: String?,
    /** Release 里附带的 sha256 摘要（GitHub 2024 起提供），没有就是 null */
    val digest: String?,
    val publishedAt: String?,
)

/** 软件内更新界面的全部状态 */
@Immutable
sealed interface UpdateState {
    /** 还没查过 */
    data object Idle : UpdateState

    /** 正在查（只有手动点进来才会显示） */
    data object Checking : UpdateState

    data class UpToDate(
        val checkedAt: Long,
        /** 这次没连上 GitHub，显示的是上次成功检查的结论 */
        val fromCache: Boolean = false,
    ) : UpdateState

    data class Available(
        val info: UpdateInfo,
        val checkedAt: Long,
        /** 这次没连上 GitHub，显示的是上次成功检查的结论 */
        val fromCache: Boolean = false,
    ) : UpdateState

    data class Downloading(
        val bytes: Long,
        val total: Long,
        val info: UpdateInfo,
    ) : UpdateState

    data class Ready(
        val file: File,
        val info: UpdateInfo,
        /** 这个包能不能直接覆盖安装（包名 / 签名 / versionCode 都对得上） */
        val installable: Boolean,
        /** 不能安装时的原因，或可安装时的补充说明 */
        val note: String?,
    ) : UpdateState

    data class Failed(
        val message: String,
        val checkedAt: Long = 0L,
        /** 被 GitHub 限流时：什么时候可以再试（毫秒时间戳）；0 表示随时可以重试 */
        val retryAfterAt: Long = 0L,
    ) : UpdateState
}

/** GitHub 匿名接口被限流。[resetAt] 是恢复时间（毫秒时间戳），0 表示服务端没给 */
private class RateLimitException(val resetAt: Long) : IOException("HTTP 403（接口限流）")

/** 点「立即安装」的结果 */
sealed interface InstallResult {
    data object Started : InstallResult

    /** 系统还没允许本应用安装 APK，已跳去授权页 */
    data object NeedPermission : InstallResult

    data class Failed(val message: String) : InstallResult
}

/**
 * 从 GitHub Release 检查并下载更新。
 *
 * 设计取舍：
 *
 * - **只打 GitHub API**。`github.com` 在国内经常连不上，而 `api.github.com`
 *   和它重定向到的 `objects.githubusercontent.com` 通常还能用，所以下载优先走
 *   `assets[].url`（`Accept: application/octet-stream`），失败再退回 `browser_download_url`。
 * - **版本以 versionCode 为准**。tag 是 `v1.6` 这种字符串，比不出 `1.10 > 1.9` 之外的
 *   情况都还好，但一旦某次只升 versionCode 不升 versionName 就会漏更新。所以发布说明里
 *   写一行 `versionCode: 125` 最可靠；没写才退回比 versionName。
 * - **装之前三重校验**：sha256（Release 自带）、包名、签名证书。GitHub 账号被盗时
 *   攻击者能发假 Release，但伪造不出我们的签名，这一步是最后一道门。
 * - 状态放在进程级单例里（[AppContainer] 持有），页面进进出出不会丢下载进度。
 */
class UpdateRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * 用户是否已经看过这一次的更新内容。
     *
     * 底栏红点靠它收掉：不处理的话，点进「我的」看一眼说明、选了「稍后」，
     * 红点还挂着，用户会以为还有别的东西没看。查到新版本时自动清零。
     */
    private val _acknowledged = MutableStateFlow(false)
    val acknowledged: StateFlow<Boolean> = _acknowledged.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        // 下载 4~5MB 的包，读超时给宽一点；不给 callTimeout，慢网络下也不会被整段掐死
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    // ────────────────────────── 落盘的小缓存 ──────────────────────────
    //
    // 为什么不全放内存：进程被杀之后（用户清后台、系统回收），下次冷启动又得重新
    // 打一次接口。github 未登录的 API 只有 60 次/小时，而且是按**出口 IP** 算的，
    // 公共 Wi-Fi / 运营商 NAT 下很容易被别人先用完，一旦用完所有请求都是 403。

    private val prefs = context.getSharedPreferences("update_cache", Context.MODE_PRIVATE)

    /**
     * 上一次成功（HTTP 200）拿到的原始响应体 + ETag。
     *
     * 1. 带 `If-None-Match` 再请求，内容没变时服务端回 304，只省流量；
     * 2. 真被限流 / 断网时，用这份给出「上次的结论 + 时间」，而不是一个红框 ——
     *    这才是关键：**匿名请求的 304 实测照样扣配额**（2026-11-05 实测：
     *    连续 3 次条件请求，`X-RateLimit-Remaining` 从 52 掉到 49），
     *    所以省配额只能靠这里的缓存 + 下面的退避，不能指望条件请求。
     */
    @Volatile
    private var cachedBody: String? = prefs.getString(KEY_BODY, null)

    @Volatile
    private var etag: String? = prefs.getString(KEY_ETAG, null)

    /** 上次成功检查的时间（毫秒），也用于「结果缓存于 X」的展示 */
    @Volatile
    private var lastSuccessAt: Long = prefs.getLong(KEY_SUCCESS_AT, 0L)

    /** 上次尝试的时间，不管成败。自动检查的节流按它算 */
    @Volatile
    private var lastAttemptAt: Long = prefs.getLong(KEY_ATTEMPT_AT, 0L)

    @Volatile
    private var lastAttemptOk: Boolean = prefs.getBoolean(KEY_ATTEMPT_OK, false)

    /** 被限流时的恢复时间点（毫秒）。到点之前一个请求都不发，省得越试越久 */
    @Volatile
    private var blockedUntil: Long = prefs.getLong(KEY_BLOCKED_UNTIL, 0L)

    init {
        // 限流按**出口 IP** 算：从 Wi-Fi 换到流量，闸门就没意义了，得放开。
        // 注册时会立刻回调一次「当前网络」，那不是用户换网，按时间戳跳过。
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching
            val registeredAt = System.currentTimeMillis()
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (System.currentTimeMillis() - registeredAt < 1_500L) return
                        clearBlock()
                    }
                },
            )
        }
    }

    private fun clearBlock() {
        if (blockedUntil == 0L) return
        blockedUntil = 0L
        prefs.edit().putLong(KEY_BLOCKED_UNTIL, 0L).apply()
        // 之前被限流、现在换了网络：立刻重查一次，用户不用干等倒计时。
        // 只在「上次就是被限流」时才走到这里，网络反复抖动不会变成连环请求。
        when (_state.value) {
            is UpdateState.Failed, is UpdateState.Idle, is UpdateState.UpToDate ->
                check(manual = true)
            else -> Unit
        }
    }

    /**
     * 启动时静默检查一次。
     *
     * 用户没开开关、或者 6 小时内已经查过、或者正在下载，都直接跳过。
     * 失败不抛给调用方：网络不通是常态，静默回 Idle 就行。
     */
    suspend fun autoCheck() {
        val s = settings.awaitSettings()
        if (!s.autoCheckUpdate) return
        if (_state.value !is UpdateState.Idle) return
        val now = System.currentTimeMillis()
        // 被限流的一律等闸门放开，这段时间请求发了也是 403
        if (blockedUntil > now) return
        val last = lastAttemptAt
        if (last != 0L) {
            // 成功过就 6 小时一次；一直失败（断网 / 被墙）也得隔一阵再试，
            // 否则每次冷启动都白等 3 秒再撞一次墙
            val interval = if (lastAttemptOk) CHECK_INTERVAL_MS else FAIL_RETRY_MS
            if (now - last < interval) return
        }
        // 先让首屏渲染出来，别和启动时的读库抢 IO
        delay(3_000)
        check(manual = false)
    }

    /**
     * 检查最新 Release。
     *
     * @param manual 用户主动点的。只有主动检查才把「检查中 / 失败」显示到界面上，
     *               自动检查全程静默，避免用户一开 App 就看到一个错误弹窗。
     */
    fun check(manual: Boolean) {
        if (checkJob?.isActive == true) return
        // 正在下载或已经下好了：不要用一次检查把这两条状态顶掉，否则「立即安装」的
        // 按钮会凭空消失，用户还得再下一次
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Ready -> return
            else -> Unit
        }

        checkJob = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            val result = runCatching { fetchLatest() }
            result.fold(
                onSuccess = { remote ->
                    markAttempt(ok = true)
                    if (isNewer(remote)) {
                        // 换了一个新版本，红点重新亮起来
                        _acknowledged.value = false
                        _state.value = UpdateState.Available(remote, lastSuccessAt)
                    } else {
                        _state.value = UpdateState.UpToDate(lastSuccessAt)
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    markAttempt(ok = false)
                    val cached = cachedInfo()
                    if (cached != null) {
                        // 手上有上次的结论就别报错。限流 / 断网时，「上次查到什么、
                        // 什么时候查的」比一个红色失败框有用得多，用户至少知道
                        // 自己是不是最新版。界面上会标明这是缓存结果。
                        _state.value = if (isNewer(cached)) {
                            UpdateState.Available(cached, lastSuccessAt, fromCache = true)
                        } else {
                            UpdateState.UpToDate(lastSuccessAt, fromCache = true)
                        }
                    } else {
                        _state.value = if (manual) {
                            UpdateState.Failed(
                                message = friendlyError(e),
                                retryAfterAt = (e as? RateLimitException)?.resetAt ?: 0L,
                            )
                        } else {
                            // 一次都没成功过、又没有缓存：静默，别打扰用户
                            UpdateState.Idle
                        }
                    }
                },
            )
        }
    }

    /** 下载当前发现的更新包 */
    fun download() {
        val available = _state.value as? UpdateState.Available ?: return
        if (downloadJob?.isActive == true) return

        downloadJob = scope.launch {
            val info = available.info
            _state.value = UpdateState.Downloading(0L, info.sizeBytes, info)
            // updateDir() 会清掉上一轮的残留，必须先建目录再取文件引用，
            // 否则 target 会被自己这一步删掉、紧接着又「删不掉」而报错
            val target = File(withContext(Dispatchers.IO) { updateDir() }, fileNameOf(info))
            try {
                withContext(Dispatchers.IO) {
                    // 上一轮可能留下同名半成品
                    if (target.exists() && !target.delete()) {
                        throw IOException("缓存目录里的旧安装包删不掉，稍后再试")
                    }
                    downloadTo(target, info)
                }
                val check = withContext(Dispatchers.IO) { verify(target, info) }
                _state.value = if (check.installable) {
                    UpdateState.Ready(target, info, installable = true, note = check.note)
                } else {
                    // 校验不过要删掉，别让用户点到一个装不上的文件
                    if (!check.keepFile) target.delete()
                    UpdateState.Ready(target, info, installable = false, note = check.note)
                }
            } catch (e: CancellationException) {
                target.delete()
                // 状态交给 cancelDownload() 复位。这里再写一次会和
                // 「取消后立刻重新下载」抢状态：新任务已经把 Downloading 设上了，
                // 旧任务收尾时又改回 Available，界面就卡在「发现新版本」不动
                throw e
            } catch (e: Exception) {
                target.delete()
                _state.value = UpdateState.Failed(
                    message = e.message?.takeIf { it.isNotBlank() } ?: "下载失败",
                    checkedAt = available.checkedAt,
                )
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        // 取消后回到「发现新版本」，让用户还能重新下
        val current = _state.value
        if (current is UpdateState.Downloading) {
            _state.value = UpdateState.Available(current.info, lastSuccessAt)
        }
    }

    /**
     * 调起系统安装器。
     *
     * Android 8 起需要「安装未知应用」权限，未授权时直接跳到本应用的授权页——
     * 让用户自己去设置里翻三层菜单基本等于劝退。
     */
    fun install(): InstallResult {
        val ready = _state.value as? UpdateState.Ready
            ?: return InstallResult.Failed("安装包还没准备好")
        if (!ready.file.exists()) {
            _state.value = UpdateState.Available(ready.info, lastSuccessAt)
            return InstallResult.Failed("安装包已被系统清理，请重新下载")
        }
        if (!ready.installable) {
            return InstallResult.Failed(ready.note ?: "这个安装包不能直接覆盖安装")
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            openInstallPermissionSettings()
            return InstallResult.NeedPermission
        }
        // 理论上 cacheDir/updates 已经在 file_paths.xml 里授权过；
        // 万一哪天有人改了路径，这里给一句人话而不是直接崩
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                ready.file,
            )
        }.getOrElse {
            return InstallResult.Failed("生成安装包链接失败：${it.message ?: "路径未授权"}")
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            InstallResult.Started
        }.getOrElse {
            InstallResult.Failed("打不开系统安装器：${it.message ?: "未知错误"}")
        }
    }

    /** 用户已经打开过更新详情，底栏红点可以收了 */
    fun markSeen() {
        _acknowledged.value = true
    }

    /** 把状态复位，让用户可以从头检查一次 */
    fun reset() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Idle
    }

    /**
     * 只给 lite 版测试工具用：把线上最新 Release 伪装成「比当前高一个版本」。
     *
     * 安装包是真实的线上 APK，所以能完整走通「检查 → 下载 → sha256/包名/签名校验 →
     * 调起安装器」这条路，不需要等到真的发下一版才能验。
     * 正式版里没有任何调用点，R8 会连这个方法一起删掉。
     */
    suspend fun simulateNewVersion(): Boolean = withContext(Dispatchers.IO) {
        val real = runCatching { fetchLatest() }.getOrNull() ?: return@withContext false
        _acknowledged.value = false
        _state.value = UpdateState.Available(
            real.copy(
                versionName = "${BuildConfig.VERSION_NAME}+test",
                versionCode = BuildConfig.VERSION_CODE + 1,
                notes = "【测试工具】这是伪造的「有新版本」，安装包是线上 Release 里的真实 APK。" +
                    "\n\n" + real.notes,
            ),
            System.currentTimeMillis(),
        )
        true
    }

    // ────────────────────────── 网络 ──────────────────────────

    private suspend fun fetchLatest(): UpdateInfo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // 闸门没放开就别发请求了：限流是按时间窗口恢复的，期间发多少都是 403
        if (blockedUntil > now) throw RateLimitException(blockedUntil)

        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent())
            .apply {
                // 带上 ETag：内容没变时服务端回 304，响应体是空的，移动网络下更快。
                // 注意它**不省配额**（匿名请求实测照样扣，见 cachedBody 的注释），
                // 省配额靠的是本地缓存 + blockedUntil 退避。
                etag?.let { header("If-None-Match", it) }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            // 304 Not Modified：isSuccessful 是 false，必须单独判
            if (response.code == 304) {
                return@withContext cachedInfo()
                    ?: throw IOException("缓存失效了，请再点一次重试")
            }

            if (!response.isSuccessful) {
                // 403 / 429 里既有「配额用完」也有「次级限流」，靠响应头区分，
                // 都拿不到时再看正文有没有提 rate limit
                val resetAt = if (response.code == 403 || response.code == 429) {
                    rateLimitResetOf(response, body)
                } else {
                    null
                }
                if (resetAt != null) {
                    blockedUntil = resetAt
                    prefs.edit().putLong(KEY_BLOCKED_UNTIL, resetAt).apply()
                    throw RateLimitException(resetAt)
                }
                throw IOException("HTTP ${response.code}${hintOf(body)}")
            }

            val info = parseRelease(body)
            // 只有真的解析成功才覆盖缓存，否则会把一份坏数据留着下次用
            cachedBody = body
            etag = response.header("ETag")
            blockedUntil = 0L
            prefs.edit()
                .putString(KEY_BODY, body)
                .putString(KEY_ETAG, etag)
                .putLong(KEY_BLOCKED_UNTIL, 0L)
                .apply()
            info
        }
    }

    /** 把 Release JSON 解析成 [UpdateInfo]，200 响应和本地缓存共用同一套逻辑 */
    private fun parseRelease(body: String): UpdateInfo {
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw IOException("GitHub 返回的内容看不懂，稍后再试") }

        val tag = json.optString("tag_name").trim()
        if (tag.isEmpty()) throw IOException("Release 上没有版本号")

        val rawBody = json.optString("body")
        val notes = cleanNotes(rawBody)
        // 注意：versionCode 要从**原始**正文里找。
        // 发布脚本会把它写成一行 HTML 注释（`<!-- versionCode: 125 -->`），
        // cleanNotes 第一步就会把注释整段删掉，清理之后再找就永远找不到。
        val declaredCode = VERSION_CODE_IN_NOTES
            .find(rawBody)
            ?.groupValues?.get(1)?.toIntOrNull()

        val assets = json.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a
                    break
                }
            }
        }
        val asset = apk ?: throw IOException("最新 Release 里没有 APK 附件")

        return UpdateInfo(
            versionName = versionFromTag(tag),
            versionCode = declaredCode,
            tagName = tag,
            notes = notes,
            sizeBytes = asset.optLong("size", 0L),
            downloadUrl = asset.optString("browser_download_url"),
            apiAssetUrl = asset.optString("url").takeIf { it.isNotBlank() },
            digest = asset.optString("digest").takeIf { it.isNotBlank() },
            publishedAt = json.optString("published_at").takeIf { it.isNotBlank() },
        )
    }

    private fun cachedInfo(): UpdateInfo? = cachedBody?.let { body ->
        runCatching { parseRelease(body) }.getOrNull()
    }

    /**
     * 从 403 / 429 里算出「什么时候能再查」。
     *
     * 三种情况都遇到得起：
     * - 配额用完：带 `X-RateLimit-Remaining: 0` + `X-RateLimit-Reset`（秒）
     * - 次级限流（短时间连打）：带 `Retry-After`（秒）
     * - 都不给：只能保守按 15 分钟后算
     */
    private fun rateLimitResetOf(response: okhttp3.Response, body: String): Long? {
        val now = System.currentTimeMillis()
        val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
        val resetSec = response.header("X-RateLimit-Reset")?.toLongOrNull()
        val retryAfter = response.header("Retry-After")
            ?.substringBefore(',')
            ?.trim()
            ?.toLongOrNull()

        return when {
            retryAfter != null && retryAfter > 0 ->
                now + retryAfter.coerceIn(1L, 24 * 60 * 60L) * 1000L
            resetSec != null && (remaining == null || remaining <= 0) ->
                (resetSec * 1000L).coerceAtLeast(now + 60_000L)
            remaining != null && remaining <= 0 -> now + DEFAULT_BACKOFF_MS
            body.contains("rate limit", ignoreCase = true) -> now + DEFAULT_BACKOFF_MS
            response.code == 429 -> now + DEFAULT_BACKOFF_MS
            else -> null
        }
    }

    /** 记一次尝试（不管成败）。自动检查的节流和「上次检查时间」都靠它 */
    private fun markAttempt(ok: Boolean) {
        val now = System.currentTimeMillis()
        lastAttemptAt = now
        lastAttemptOk = ok
        if (ok) lastSuccessAt = now
        prefs.edit()
            .putLong(KEY_ATTEMPT_AT, now)
            .putBoolean(KEY_ATTEMPT_OK, ok)
            .putLong(KEY_SUCCESS_AT, lastSuccessAt)
            .apply()
    }

    /**
     * 下载并写盘。
     *
     * 先试 api.github.com 的附件地址（`Accept: application/octet-stream`），
     * 失败再试 github.com 直链。两条路都走不通才报错。
     */
    private suspend fun downloadTo(target: File, info: UpdateInfo): Long = withContext(Dispatchers.IO) {
        val urls = buildList {
            info.apiAssetUrl?.let(::add)
            if (info.downloadUrl.isNotBlank()) add(info.downloadUrl)
        }
        if (urls.isEmpty()) throw IOException("Release 里没有可用的下载地址")

        var lastError: Exception? = null
        for (url in urls) {
            try {
                return@withContext fetchInto(url, target, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                target.delete()
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    private suspend fun fetchInto(url: String, target: File, info: UpdateInfo): Long {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", userAgent())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("下载失败：服务器没有返回内容")
            val total = body.contentLength().takeIf { it > 0L } ?: info.sizeBytes

            var written = 0L
            var lastEmit = 0L
            FileOutputStream(target).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // 用户点了取消：这里抛出 CancellationException，
                        // use 会把连接和文件都关掉
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                            lastEmit = now
                            _state.value = UpdateState.Downloading(written, total, info)
                        }
                    }
                }
                out.flush()
                // 校验前必须落盘：这时断电的话，读到的是 0 字节的残文件
                out.fd.sync()
            }
            _state.value = UpdateState.Downloading(written, total, info)
            return written
        }
    }

    // ────────────────────────── 校验 ──────────────────────────

    private data class VerifyResult(
        val installable: Boolean,
        val note: String?,
        /** 不可安装时是否还要留着文件（只有「包名不同」需要留，便于用户手动处理） */
        val keepFile: Boolean,
    )

    private fun verify(file: File, info: UpdateInfo): VerifyResult {
        if (!file.exists() || file.length() == 0L) {
            return VerifyResult(false, "下载到的文件是空的，请重试", keepFile = false)
        }
        // 服务器声明的大小和落盘大小对不上，说明连接中途断了
        if (info.sizeBytes > 0L && file.length() != info.sizeBytes) {
            return VerifyResult(
                false,
                "下载不完整（${formatSize(file.length())} / ${formatSize(info.sizeBytes)}），请重试",
                keepFile = false,
            )
        }

        // 1) Release 自带摘要
        val expected = info.digest?.substringAfter("sha256:", "")?.trim()
            ?.takeIf { it.length == 64 }
        if (expected != null) {
            val actual = sha256Of(file)
            if (!actual.equals(expected, ignoreCase = true)) {
                return VerifyResult(false, "安装包校验失败（sha256 不匹配），已删除", keepFile = false)
            }
        }

        // 2) 包名 / versionCode / 签名
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = archiveInfoOf(file)
            ?: return VerifyResult(false, "这不是一个 Android 安装包，已删除", keepFile = false)

        if (archive.packageName != context.packageName) {
            return VerifyResult(
                false,
                "当前是测试包（${context.packageName}），与正式版（${archive.packageName}）包名不同，" +
                    "无法覆盖安装。请到 GitHub Release 页面下载正式版安装包。",
                keepFile = true,
            )
        }

        if (archive.longVersionCode <= BuildConfig.VERSION_CODE) {
            return VerifyResult(
                false,
                "下载到的版本（${archive.longVersionCode}）不比当前版本（${BuildConfig.VERSION_CODE}）新",
                keepFile = false,
            )
        }

        val ownSigners = runCatching { installedSigners(flags) }.getOrNull().orEmpty()
        val newSigners = runCatching { certDigests(archive) }.getOrNull().orEmpty()
        if (ownSigners.isNotEmpty() && newSigners.isNotEmpty() && ownSigners != newSigners) {
            return VerifyResult(
                false,
                "安装包签名和当前应用不一致，已拒绝安装（可能被替换过）",
                keepFile = false,
            )
        }

        // 老 Release 没写 versionCode 时只能靠 tag 比，这里不再额外提示：
        // 只要包名与签名都对上，就说明是我们自己的包
        return VerifyResult(true, null, keepFile = true)
    }

    @Suppress("DEPRECATION")
    private fun archiveInfoOf(file: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )

    @Suppress("DEPRECATION")
    private fun installedSigners(flags: Int): Set<String> =
        certDigests(context.packageManager.getPackageInfo(context.packageName, flags))

    // ────────────────────────── 小工具 ──────────────────────────

    private fun updateDir(): File = File(context.cacheDir, "updates").apply {
        if (!isDirectory && !mkdirs()) throw IOException("创建缓存目录失败")
        // 只保留本次要下的这一个，历史包占缓存没意义
        listFiles()?.forEach { it.delete() }
    }

    private fun fileNameOf(info: UpdateInfo): String {
        val safe = info.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
        return "yfantasyxx-$safe.apk"
    }

    private fun isNewer(info: UpdateInfo): Boolean {
        info.versionCode?.let { return it > BuildConfig.VERSION_CODE }
        return compareVersionNames(info.versionName, BuildConfig.VERSION_NAME) > 0
    }

    private fun openInstallPermissionSettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun userAgent() = "YFantasyXX-Android/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private fun hintOf(body: String): String {
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
        return if (message.isNullOrBlank()) "" else "（$message）"
    }

    private fun friendlyError(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            e is RateLimitException ->
                "GitHub 免费接口每小时只有 60 次，现在用完了。额度按网络出口 IP 算，" +
                    "不是本机用超的——公共 Wi-Fi 或运营商共享 IP 下容易被别人先用掉。" +
                    "换成流量或别的 Wi-Fi 可以马上再试。"
            e is UnknownHostException || e is ConnectException ||
                raw.contains("Unable to resolve host", ignoreCase = true) ->
                "连不上 GitHub，请检查网络（部分网络环境需要代理）"
            e is SocketTimeoutException || raw.contains("timeout", ignoreCase = true) ->
                "连接 GitHub 超时，稍后再试"
            raw.contains("HTTP 403") ->
                "GitHub 拒绝了这次请求（可能是接口限流），过一会儿再试"
            raw.contains("HTTP 404") ->
                "GitHub 上还没有发布过版本，或者仓库地址变了"
            raw.contains("HTTP 5") ->
                "GitHub 服务器出错了，稍后再试"
            raw.isBlank() ->
                "检查更新失败，稍后再试"
            else -> raw
        }
    }

    companion object {
        private const val REPO_OWNER = "YFantasyXX"
        private const val REPO_NAME = "YFantasyXX"

        /** 自动检查的最小间隔（上一次成功的前提下） */
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** 上次失败时的重试间隔：断网 / 被墙时别每次冷启动都撞一次 */
        private const val FAIL_RETRY_MS = 45 * 60 * 1000L

        /** 服务端没说什么时候恢复时，保守退避这么久 */
        private const val DEFAULT_BACKOFF_MS = 15 * 60 * 1000L

        private const val KEY_BODY = "release_body"
        private const val KEY_ETAG = "release_etag"
        private const val KEY_SUCCESS_AT = "checked_at"
        private const val KEY_ATTEMPT_AT = "attempt_at"
        private const val KEY_ATTEMPT_OK = "attempt_ok"
        private const val KEY_BLOCKED_UNTIL = "blocked_until"

        /** 下载进度刷新间隔：太快会把界面刷爆，太慢进度条像卡住 */
        private const val PROGRESS_INTERVAL_MS = 150L

        /**
         * 发布说明里的 versionCode 标记，例如 `versionCode: 125`。
         * 发布脚本会往 Release 正文里写这一行。
         */
        private val VERSION_CODE_IN_NOTES =
            Regex("""(?i)versioncode\s*[:：=]\s*(\d+)""")

        private fun versionFromTag(tag: String): String =
            tag.trim().removePrefix("v").removePrefix("V").trim()

        /** 1.10 要比 1.9 大，所以按数字段逐个比，不能直接比字符串 */
        private fun compareVersionNames(a: String, b: String): Int {
            fun parts(v: String) = Regex("""\d+""")
                .findAll(v)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }

        /** Release 正文是 Markdown，我们的 Text 不解析标记，先把符号去掉 */
        private fun cleanNotes(raw: String): String {
            if (raw.isBlank()) return "这个版本没有写更新说明。"
            return raw
                .replace(Regex("""<!--[\s\S]*?-->"""), "")
                .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
                .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
                .replace(Regex("""(?m)^\s{0,3}>\s?"""), "")
                .replace(Regex("""(?m)^\s*[-*_]{3,}\s*$"""), "")
                .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                .replace(Regex("""__(.+?)__"""), "$1")
                .replace(Regex("""`([^`]*)`"""), "$1")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                .replace(Regex("""(?m)^\s*[-*+]\s+"""), "· ")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        }

        private fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().toHex()
        }

        @Suppress("DEPRECATION")
        private fun certDigests(info: PackageInfo): Set<String> {
            // signingInfo 是 API 28 起的正确入口；signatures 只在拿不到时兜底
            val certs = info.signingInfo?.apkContentsSigners
                ?: info.signatures
                ?: return emptySet()
            return certs.mapTo(HashSet()) { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .toHex()
            }
        }

        private fun ByteArray.toHex(): String {
            val chars = CharArray(size * 2)
            val digits = "0123456789abcdef"
            for (i in indices) {
                val v = this[i].toInt() and 0xFF
                chars[i * 2] = digits[v ushr 4]
                chars[i * 2 + 1] = digits[v and 0x0F]
            }
            return String(chars)
        }

        fun formatSize(bytes: Long): String = when {
            bytes <= 0L -> "未知大小"
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }
    }
}
