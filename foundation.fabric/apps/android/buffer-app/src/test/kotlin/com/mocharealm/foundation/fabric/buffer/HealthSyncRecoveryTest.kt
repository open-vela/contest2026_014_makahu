package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.HealthExportResult
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncOutcome
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncRecovery

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HealthSyncRecoveryTest {
    private val synced = HealthExportResult.Synced("HydrationRecord", "provider-id", "buffer-card")

    @Test fun existingReceiptRestoresWithoutExternalWrite() {
        var restored = false
        val result = HealthSyncRecovery.sync({ true }, { restored = true },
            { error("must not export") }, { fail("must not commit") }, { fail(it) })
        assertTrue(result.success)
        assertTrue(restored)
    }
    @Test fun localFailureIsReportedWithoutThrowing() {
        var detail = ""
        val result = HealthSyncRecovery.sync({ false }, {}, { synced },
            { error("disk full") }, { detail = it })
        assertFalse(result.success)
        assertTrue(detail.contains("已写入"))
    }
    @Test fun failureReceiptFailureIsAlsoHandled() {
        val result = HealthSyncRecovery.sync({ false }, {},
            { HealthExportResult.Failed("permission revoked") }, {}, { error("disk full") })
        assertFalse(result.success)
        assertTrue(result.message.contains("permission revoked"))
        assertTrue(result.message.contains("失败记录也未保存"))
    }
    @Test fun failedReceiptLookupDoesNotWriteExternally() {
        val result = HealthSyncRecovery.sync({ error("database unavailable") }, {},
            { error("must not export") }, {}, {})
        assertFalse(result.success)
        assertEquals("database unavailable", result.message)
    }
    @Test fun retriesCommitProviderIdentity() {
        var receipt = false
        var failCommit = true
        var committed: HealthExportResult.Synced? = null
        fun attempt() = HealthSyncRecovery.sync({ receipt }, {}, { synced }, {
            if (failCommit) error("disk full")
            committed = it
            receipt = true
        }, {})
        assertFalse(attempt().success)
        failCommit = false
        assertTrue(attempt().success)
        assertEquals(synced, committed)
    }
    @Test fun concurrentActivitiesExportOnlyOnceAfterReceiptCommits() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            var written = false
            var exports = 0
            val attempts = (1..20).map {
                executor.submit<HealthSyncOutcome> {
                    HealthSyncRecovery.sync({ written }, {}, { exports++; synced }, { written = true }, {})
                }
            }
            attempts.forEach { assertTrue(it.get(5, TimeUnit.SECONDS).success) }
            assertEquals(1, exports)
        } finally { executor.shutdownNow() }
    }
    @Test fun missingDetailNeverCommitsSuccess() {
        val result = HealthSyncRecovery.sync({ false }, {},
            { HealthExportResult.NeedsDetail("需要明确时长") }, { fail("must not commit") }, {})
        assertFalse(result.success)
        assertEquals("需要明确时长", result.message)
    }
}
