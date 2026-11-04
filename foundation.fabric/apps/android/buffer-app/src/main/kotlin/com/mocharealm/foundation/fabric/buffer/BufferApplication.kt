package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationWorker
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferTranscriptionWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyLlmWorker
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBridgeServer
import android.app.Application

class BufferApplication : Application() {
    @Volatile internal var fabricClient: com.mocharealm.foundation.fabric.sdk.FabricClient? = null
    internal lateinit var velaFabric: com.mocharealm.foundation.fabric.buffer.data.device.VelaFabricConnections
        private set
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

    private lateinit var healthQuantWorker: com.mocharealm.foundation.fabric.buffer.data.llm.BufferHealthQuantWorker
    private lateinit var weeklyLlmWorker: BufferWeeklyLlmWorker
    internal lateinit var capabilityWorker: com.mocharealm.foundation.fabric.buffer.data.llm.BufferCapabilityWorker
        private set
    internal lateinit var meshConfirmationWorker: com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshConfirmationWorker
        private set
    private lateinit var meshSourceWorker: com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshSourceWorker
    private lateinit var meshExecutionWorker: com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshExecutionWorker
    internal lateinit var meshChannelWorker: com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshChannelWorker
        private set
    internal lateinit var meshFactsWorker: com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshFactsWorker
        private set
    internal lateinit var meshHistoryWorker: com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshHistoryWorker
        private set
    private lateinit var captureContextWorker: com.mocharealm.foundation.fabric.buffer.data.system.BufferCaptureContextWorker
    private lateinit var sourceHistoryWorker: com.mocharealm.foundation.fabric.buffer.data.local.BufferSourceHistoryWorker

    override fun onCreate() {
        super.onCreate()
        repository = BufferRepository(this)
        meshConfirmationWorker = com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshConfirmationWorker(repository) { fabricClient }
        meshConfirmationWorker.start()
        meshChannelWorker = com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshChannelWorker(repository) { fabricClient }
        meshChannelWorker.start()
        meshSourceWorker = com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshSourceWorker(repository) { fabricClient }
        meshSourceWorker.start()
        meshExecutionWorker = com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshExecutionWorker(this, repository) { fabricClient }
        meshExecutionWorker.start()
        meshFactsWorker = com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshFactsWorker(repository) { fabricClient }
        meshFactsWorker.start()
        meshHistoryWorker = com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshHistoryWorker(repository) { fabricClient }
        meshHistoryWorker.start()
        sourceHistoryWorker = com.mocharealm.foundation.fabric.buffer.data.local.BufferSourceHistoryWorker(repository)
        sourceHistoryWorker.start()
        velaFabric = com.mocharealm.foundation.fabric.buffer.data.device.VelaFabricConnections(this, repository) { fabricClient }
        bridge = VelaBridgeServer(repository)
        transcriptionWorker = BufferTranscriptionWorker(repository) { classificationWorker.requestProcessing() }
        classificationWorker = BufferClassificationWorker(repository)
        careScheduler = BufferCareScheduler(this, repository)
        transcriptionWorker.start()
        classificationWorker.start()
        healthQuantWorker = com.mocharealm.foundation.fabric.buffer.data.llm.BufferHealthQuantWorker(repository)
        healthQuantWorker.start()
        weeklyLlmWorker = BufferWeeklyLlmWorker(repository)
        weeklyLlmWorker.start()
        capabilityWorker = com.mocharealm.foundation.fabric.buffer.data.llm.BufferCapabilityWorker(repository)
        capabilityWorker.start()
        careScheduler.start()
        captureContextWorker = com.mocharealm.foundation.fabric.buffer.data.system.BufferCaptureContextWorker(this, repository)
        captureContextWorker.start()
        Thread({ repository.recoverPendingAudio() }, "buffer-audio-recovery").start()
    }
}
