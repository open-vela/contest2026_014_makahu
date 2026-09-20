package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationWorker
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferTranscriptionWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyLlmWorker
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBridgeServer
import android.app.Application

class BufferApplication : Application() {
    lateinit var repository: BufferRepository
        private set

    lateinit var bridge: VelaBridgeServer
        private set

    lateinit var transcriptionWorker: BufferTranscriptionWorker
        private set

    lateinit var classificationWorker: BufferClassificationWorker
        private set

    lateinit var careScheduler: BufferCareScheduler
        private set

    private lateinit var weeklyLlmWorker: BufferWeeklyLlmWorker

    override fun onCreate() {
        super.onCreate()
        repository = BufferRepository(this)
        bridge = VelaBridgeServer(repository)
        transcriptionWorker = BufferTranscriptionWorker(repository) { classificationWorker.requestProcessing() }
        classificationWorker = BufferClassificationWorker(repository)
        careScheduler = BufferCareScheduler(this, repository)
        transcriptionWorker.start()
        classificationWorker.start()
        weeklyLlmWorker = BufferWeeklyLlmWorker(repository)
        weeklyLlmWorker.start()
        careScheduler.start()
        Thread({ repository.recoverPendingAudio() }, "buffer-audio-recovery").start()
    }
}
