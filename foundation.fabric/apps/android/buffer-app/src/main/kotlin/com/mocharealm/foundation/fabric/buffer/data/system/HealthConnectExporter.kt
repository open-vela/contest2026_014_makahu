package com.mocharealm.foundation.fabric.buffer.data.system
import com.mocharealm.foundation.fabric.buffer.domain.HealthExportResult
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Keeps Health Connect behind the phone boundary. Vela only sends a card; it
 * never receives a permission or a Health Connect action.
 */
class HealthConnectExporter(context: Context) {
    private val appContext = context.applicationContext

    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(appContext)
    }

    suspend fun export(card: BufferCard): HealthExportResult {
        val category = card.healthCategory
            ?: return HealthExportResult.Unsupported("这张记录没有健康数据分类")
        val permissions = requiredPermissions(category)
        if (permissions.isEmpty()) {
            return HealthExportResult.Unsupported("$category 目前只保存在 Buffer 本地")
        }
        when (availability(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> Unit
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                return HealthExportResult.Failed("Health Connect 未安装或需要更新")
            else -> return HealthExportResult.Failed("当前设备没有可用的 Health Connect")
        }

        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(permissions)) {
            return HealthExportResult.Failed("尚未授予 Health Connect 写入权限")
        }

        val record = createRecord(card, category)
            ?: return HealthExportResult.NeedsDetail(detailMessage(category))
        val recordType = record::class.simpleName ?: "HealthRecord"
        return try {
            val response = client.insertRecords(listOf(record))
            HealthExportResult.Synced(recordType, response.recordIdsList.singleOrNull(), record.metadata.clientRecordId)
        } catch (error: Exception) {
            HealthExportResult.Failed(error.message ?: "Health Connect 写入失败")
        }
    }

    private fun createRecord(card: BufferCard, category: String): Record? {
        val start = Instant.ofEpochMilli(card.createdAt)
        val offset = ZoneId.systemDefault().rules.getOffset(start)
        val metadata = Metadata.manualEntry(clientRecordId = "buffer-${card.cardId}")
        val summary = card.summary.take(MAX_NOTE_LENGTH)

        return when (category) {
            "nutrition" -> NutritionRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = start.plusSeconds(EVENT_INTERVAL_SECONDS),
                endZoneOffset = offset,
                metadata = metadata,
                name = summary,
                mealType = MealType.MEAL_TYPE_UNKNOWN,
            )
            "hydration" -> {
                val milliliters = parseVolumeMilliliters(card.summary) ?: return null
                HydrationRecord(
                    startTime = start,
                    startZoneOffset = offset,
                    endTime = start.plusSeconds(EVENT_INTERVAL_SECONDS),
                    endZoneOffset = offset,
                    volume = Volume.milliliters(milliliters),
                    metadata = metadata,
                )
            }
            "exercise" -> {
                val minutes = parseDurationMinutes(card.summary) ?: return null
                ExerciseSessionRecord(
                    startTime = start,
                    startZoneOffset = offset,
                    endTime = start.plusSeconds(minutes * 60L),
                    endZoneOffset = offset,
                    metadata = metadata,
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
                    title = card.title.take(MAX_NOTE_LENGTH),
                    notes = summary,
                )
            }
            "sleep" -> {
                val minutes = parseDurationMinutes(card.summary) ?: return null
                SleepSessionRecord(
                    startTime = start,
                    startZoneOffset = offset,
                    endTime = start.plusSeconds(minutes * 60L),
                    endZoneOffset = offset,
                    metadata = metadata,
                    title = card.title.take(MAX_NOTE_LENGTH),
                    notes = summary,
                )
            }
            "mindfulness" -> {
                val minutes = parseDurationMinutes(card.summary) ?: return null
                MindfulnessSessionRecord(
                    startTime = start,
                    startZoneOffset = offset,
                    endTime = start.plusSeconds(minutes * 60L),
                    endZoneOffset = offset,
                    metadata = metadata,
                    mindfulnessSessionType = if (card.summary.contains("呼吸")) {
                        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING
                    } else {
                        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_UNGUIDED
                    },
                    title = card.title.take(MAX_NOTE_LENGTH),
                    notes = summary,
                )
            }
            else -> null
        }
    }

    private fun parseVolumeMilliliters(text: String): Double? {
        val match = Regex(
            """(\d+(?:[.,]\d+)?)\s*(毫升|ml|升|l)""",
            RegexOption.IGNORE_CASE,
        ).find(text) ?: return null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return if (match.groupValues[2].lowercase(Locale.ROOT) == "升" ||
            match.groupValues[2].equals("l", ignoreCase = true)
        ) {
            amount * 1000.0
        } else {
            amount
        }.takeIf { it > 0.0 && it <= MAX_HYDRATION_MILLILITERS }
    }

    private fun parseDurationMinutes(text: String): Long? {
        if (text.contains("半小时")) return 30L
        val match = Regex(
            """(\d+(?:[.,]\d+)?)\s*(分钟|min|mins|小时|h|hour|hours)""",
            RegexOption.IGNORE_CASE,
        ).find(text) ?: return null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val minutes = if (unit == "小时" || unit == "h" || unit.startsWith("hour")) {
            amount * 60.0
        } else {
            amount
        }
        return minutes.toLong().takeIf { it > 0L && it <= MAX_DURATION_MINUTES }
    }

    private fun detailMessage(category: String): String = when (category) {
        "hydration" -> "饮水记录需要包含数量，例如“喝水 300 ml”"
        "exercise" -> "运动记录需要包含时长，例如“走路 30 分钟”"
        "sleep" -> "睡眠记录需要包含时长，例如“睡了 8 小时”"
        "mindfulness" -> "静心练习记录需要包含时长，例如“冥想 10 分钟”"
        else -> "这条记录缺少可写入 Health Connect 的明确信息"
    }

    companion object {
        private const val EVENT_INTERVAL_SECONDS = 1L
        private const val MAX_NOTE_LENGTH = 1_000
        private const val MAX_HYDRATION_MILLILITERS = 100_000.0
        private const val MAX_DURATION_MINUTES = 24L * 60L

        fun availability(context: Context): Int = try {
            HealthConnectClient.getSdkStatus(context.applicationContext)
        } catch (_: Exception) {
            HealthConnectClient.SDK_UNAVAILABLE
        }

        fun requiredPermissions(category: String?): Set<String> = when (category) {
            "nutrition" -> setOf(HealthPermission.getWritePermission(NutritionRecord::class))
            "hydration" -> setOf(HealthPermission.getWritePermission(HydrationRecord::class))
            "exercise" -> setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
            "sleep" -> setOf(HealthPermission.getWritePermission(SleepSessionRecord::class))
            "mindfulness" -> setOf(HealthPermission.getWritePermission(MindfulnessSessionRecord::class))
            else -> emptySet()
        }

        fun canExport(category: String?): Boolean = requiredPermissions(category).isNotEmpty()
    }
}
