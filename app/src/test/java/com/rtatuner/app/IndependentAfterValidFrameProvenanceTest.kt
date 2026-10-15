package com.rtatuner.app

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import com.rtatuner.app.core.audio.*
import com.rtatuner.app.core.dsp.BiquadFilterBank
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.math.sin

/** Independent latches around actual production manager transfers. No production source changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], shadows=[AfterValidDeviceShadow::class])
class IndependentAfterValidFrameProvenanceTest {
    private val managers=CopyOnWriteArrayList<AudioCaptureManager>()
    private val sources=CopyOnWriteArrayList<Source>()
    private lateinit var a:AudioDeviceInfo
    private lateinit var b:AudioDeviceInfo
    private val app:Application get()=RuntimeEnvironment.getApplication()
    @Before fun setup() {
        a=device(411,"Blocking A",AudioDeviceInfo.TYPE_BUILTIN_MIC)
        b=device(422,"Blocking B",AudioDeviceInfo.TYPE_USB_DEVICE)
        Shadows.shadowOf(app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setInputDevices(listOf(a,b))
    }
    @After fun cleanup() { sources.forEach{it.unblockAll()};managers.forEach{it.release()} }
    private fun device(id:Int,name:String,type:Int)=AudioDeviceInfoBuilder.newBuilder().setType(type).build().also {
        Shadow.extract<AfterValidDeviceShadow>(it).apply { auditId=id;auditName=name;auditType=type }
    }
    private fun start():Pair<AudioCaptureManager,Source> {
        val s=Source(a).also{sources.add(it)}
        val m=AudioCaptureManager(app,AudioCaptureConfig(windowSize=1024,hopSize=512),AudioRecordSourceFactory{_,_,_,_,_->s},{_,_,_->4096}).also{managers.add(it)}
        assertTrue(m.startCapture());s.nextRead();assertEquals(411,m.uiState.value.routedDeviceId)
        return m to s
    }
    private fun switch(s:Source,d:AudioDeviceInfo){s.actualRoute=d;s.listeners.single().onRoutingChanged(null)}
    private fun send(s:Source,amplitude:Int=12000,count:Int=512,pause:Boolean=false):Step {
        val step=Step(count,amplitude,pause);s.steps.put(step)
        assertTrue("PCM copied before callback",step.copied.await(8,TimeUnit.SECONDS));return step
    }
    private fun finish(s:Source,step:Step){step.resume.countDown();s.nextRead()}
    private fun hop(s:Source,amplitude:Int=12000,count:Int=512){finish(s,send(s,amplitude,count))}
    private fun acceptedA(m:AudioCaptureManager,s:Source){hop(s);assertEquals(1L,m.uiState.value.frameSequence);assertEquals("411:Blocking A",m.uiState.value.frameContext!!.fingerprint.routedMicrophoneId)}
    private fun silent(m:AudioCaptureManager,label:String){
        val x=m.uiState.value
        println("$label sequence=${x.frameSequence}, fingerprint=${x.frameContext?.fingerprint?.routedMicrophoneId}, epoch=${x.frameContext?.routeEpoch}, rms=${x.rmsDb}, peak=${x.peakDb}, bands=${x.octave31Db.contentToString()}, maxFft=${x.rawFftDb.maxOrNull()}")
        assertEquals("all 31 filters observed",31,x.octave31Db.size)
        assertTrue("$label RMS",x.rmsDb<=AudioMath.MIN_DB_FS+0.001f)
        assertTrue("$label peak",x.peakDb<=AudioMath.MIN_DB_FS+0.001f)
        assertTrue("$label all IIR bands",x.octave31Db.all{it<=AudioMath.MIN_DB_FS+0.001f})
        assertTrue("$label FFT",x.rawFftDb.all{it<=AudioMath.MIN_DB_FS+0.001f})
    }
    /** No PCM crosses the callback: zero-transfer read finishes, then new B read starts. */
    private fun cleanTransferBoundary(s:Source,d:AudioDeviceInfo){val zero=send(s,amplitude=0,count=0,pause=true);switch(s,d);finish(s,zero)}

    @Test fun acceptedAThenFullAReadBlockedAcrossBMustNotPublishAsB(){
        val(m,s)=start();acceptedA(m,s);val step=send(s,pause=true)
        assertEquals(411,step.originRouteId);val oldEpoch=m.uiState.value.captureAuthority.routeEpoch
        switch(s,b);finish(s,step)
        println("AFTER_VALID_FULL origin=${step.originRouteId}, oldEpoch=$oldEpoch, current=${m.uiState.value.captureAuthority}, published=${m.uiState.value.frameContext}, rms=${m.uiState.value.rmsDb}")
        assertEquals("A transfer interrupted by B must be discarded after a valid A frame too",1L,m.uiState.value.frameSequence)
    }
    @Test fun acceptedAThenFirstPartialAReadBlockedAcrossBMustNotCombineWithB(){
        val(m,s)=start();acceptedA(m,s);val step=send(s,count=256,pause=true);switch(s,b);finish(s,step)
        hop(s,amplitude=0,count=256)
        println("AFTER_VALID_PARTIAL origin=${step.originRouteId}, published=${m.uiState.value.frameContext}, rms=${m.uiState.value.rmsDb}")
        assertEquals("First A partial transfer must not become first half of B hop",1L,m.uiState.value.frameSequence)
    }
    @Test fun acceptedAThenBlockedAToBToAMustInvalidateEvenSameFingerprint(){
        val(m,s)=start();acceptedA(m,s);val epoch=m.uiState.value.captureAuthority.routeEpoch
        val step=send(s,pause=true);switch(s,b);switch(s,a);finish(s,step)
        println("AFTER_VALID_ABA oldEpoch=$epoch, published=${m.uiState.value.frameContext}")
        assertEquals("Epoch changes during transfer invalidate A-B-A despite equal final microphone",1L,m.uiState.value.frameSequence)
    }
    @Test fun acceptedAThenStopDuringBlockedReadCannotPublish(){
        val(m,s)=start();acceptedA(m,s);val step=send(s,pause=true);m.stopCapture();step.resume.countDown()
        assertTrue(s.released.await(8,TimeUnit.SECONDS));assertEquals(CaptureStatus.STOPPED,m.uiState.value.status)
        assertEquals("STOP intentionally clears the published frame and sequence",0L,m.uiState.value.frameSequence);assertNull(m.uiState.value.frameContext)
    }
    @Test fun acceptedAThenRouteAfterDspBeforePublicationDropsAndResets(){
        val(m,s)=start();acceptedA(m,s);val ready=CountDownLatch(1);val resume=CountDownLatch(1)
        m.onPrePublishFrame={ready.countDown();assertTrue(resume.await(8,TimeUnit.SECONDS))}
        try{send(s);assertTrue(ready.await(8,TimeUnit.SECONDS));switch(s,b);resume.countDown();s.nextRead();m.onPrePublishFrame=null
            assertEquals(1L,m.uiState.value.frameSequence);hop(s,0);assertEquals(2L,m.uiState.value.frameSequence);silent(m,"ACCEPTED_THEN_REJECTED")
        }finally{resume.countDown();m.onPrePublishFrame=null}
    }
    @Test fun acceptedLoudAThenCleanSilentBHasNoAOverlapOrAnyIirHistory(){
        val(m,s)=start();acceptedA(m,s);cleanTransferBoundary(s,b);hop(s,0)
        assertEquals(2L,m.uiState.value.frameSequence);assertEquals("422:Blocking B",m.uiState.value.frameContext!!.fingerprint.routedMicrophoneId);silent(m,"ACCEPTED_A_SILENT_B")
    }
    @Test fun discardedLoudAThenSilentBHasNoAOverlapOrAnyIirHistory(){
        val(m,s)=start();val ready=CountDownLatch(1);val resume=CountDownLatch(1)
        m.onPrePublishFrame={ready.countDown();assertTrue(resume.await(8,TimeUnit.SECONDS))}
        try{send(s);assertTrue(ready.await(8,TimeUnit.SECONDS));switch(s,b);resume.countDown();s.nextRead();m.onPrePublishFrame=null
            assertEquals(0L,m.uiState.value.frameSequence);hop(s,0);assertEquals(1L,m.uiState.value.frameSequence);silent(m,"DISCARDED_A_SILENT_B")
        }finally{resume.countDown();m.onPrePublishFrame=null}
    }
    @Test fun partialAThenRouteSwitchThenSilentBHasNoPartialOrIirContamination(){
        val(m,s)=start();hop(s,count=256);val step=send(s,amplitude=0,count=256,pause=true);switch(s,b);finish(s,step)
        assertEquals(0L,m.uiState.value.frameSequence);hop(s,0);assertEquals(1L,m.uiState.value.frameSequence);silent(m,"PARTIAL_A_SILENT_B")
    }
    @Test fun invalidatedAToBToAThenSilentAHasNoIntermediateHistory(){
        val(m,s)=start();val step=send(s,pause=true);switch(s,b);switch(s,a);finish(s,step)
        assertEquals(0L,m.uiState.value.frameSequence);hop(s,0);assertEquals(1L,m.uiState.value.frameSequence);silent(m,"INVALIDATED_ABA_SILENT_A")
    }
    @Test fun cleanBTransfersAfterTransitionAreAcceptedConsecutively(){
        val(m,s)=start();acceptedA(m,s)
        // Real between-read boundary: the second A read has already returned, and
        // production DSP is paused before publication and before its next read.
        val ready=CountDownLatch(1);val resume=CountDownLatch(1)
        m.onPrePublishFrame={ready.countDown();assertTrue(resume.await(8,TimeUnit.SECONDS))}
        try{send(s,amplitude=0);assertTrue(ready.await(8,TimeUnit.SECONDS));switch(s,b);resume.countDown();s.nextRead();m.onPrePublishFrame=null
            assertEquals("pending A frame invalidated before publication",1L,m.uiState.value.frameSequence)
            for(i in 2L..5L){hop(s,0);assertEquals("each legitimate B read accepted",i,m.uiState.value.frameSequence);assertEquals("422:Blocking B",m.uiState.value.frameContext!!.fingerprint.routedMicrophoneId);silent(m,"CLEAN_B_$i")}
        }finally{resume.countDown();m.onPrePublishFrame=null}
    }
    @Test fun uninterruptedSameRouteRetainsOverlapAndIirState(){
        val(m,s)=start();acceptedA(m,s);val first=m.uiState.value.octave31Db.clone();hop(s,0)
        val x=m.uiState.value;assertEquals(2L,x.frameSequence);assertEquals("411:Blocking A",x.frameContext!!.fingerprint.routedMicrophoneId)
        assertTrue("same-route overlap deliberately preserved",x.rmsDb>AudioMath.MIN_DB_FS+20f)
        assertTrue("same-route IIR tail deliberately preserved",x.octave31Db.any{it>AudioMath.MIN_DB_FS+20f})
        assertFalse("filter history must advance",first.contentEquals(x.octave31Db))
        val reference=BiquadFilterBank(sampleRate=48000);reference.processHop(s.generatedPcm(0,512,12000));val expected=reference.processHop(ShortArray(512))
        assertArrayEquals("continuous same-route IIR exact reference",expected,x.octave31Db,0.00001f)
        println("CONTINUOUS_A rms=${x.rmsDb}, maxBand=${x.octave31Db.maxOrNull()}, maxFft=${x.rawFftDb.maxOrNull()}")
    }

    class Step(val count:Int,val amplitude:Int,pause:Boolean){val copied=CountDownLatch(1);val resume=CountDownLatch(if(pause)1 else 0);@Volatile var originRouteId:Int?=null}
    class Source(@Volatile var actualRoute:AudioDeviceInfo):AudioRecordSource{
        override val state=AudioRecord.STATE_INITIALIZED
        @Volatile override var recordingState=AudioRecord.RECORDSTATE_STOPPED
        override val routedDevice:AudioDeviceInfo get()=actualRoute
        val listeners=CopyOnWriteArrayList<AudioRecord.OnRoutingChangedListener>();val steps=LinkedBlockingQueue<Step>();val reads=LinkedBlockingQueue<Int>();val released=CountDownLatch(1)
        private val active=CopyOnWriteArrayList<Step>();private val readCount=AtomicInteger();private var expectedRead=0;private var position=0
        fun nextRead(){expectedRead++;assertEquals("previous transfer and publication completed before next read",expectedRead,reads.poll(8,TimeUnit.SECONDS))}
        fun generatedPcm(from:Int,count:Int,amplitude:Int)=ShortArray(count){(amplitude*sin(2.0*Math.PI*1000.0*(from+it)/48000)).toInt().toShort()}
        override fun setPreferredDevice(device:AudioDeviceInfo?)=true
        override fun addOnRoutingChangedListener(listener:AudioRecord.OnRoutingChangedListener,handler:Handler?){listeners.add(listener)}
        override fun removeOnRoutingChangedListener(listener:AudioRecord.OnRoutingChangedListener){listeners.remove(listener)}
        override fun startRecording(){recordingState=AudioRecord.RECORDSTATE_RECORDING}
        override fun read(audioData:ShortArray,offsetInShorts:Int,sizeInShorts:Int,readMode:Int):Int{
            reads.put(readCount.incrementAndGet());val step=try{steps.take()}catch(_:InterruptedException){return AudioRecord.ERROR_INVALID_OPERATION}
            if(step.count<0)return AudioRecord.ERROR_INVALID_OPERATION
            active.add(step);step.originRouteId=actualRoute.id;val n=minOf(step.count,sizeInShorts)
            val pcm=generatedPcm(position,n,step.amplitude);position+=n;System.arraycopy(pcm,0,audioData,offsetInShorts,n);step.copied.countDown()
            try{step.resume.await(8,TimeUnit.SECONDS)}catch(_:InterruptedException){};active.remove(step);return n
        }
        fun unblockAll(){active.forEach{it.resume.countDown()};steps.offer(Step(-1,0,false))}
        override fun stop(){recordingState=AudioRecord.RECORDSTATE_STOPPED;unblockAll()}
        override fun release(){released.countDown();unblockAll()}
    }
}
@Implements(AudioDeviceInfo::class)
class AfterValidDeviceShadow{
    var auditId=0;var auditName="";var auditType=AudioDeviceInfo.TYPE_BUILTIN_MIC
    @Implementation fun getId()=auditId
    @Implementation fun getProductName():CharSequence=auditName
    @Implementation fun getType()=auditType
    @Implementation fun getSampleRates()=intArrayOf(48000)
    @Implementation fun getChannelCounts()=intArrayOf(1)
    @Implementation fun isSource()=true
}
