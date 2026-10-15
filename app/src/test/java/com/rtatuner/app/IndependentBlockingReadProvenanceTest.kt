package com.rtatuner.app

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import com.rtatuner.app.core.audio.*
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

/** New independent safety assertions; production sources are untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], shadows=[BlockingReadDeviceShadow::class])
class IndependentBlockingReadProvenanceTest {
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
    @After fun cleanup() { sources.forEach{it.unblockAll()}; managers.forEach{it.release()} }
    private fun device(id:Int,name:String,type:Int):AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build().also {
        Shadow.extract<BlockingReadDeviceShadow>(it).apply { auditId=id; auditName=name; auditType=type }
    }
    private fun start(route:AudioDeviceInfo?=a):Pair<AudioCaptureManager,Source> {
        val s=Source(route).also{sources.add(it)}
        val m=AudioCaptureManager(app,AudioCaptureConfig(windowSize=1024,hopSize=512),AudioRecordSourceFactory{_,_,_,_,_->s},{_,_,_->4096}).also{managers.add(it)}
        assertTrue(m.startCapture()); waitFor{m.uiState.value.routedDeviceId==route?.id && s.enteredReads.get()>0}
        return m to s
    }
    private fun waitFor(test:()->Boolean) { val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); while(!test()&&System.nanoTime()<end) Thread.sleep(2);assertTrue("worker reached deterministic boundary",test()) }
    private fun switch(s:Source,d:AudioDeviceInfo?) { s.actualRoute=d; s.listeners.single().onRoutingChanged(null) }
    private fun complete(s:Source,step:Step) {s.steps.put(step);assertTrue(step.entered.await(8,TimeUnit.SECONDS));step.resume.countDown()}
    private fun sampleStep(amplitude:Int=12000,count:Int=512,pause:Boolean=false) = Step(count,amplitude,pause)
    private fun exhausted(s:Source,count:Int) {waitFor{s.enteredReads.get()>=count}}

    @Test fun fullHopChangingRouteInsideBlockingReadCannotBeStampedAsDestination() {
        val (m,s)=start();val x=sampleStep(pause=true);s.steps.put(x);assertTrue(x.entered.await(8,TimeUnit.SECONDS));assertEquals(411,x.originRouteId)
        switch(s,b);x.resume.countDown();exhausted(s,2)
        println("FULL_READ origin=${x.originRouteId}, current=${m.uiState.value.routedDeviceId}, published=${m.uiState.value.frameContext}")
        assertEquals("A samples returned after B callback must be discarded",0L,m.uiState.value.frameSequence)
    }
    @Test fun firstPartialReadChangingRouteInsideReadCannotCombineOldSamplesWithNewRoute() {
        val (m,s)=start();val x=sampleStep(count=256,pause=true);s.steps.put(x);assertTrue(x.entered.await(8,TimeUnit.SECONDS))
        switch(s,b);x.resume.countDown();exhausted(s,2);complete(s,sampleStep(amplitude=0,count=256));exhausted(s,3)
        println("PARTIAL_READ origin=${x.originRouteId}, published=${m.uiState.value.frameContext}, rms=${m.uiState.value.rmsDb}")
        assertEquals("Partial A plus B PCM cannot be published as B",0L,m.uiState.value.frameSequence)
    }
    @Test fun aToBToAInsideBlockingReadMustInvalidateEvenEqualFinalFingerprint() {
        val (m,s)=start();val x=sampleStep(pause=true);s.steps.put(x);assertTrue(x.entered.await(8,TimeUnit.SECONDS))
        switch(s,b);switch(s,a);x.resume.countDown();exhausted(s,2)
        println("ABA_READ origin=${x.originRouteId}, published=${m.uiState.value.frameContext}")
        assertEquals("A-B-A during one native transfer must discard ambiguous PCM",0L,m.uiState.value.frameSequence)
    }
    @Test fun stopInsideBlockingReadPreventsFramePublication() {
        val (m,s)=start();val x=sampleStep(pause=true);s.steps.put(x);assertTrue(x.entered.await(8,TimeUnit.SECONDS))
        m.stopCapture();x.resume.countDown();waitFor{s.releases.get()==1}
        assertEquals(CaptureStatus.STOPPED,m.uiState.value.status);assertNull(m.uiState.value.frameContext);assertEquals(0L,m.uiState.value.frameSequence)
    }
    @Test fun routeChangeAfterCompletedReadBeforePublicationDiscardsHop() {
        val (m,s)=start();val ready=CountDownLatch(1);val resume=CountDownLatch(1)
        m.onPrePublishFrame={ready.countDown();resume.await(8,TimeUnit.SECONDS)}
        try{complete(s,sampleStep());assertTrue(ready.await(8,TimeUnit.SECONDS));switch(s,b);resume.countDown();exhausted(s,2)
            println("PREPUBLICATION published=${m.uiState.value.frameContext}")
            assertEquals("Completed old-route hop must be discarded",0L,m.uiState.value.frameSequence)
        }finally{resume.countDown();m.onPrePublishFrame=null}
    }
    @Test fun discardedHopCannotContaminateNextDestinationFrameThroughOverlapOrIirState() {
        val (m,s)=start();val ready=CountDownLatch(1);val resume=CountDownLatch(1)
        m.onPrePublishFrame={ready.countDown();resume.await(8,TimeUnit.SECONDS)}
        try{complete(s,sampleStep());assertTrue(ready.await(8,TimeUnit.SECONDS));switch(s,b);resume.countDown();exhausted(s,2);m.onPrePublishFrame=null
            val before=m.uiState.value.frameSequence;complete(s,sampleStep(amplitude=0));waitFor{m.uiState.value.frameSequence>before}
            val current=m.uiState.value
            println("DISCARDED_CONTAMINATION destination=${current.frameContext?.fingerprint?.routedMicrophoneId}, rms=${current.rmsDb}, peak=${current.peakDb}, maxBand=${current.octave31Db.maxOrNull()}, maxFft=${current.rawFftDb.maxOrNull()}")
            assertTrue("First B frame with only silent B PCM must not contain discarded A overlap",current.rmsDb<=AudioMath.MIN_DB_FS+0.001f)
            assertTrue("IIR history from discarded A must not be authorized as B",current.octave31Db.all{it<=AudioMath.MIN_DB_FS+0.001f})
        }finally{resume.countDown();m.onPrePublishFrame=null}
    }
    @Test fun acceptedARouteHistoryCannotContaminateFirstSilentBFrame() {
        val (m,s)=start();complete(s,sampleStep());waitFor{m.uiState.value.frameSequence==1L};switch(s,b)
        complete(s,sampleStep(amplitude=0));waitFor{m.uiState.value.frameSequence==2L}
        println("ACCEPTED_HISTORY B rms=${m.uiState.value.rmsDb}, maxBand=${m.uiState.value.octave31Db.maxOrNull()}")
        assertTrue("Current B frame must not contain previously accepted A overlap",m.uiState.value.rmsDb<=AudioMath.MIN_DB_FS+0.001f)
    }
    @Test fun betweenReadPartialTransitionDiscardsBothContributionsThenAcceptsFreshHop() {
        val(m,s)=start();complete(s,sampleStep(count=256));exhausted(s,2);switch(s,b);complete(s,sampleStep(count=256));exhausted(s,3)
        assertEquals("Transition spanning accumulated partial must not publish",0L,m.uiState.value.frameSequence)
        complete(s,sampleStep(amplitude=0));waitFor{m.uiState.value.frameSequence==1L};assertTrue(m.uiState.value.rmsDb<=AudioMath.MIN_DB_FS+0.001f)
        assertEquals("422:Blocking B",m.uiState.value.frameContext!!.fingerprint.routedMicrophoneId)
    }
    @Test fun unchangedRoutePartialReadMeasurementsEqualWholeHop() {
        val(m1,s1)=start();complete(s1,sampleStep());waitFor{m1.uiState.value.frameSequence==1L}
        val(m2,s2)=start();complete(s2,sampleStep(count=173));exhausted(s2,2);complete(s2,sampleStep(count=339));waitFor{m2.uiState.value.frameSequence==1L}
        assertEquals(m1.uiState.value.rmsDb,m2.uiState.value.rmsDb,0.00001f)
        assertArrayEquals(m1.uiState.value.octave31Db,m2.uiState.value.octave31Db,0.00001f)
        assertArrayEquals(m1.uiState.value.rawFftDb,m2.uiState.value.rawFftDb,0.00001f)
    }
    class Step(val count:Int,val amplitude:Int,val pause:Boolean) {
        val entered=CountDownLatch(1);val resume=CountDownLatch(if(pause)1 else 0);@Volatile var originRouteId:Int?=null
    }
    class Source(@Volatile var actualRoute:AudioDeviceInfo?):AudioRecordSource {
        override val state=AudioRecord.STATE_INITIALIZED
        @Volatile override var recordingState=AudioRecord.RECORDSTATE_STOPPED
        override val routedDevice:AudioDeviceInfo? get()=actualRoute
        val listeners=CopyOnWriteArrayList<AudioRecord.OnRoutingChangedListener>();val steps=LinkedBlockingQueue<Step>();val enteredReads=AtomicInteger();val releases=AtomicInteger();private val outstanding=CopyOnWriteArrayList<Step>();private var position=0
        override fun setPreferredDevice(device:AudioDeviceInfo?)=true
        override fun addOnRoutingChangedListener(listener:AudioRecord.OnRoutingChangedListener,handler:Handler?){listeners.add(listener)}
        override fun removeOnRoutingChangedListener(listener:AudioRecord.OnRoutingChangedListener){listeners.remove(listener)}
        override fun startRecording(){recordingState=AudioRecord.RECORDSTATE_RECORDING}
        override fun read(audioData:ShortArray,offsetInShorts:Int,sizeInShorts:Int,readMode:Int):Int {
            enteredReads.incrementAndGet()
            val step=try{steps.take()}catch(_:InterruptedException){return AudioRecord.ERROR_INVALID_OPERATION}
            if(step.count<0)return AudioRecord.ERROR_INVALID_OPERATION
            outstanding.add(step);step.originRouteId=actualRoute?.id
            val n=minOf(step.count,sizeInShorts)
            // Copy samples before callback; the return is deliberately blocked like a native transfer.
            for(i in 0 until n) audioData[offsetInShorts+i]=(step.amplitude*sin(2.0*Math.PI*1000.0*position++/48000)).toInt().toShort()
            step.entered.countDown()
            try{step.resume.await(8,TimeUnit.SECONDS)}catch(_:InterruptedException){}
            outstanding.remove(step);return n
        }
        fun unblockAll(){outstanding.forEach{it.resume.countDown()};steps.offer(Step(-1,0,false))}
        override fun stop(){recordingState=AudioRecord.RECORDSTATE_STOPPED;unblockAll()}
        override fun release(){releases.incrementAndGet();unblockAll()}
    }
}
@Implements(AudioDeviceInfo::class)
class BlockingReadDeviceShadow {
    var auditId=0;var auditName="";var auditType=AudioDeviceInfo.TYPE_BUILTIN_MIC
    @Implementation fun getId()=auditId
    @Implementation fun getProductName():CharSequence=auditName
    @Implementation fun getType()=auditType
    @Implementation fun getSampleRates()=intArrayOf(48000)
    @Implementation fun getChannelCounts()=intArrayOf(1)
    @Implementation fun isSource()=true
}

