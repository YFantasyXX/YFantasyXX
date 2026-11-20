package com.yfantasyxx.data

import com.yfantasyxx.data.db.AppDatabase
import com.yfantasyxx.data.db.CardioSession
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 有氧器械的一个可调参数。
 *
 * 每种有氧最多两项：跑步机是坡度 + 速度，骑行是阻力 + 速度，其余大多一项。
 * 用同一套结构描述，界面就不用为十种类型写十份表单；以后加类型或加参数，
 * 只要在 [CardioType] 的 controls 里列出来，存库那边一个 JSON 字段全包了。
 */
data class CardioControl(
    /** 存 JSON 的 key，同一类型内唯一 */
    val key: String,
    val label: String,
    /** 带前导空格的单位，如 " km/h"；百分号这种紧贴数字的写 "%" */
    val unit: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val initial: Float,
    val decimals: Int = 1,
) {
    fun format(value: Float): String = if (decimals == 0) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.${decimals}f", value)
    }
}

/**
 * 有氧参数的编解码。
 *
 * 全部塞进一个 JSON 文本列，而不是给每种器械各开一列：
 * 加参数不再需要迁移数据库，旧记录读到空串就当「没调过」。
 */
object CardioControls {

    fun defaults(type: CardioType): Map<String, Float> =
        type.controls.associate { it.key to it.initial }

    fun decode(json: String?): Map<String, Float> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associate { key -> key to obj.getDouble(key).toFloat() }
        }.getOrDefault(emptyMap())
    }

    fun encode(values: Map<String, Float>): String {
        if (values.isEmpty()) return ""
        val obj = JSONObject()
        values.forEach { (key, value) ->
            obj.put(key, (value * 100f).roundToInt() / 100.0)
        }
        return obj.toString()
    }

    /** 列表/详情里那一行摘要。没调过的项不画，不会满屏 `--` 占位 */
    fun summary(type: CardioType, json: String?): String {
        val values = decode(json)
        return type.controls.mapNotNull { control ->
            values[control.key]?.let { "${control.label} ${control.format(it)}${control.unit}" }
        }.joinToString(" · ")
    }
}

/**
 * 有氧运动类型。
 *
 * [key] 存库，[label] 只用于显示：以后改文案、调顺序都不会碰到历史数据。
 * [metric] 决定「有距离时那一行显示什么」——跑步看配速（min/km），
 * 骑行看时速（km/h），器械类没有公认口径，干脆不显示。
 * [controls] 是这项运动可以调的器械参数，0~2 项。
 */
enum class CardioType(
    val key: String,
    val label: String,
    val metric: CardioMetric,
    val controls: List<CardioControl> = emptyList(),
) {
    TREADMILL(
        "treadmill", "跑步机", CardioMetric.SPEED,
        listOf(
            CardioControl("incline", "坡度", "%", 0f, 15f, 0.5f, 1f),
            CardioControl("speed", "速度", " km/h", 0f, 25f, 0.5f, 8f),
        ),
    ),
    RUN(
        "run", "跑步", CardioMetric.PACE,
        listOf(CardioControl("pace", "目标配速", " min/km", 3f, 12f, 0.1f, 6f)),
    ),
    WALK(
        "walk", "快走", CardioMetric.PACE,
        listOf(CardioControl("speed", "速度", " km/h", 1f, 10f, 0.5f, 5f)),
    ),
    BIKE(
        "bike", "骑行", CardioMetric.SPEED,
        listOf(
            CardioControl("resistance", "阻力", " 档", 1f, 20f, 1f, 5f, decimals = 0),
            CardioControl("speed", "速度", " km/h", 0f, 60f, 1f, 20f, decimals = 0),
        ),
    ),
    ELLIPTICAL(
        "elliptical", "椭圆机", CardioMetric.NONE,
        listOf(CardioControl("resistance", "阻力", " 档", 1f, 20f, 1f, 5f, decimals = 0)),
    ),
    ROWER(
        "rower", "划船机", CardioMetric.NONE,
        listOf(CardioControl("resistance", "阻力", " 档", 1f, 10f, 1f, 4f, decimals = 0)),
    ),
    ROPE(
        "rope", "跳绳", CardioMetric.NONE,
        listOf(CardioControl("rate", "速度", " 次/分", 20f, 200f, 10f, 80f, decimals = 0)),
    ),
    STAIR(
        "stair", "爬楼机", CardioMetric.NONE,
        listOf(CardioControl("level", "档位", " 档", 1f, 20f, 1f, 5f, decimals = 0)),
    ),
    SWIM(
        "swim", "游泳", CardioMetric.PACE,
        listOf(CardioControl("pace", "配速", " min/100m", 1f, 5f, 0.1f, 2f)),
    ),
    OTHER(
        "other", "其他", CardioMetric.NONE,
        listOf(CardioControl("intensity", "强度", " 档", 1f, 10f, 1f, 5f, decimals = 0)),
    );

    companion object {
        /** 未知 key 一律当「其他」：旧版本读到未来版本写的新类型时不至于崩 */
        fun from(key: String?): CardioType = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

enum class CardioMetric { PACE, SPEED, NONE }

/**
 * 有氧记录仓库。
 *
 * 和 [WorkoutRepository] 一样按当前库取 DAO，不缓存实例：
 * 恢复备份会把旧库 close 掉重建，缓存下来的 DAO 之后每次调用都会抛
 * `attempt to re-open an already-closed object`。
 */
class CardioRepository(private val dbProvider: () -> AppDatabase) {

    private fun dao() = dbProvider().cardioDao()

    fun observeRecent(limit: Int = 200): Flow<List<CardioSession>> = dao().observeRecent(limit)

    fun observeSince(since: Long): Flow<List<CardioSession>> = dao().observeSince(since)

    /** 全部记录：历史页翻月用，见 [com.yfantasyxx.data.db.CardioDao.observeAll] */
    fun observeAll(): Flow<List<CardioSession>> = dao().observeAll()

    fun observeLatest(): Flow<CardioSession?> = dao().observeLatest()

    suspend fun get(id: Long): CardioSession? = dao().getById(id)

    suspend fun add(
        type: CardioType,
        startedAt: Long,
        durationMs: Long,
        distanceKm: Float,
        note: String,
        manual: Boolean,
        settings: String = "",
    ): Long = dao().insert(
        CardioSession(
            type = type.key,
            startedAt = startedAt,
            durationMs = durationMs.coerceAtLeast(0L),
            distanceKm = distanceKm.coerceAtLeast(0f),
            note = note.trim(),
            manual = manual,
            settings = settings,
        )
    )

    suspend fun update(session: CardioSession) = dao().update(
        session.copy(
            durationMs = session.durationMs.coerceAtLeast(0L),
            distanceKm = session.distanceKm.coerceAtLeast(0f),
            note = session.note.trim(),
        )
    )

    suspend fun delete(id: Long) = dao().delete(id)
}
