package com.mocharealm.foundation.fabric.buffer.data.system

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.CaptureContext
import com.mocharealm.foundation.fabric.buffer.domain.CaptureContextJob
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/** Original input is committed before this worker reads any system service. */
internal class BufferCaptureContextWorker(context: Context, private val repository: BufferRepository) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "buffer-context").apply { isDaemon = true } }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "buffer-context-deadline").apply { isDaemon = true } }

    fun start() {
        worker.scheduleWithFixedDelay({
            try {
                val job = repository.pendingCaptureContext() ?: return@scheduleWithFixedDelay
                val snapshot = collect(job)
                repository.completeCaptureContext(job, snapshot)
            } catch (_: Exception) {
                // A failed DB write leaves the immutable request pending. A
                // later attempt records expiry instead of fabricating history.
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun allowed(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun collect(job: CaptureContextJob): JSONObject {
        val now = System.currentTimeMillis()
        if (!job.local) return CaptureContext.empty(job.capturedAt, job.source, "not_provided", now)
        if (repository.captureContextPolicy().getLong("revision") != job.policy.getLong("revision"))
            return CaptureContext.empty(job.capturedAt, job.source, "cancelled", now)
        val expired = now < job.capturedAt || now - job.capturedAt > CaptureContext.MAX_AGE_MS
        val value = CaptureContext.empty(job.capturedAt, job.source, "disabled", now)
        value.put("location", if (!job.policy.getBoolean("location")) CaptureContext.section("disabled")
            else if (expired) CaptureContext.section("expired")
            else if (!job.policy.getBoolean("location_granted") || !allowed(Manifest.permission.ACCESS_COARSE_LOCATION)) CaptureContext.section("permission_denied")
            else location(job.capturedAt))
        value.put("calendar", if (!job.policy.getBoolean("calendar")) CaptureContext.section("disabled")
            else if (expired) CaptureContext.section("expired")
            else if (!job.policy.getBoolean("calendar_granted") || !allowed(Manifest.permission.READ_CALENDAR)) CaptureContext.section("permission_denied")
            else calendar(job.capturedAt))
        return CaptureContext.validate(value)
    }

    private fun location(at: Long): JSONObject = try {
        val manager = app.getSystemService(LocationManager::class.java)
        if (manager == null || !manager.isLocationEnabled || !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) CaptureContext.section("service_unavailable")
        else {
            val fix = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (fix == null || fix.time > at || at - fix.time > 900_000 || fix.time < 0 ||
                !fix.latitude.isFinite() || !fix.longitude.isFinite() || fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0)
                CaptureContext.section("no_recent_fix")
            else {
                // Store a region of roughly 11 km, even if another feature
                // has fine permission. Never persist the precise fix/address.
                val south = floor((fix.latitude + 90) * 10).coerceIn(0.0, 1799.0) / 10 - 90
                val north = (south + 0.1).coerceAtMost(90.0)
                val width = 0.1 / cos(Math.toRadians(maxOf(abs(south), abs(north)))).coerceAtLeast(0.000001)
                val cells = floor(360 / width).toInt().coerceAtLeast(1)
                val step = 360.0 / cells
                val west = floor((fix.longitude + 180) / step).coerceIn(0.0, cells - 1.0) * step - 180
                JSONObject().put("status", "available").put("observed_at", fix.time)
                    .put("latitude_min", south).put("latitude_max", north)
                    .put("longitude_min", west).put("longitude_max", (west + step).coerceAtMost(180.0))
            }
        }
    } catch (_: SecurityException) { CaptureContext.section("permission_denied") }
      catch (_: Exception) { CaptureContext.section("provider_error") }

    private fun calendar(at: Long): JSONObject {
        val signal = CancellationSignal()
        val timeout = deadlines.schedule({ signal.cancel() }, 1500, TimeUnit.MILLISECONDS)
        return try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, at); ContentUris.appendId(it, at + 1)
            }.build()
            val columns = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.STATUS)
            val events = JSONArray(); var more = false
            val rows = app.contentResolver.query(uri, columns, null, null, CalendarContract.Instances.BEGIN + " ASC", signal)
                ?: return CaptureContext.section("provider_error")
            rows.use { cursor ->
                while (cursor.moveToNext()) {
                    if (signal.isCanceled) return CaptureContext.section("provider_error")
                    val begin = cursor.getLong(1); val end = cursor.getLong(2)
                    if (cursor.getInt(3) != 0 || cursor.getInt(4) == CalendarContract.Events.STATUS_CANCELED || begin > at || end <= at || begin < 0) continue
                    if (events.length() == 3) { more = true; break }
                    events.put(JSONObject().put("title", cursor.getString(0).orEmpty().replace("\u0000", "").take(96))
                        .put("begin", begin).put("end", end))
                }
            }
            JSONObject().put("status", "available").put("events", events).put("more", more)
        } catch (_: SecurityException) { CaptureContext.section("permission_denied") }
          catch (_: Exception) { CaptureContext.section("provider_error") }
          finally { timeout.cancel(false); signal.cancel() }
    }
}
