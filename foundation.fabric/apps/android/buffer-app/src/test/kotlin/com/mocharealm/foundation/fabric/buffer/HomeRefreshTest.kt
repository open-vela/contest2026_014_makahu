package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBridgeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class HomeRefreshTest {
    @Test fun repositoryContentionDoesNotBlockMainOrQueuePeriodicRefreshes() {
        val activity=Robolectric.buildActivity(MainActivity::class.java).get()
        val repository=BufferRepository(RuntimeEnvironment.getApplication())
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        field("repository").set(activity,repository)
        field("bridge").set(activity,VelaBridgeServer(repository))
        val refresh=MainActivity::class.java.getDeclaredMethod("refresh").apply{isAccessible=true}
        val locked=CountDownLatch(1);val release=CountDownLatch(1)
        val writer=Thread { synchronized(repository){locked.countDown();release.await()} }
        writer.start();assertTrue(locked.await(2,TimeUnit.SECONDS))
        try {
            refresh.invoke(activity)
            val job=field("homeRefreshJob").get(activity) as Job
            assertTrue(job.isActive)
            repeat(200){refresh.invoke(activity);assertSame(job,field("homeRefreshJob").get(activity))}
            assertFalse(field("homeSnapshotLoaded").getBoolean(activity))
            release.countDown();writer.join(2000)
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(job.isActive && System.nanoTime()<deadline){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertFalse("Coalesced refresh never completed",job.isActive)
            assertTrue(field("homeSnapshotLoaded").getBoolean(activity))
        } finally {
            release.countDown();writer.join(2000)
            (field("homeRefreshScope").get(activity) as CoroutineScope).cancel()
            repository.close()
        }
    }
}
