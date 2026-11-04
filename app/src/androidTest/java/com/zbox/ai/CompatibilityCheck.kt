package com.zbox.ai

import android.app.Activity
import android.app.Instrumentation
import android.app.Application
import android.os.Bundle
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.core.CameraStorage
import com.zbox.ai.core.CameraAperturePolicy
import com.zbox.ai.core.PhoneToolsPolicy
import com.zbox.ai.ptpip.*
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Local fake responder only. No discovery, real camera, registration of a real host or AI. */
class CompatibilityCheck:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private var checks=0
    private fun verify(value:Boolean,message:String){check(value){message};checks++}
    private fun rejected(f:()->Unit){verify(runCatching(f).isFailure,"Malformed input accepted")}
    override fun onStart(){val result=Bundle();var phase="policy"
        try{runBlocking {
            val apertureEnum = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0x5007.toShort()).putShort(4).put(1).putShort(560).putShort(560).put(2).putShort(5)
                .putShort(95).putShort(280).putShort(400).putShort(560).putShort(800).array()
            val enumCapability = CameraAperturePolicy.parseDescriptor(apertureEnum)
            verify(enumCapability?.values == listOf(0.95f,2.8f,4f,5.6f,8f),"Camera aperture enum range includes sub-F1 lenses")
            verify(enumCapability?.current == 5.6f && enumCapability.writable,"Camera aperture current/write flag")
            val apertureRange = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0x5007.toShort()).putShort(4).put(1).putShort(560).putShort(560).put(1)
                .putShort(280).putShort(800).putShort(40).array()
            verify(CameraAperturePolicy.parseDescriptor(apertureRange)?.values?.let { it.first()==2.8f && it.last()==8f && it.size==14 } == true,
                "Camera aperture range min/max/step")
            verify(CameraAperturePolicy.parseDescriptor(apertureEnum.copyOf(13)) == null,"Truncated aperture descriptor rejected")
            verify(NikonConnectionPolicy.pairingCode(byteArrayOf())=="","No-PIN registration")
            for(ascii in listOf(false,true)) {
                val data=ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putInt(6)
                    .put((if(ascii) "123456".toByteArray() else byteArrayOf(1,2,3,4,5,6))).array()
                verify(NikonConnectionPolicy.pairingCode(data)=="123456","PIN representation")
            }
            rejected{NikonConnectionPolicy.pairingCode(byteArrayOf(1,0,0,0,1))}
            rejected{NikonConnectionPolicy.pairingCode(byteArrayOf(4,0,0,0,1,2,3,99))}
            rejected{NikonConnectionPolicy.packetLength(7)}
            rejected{NikonConnectionPolicy.packetLength(0xffffffffL)}
            verify(NikonConnectionPolicy.packetLength(8)==8,"Minimum frame")
            verify(NikonConnectionPolicy.remainingSettle(0)==8500L,"Settle start")
            verify(NikonConnectionPolicy.remainingSettle(8499)==1L,"Settle boundary")
            verify(NikonConnectionPolicy.remainingSettle(9000)==0L,"Settle complete")
            verify(NikonConnectionPolicy.sessionId(true,0)==2L,"STA avoids fixed session1")
            verify(NikonConnectionPolicy.sessionId(false,42)==1L,"Legacy non-STA unchanged")
            verify(NikonConnectionPolicy.model("NIKON Z 50Ⅱ")=="NIKON Z 50II","Roman generation retained")
            verify(NikonConnectionPolicy.model("Z6III_12345678")=="Z6III","Serial suffix separated")
            verify(NikonConnectionPolicy.model("Z502")=="Z502","Unknown model not guessed")
            verify(NikonConnectionPolicy.permitsLegacyPictureControlWrite("NIKON Z 30"),"Z30 write policy")
            verify(!NikonConnectionPolicy.permitsLegacyPictureControlWrite("NIKON Z 50II"),"New model must not receive Z30 writes")
            verify(NikonConnectionPolicy.isCellularOrTunnel("rmnet_data0"),"Cellular excluded")
            verify(!NikonConnectionPolicy.isCellularOrTunnel("wlan1"),"Hotspot retained")
            for(sta in listOf(false,true)) {
                phase="mock transport sta=$sta"
                FakeNikon().use { fake ->
                    val transport=PtpIpTransport("127.0.0.1",fake.port,DiagnosticLog(),staMode=sta,commandTimeoutMs=2000)
                    try{
                        verify(transport.connect().ok,"Handshake failed")
                        verify(fake.session.get()==transport.ptpSessionId.toInt(),"Session ID mismatch")
                        verify(fake.commandInitDelay.get()>=300,"Command TCP settle missing")
                        verify(fake.eventInitDelay.get()>=300,"Event TCP settle missing")
                        verify(if(sta)fake.session.get()>=2 else fake.session.get()==1,"Session profile")
                        verify(transport.executeReadAll(0x1001).response.ok,"DeviceInfo framing")
                        verify(transport.executeReadAll(0x952B).payload.isEmpty(),"No-data prepare accepted")
                        verify(fake.confirm.get()==0,"No automatic host confirmation")
                        if(sta){transport.pollEvent(1000);verify(fake.pings.get()>0,"STA initial ping")}
                    }finally{transport.close()}
                    fake.assertHealthy()
                }
            }
            phase="cancelled TCP settle"
            FakeNikon().use { fake ->
                val transport=PtpIpTransport("127.0.0.1",fake.port,DiagnosticLog())
                try {
                    val job=launch{runCatching{transport.connect()}}
                    delay(100);job.cancelAndJoin()
                    verify(fake.initRequests.get()==0,"Cancelled settle sent InitCommand")
                    verify(fake.commands.get()==0,"Cancelled settle opened session")
                    verify(!transport.isOpen,"Cancelled transport still open")
                }finally{transport.close()}
                fake.assertHealthy()
            }
            phase="strict handshake failure handling"
            for(which in listOf("init","event","open")) FakeNikon(initRejectReason=if(which=="init")2 else null,
                acceptEvents=which!="event",openCode=if(which=="open")0x201E else 0x2001).use { fake ->
                val transport=PtpIpTransport("127.0.0.1",fake.port,DiagnosticLog())
                try {
                    val failure=runCatching{transport.connect()}.exceptionOrNull()
                    verify(failure is PtpIpException,"Failed handshake reported as success: $which")
                    verify(!transport.isOpen,"Failed channel left usable: $which")
                    verify(fake.prepare.get()==0,"Failure triggered registration")
                    verify(fake.commands.get()==if(which=="open")1 else 0,"Failure retried commands")
                    if(which=="init")verify((failure as PtpIpException).code==2,"InitFail reason lost")
                    if(which=="open")verify((failure as PtpIpException).code==0x201E,"Existing-session code lost")
                }finally{transport.close()}
                fake.assertHealthy()
            }
            phase="shared protocol model fixtures, not hardware"
            for(model in listOf("NIKON Z 30","NIKON Z 50","NIKON Z 50Ⅱ","NIKON Z 6Ⅲ","NIKON D850")) FakeNikon(model=model).use { fake ->
                val driver=NikonPtpDriver(targetContext.applicationContext)
                try {
                    val info=driver.openWifi("127.0.0.1",fake.port,"127.0.0.1").getOrThrow()
                    verify(info.model==NikonConnectionPolicy.model(model),"Model whitelist or generation loss")
                    verify(info.firmwareVersion=="1.00" && info.serial=="TEST-SERIAL","Identity fields")
                    verify(fake.prepare.get()==0,"Model fixture auto-registered")
                }finally{driver.close()}
                fake.assertHealthy()
            }
            phase="phone chunk download, source validation and manual resume"
            FakeNikon().use { fake ->
                val driver=NikonPtpDriver(targetContext.applicationContext)
                val file=File(targetContext.cacheDir,"phone-loopback-test-only.part")
                val meta=File(file.path+".meta")
                try {
                    file.delete();meta.delete()
                    driver.openWifi("127.0.0.1",fake.port,"127.0.0.1").getOrThrow()
                    val token=driver.burstConnectionToken!!
                    val item=CameraStorage.Item(1,1,0x3801,"TEST.JPG",OBJECT_BYTES.toLong(),100,100,"",false)
                    driver.downloadPhoneFile(item,file,token){_,_->}
                    verify(file.length()==OBJECT_BYTES.toLong(),"Chunk file length")
                    verify(file.readBytes().contentEquals(ByteArray(OBJECT_BYTES){(it%251).toByte()}),"Chunk content")
                    verify(fake.maxChunk.get()==4*1024*1024,"4MiB target actually exercised")
                    verify(fake.offsets.toList()==listOf(0L,4194304L,8388608L),"Exact 4MiB offsets and tail")
                    RandomAccessFile(file,"rw").use{it.setLength(PhoneToolsPolicy.CHUNK_BYTES.toLong())}
                    fake.offsets.clear()
                    driver.downloadPhoneFile(item,file,token){_,_->}
                    verify(fake.offsets.firstOrNull()==PhoneToolsPolicy.CHUNK_BYTES.toLong(),"Verified manual resume offset")
                    verify(file.length()==OBJECT_BYTES.toLong(),"Resumed file size")
                    fake.offsets.clear()
                    verify(runCatching{driver.downloadPhoneFile(item.copy(filename="WRONG.JPG"),file,token){_,_->}}.isFailure,"Source identity change accepted")
                    verify(fake.offsets.isEmpty(),"Identity mismatch performed file read")
                    verify(runCatching{driver.downloadPhoneFile(item,file,Any()){_,_->}}.isFailure,"Stale session accepted")
                    file.delete();meta.delete();fake.offsets.clear()
                    val cancelJob=launch {val self=coroutineContext[Job]!!;driver.downloadPhoneFile(item,file,token){done,_->if(done>0)self.cancel()}}
                    cancelJob.join();verify(file.length()<=PhoneToolsPolicy.CHUNK_BYTES,"Cancel continued after current chunk")
                    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
                    try{var state=com.zbox.ai.ui.UiState(usbState=NikonPtpDriver.State.SESSION_OPEN)
                        val work=com.zbox.ai.ui.PhoneWorkbench(targetContext.applicationContext as android.app.Application,scope,driver,{state},{f->state=f(state)},preferencesName="batch38_capacity_test_only")
                        val candidates=(100L..123L).map{item.copy(handle=it,filename="BATCH-$it.JPG")};val commands=fake.commands.get()
                        verify(work.enqueueBatch(candidates,false)==24,"Batch admits 24 unique existing-format targets")
                        verify(work.state.value.tasks.size==24,"Queue capacity exact")
                        verify(work.enqueueBatch(listOf(item.copy(handle=124)),false)==0,"Batch over capacity is rejected")
                        verify(work.state.value.tasks.size==24,"No partial batch addition on capacity failure")
                        verify(work.enqueueBatch(candidates,false)==0,"Active queued duplicates not added")
                        verify(fake.commands.get()==commands,"Enqueue must not perform camera I/O")
                    }finally{scope.cancel()}

                }finally{driver.close();file.delete();meta.delete()}
                fake.assertHealthy()
            }
            phase="read-only parameter rejection fallback"
            for(code in listOf(0x2006,0x201D,0x2005)) {
                FakeNikon(maxAccepted=512*1024,rejectReadCode=code).use { fake ->
                    val driver=NikonPtpDriver(targetContext.applicationContext)
                    val file=File(targetContext.cacheDir,"phone-loopback-reduction-only.part");val meta=File(file.path+".meta")
                    try {
                        file.delete();meta.delete();driver.openWifi("127.0.0.1",fake.port,"127.0.0.1").getOrThrow()
                        val item=CameraStorage.Item(1,1,0x3801,"TEST.JPG",OBJECT_BYTES.toLong(),100,100,"",false)
                        val result=runCatching{driver.downloadPhoneFile(item,file,driver.burstConnectionToken!!){_,_->}}
                        if(code==0x2005){verify(result.isFailure,"Unsupported operation must fail");verify(fake.offsets.size==1,"No unsupported-operation replay")}
                        else {result.getOrThrow();verify(fake.offsets.take(4)==listOf(0L,0L,0L,0L),"Reduction must keep offset unchanged")
                            verify(fake.requests.take(4)==listOf(4194304,2097152,1048576,524288),"Bounded 4/2/1MiB to 512KiB")
                            verify(file.readBytes().contentEquals(ByteArray(OBJECT_BYTES){(it%251).toByte()}),"Reduced read exact bytes")}
                    } finally {driver.close();file.delete();meta.delete()};fake.assertHealthy()
                }
            }
            phase="mock driver identity, capability and no-PIN registration"
            FakeNikon().use { fake ->
                val driver=NikonPtpDriver(targetContext.applicationContext)
                try{
                    val info=driver.openWifi("127.0.0.1",fake.port,"127.0.0.1").getOrThrow()
                    verify(info.model=="NIKON Z 50II","Model generation parse")
                    verify(info.serial=="TEST-SERIAL","Firmware incorrectly used as serial")
                    verify(info.firmwareVersion=="1.00","Firmware not preserved")
                    verify(info.supportedOperations.contains(0x952B),"Operations not preserved")
                    verify(fake.prepare.get()==0,"Connection must not auto-register")
                    val before=fake.commands.get()
                    verify(!driver.transact(driver.opSetPicCtrl,intArrayOf(1),byteArrayOf(0),false).ok,"Unsafe write allowed")
                    verify(!driver.transact(driver.opDelCustomPicCtrl,intArrayOf(1),null,false).ok,"Unsafe delete allowed")
                    verify(fake.commands.get()==before,"Blocked operations reached camera")
                    verify(driver.readNikonPairingCode().getOrThrow().isEmpty(),"No-PIN driver path")
                    val prepared=System.nanoTime()
                    driver.confirmNikonPairingCode().getOrThrow()
                    verify((System.nanoTime()-prepared)/1_000_000>=8200,"Host settle omitted")
                    val staleRequest=runCatching{driver.transact(0x1001,IntArray(0),null,expectData=true)}
                    verify(staleRequest.isSuccess && staleRequest.getOrThrow().responseCode==0x2003,"Post-registration stale request must fail safely as SessionNotOpen")
                    verify(fake.prepare.get()==1 && fake.confirm.get()==1,"Unexpected repeated registration")
                    verify(driver.confirmNikonPairingCode().isFailure,"Repeated confirmation not blocked")
                }finally{driver.close()}
                fake.assertHealthy()
            }
            phase="mock PIN registration cancellation"
            FakeNikon(pinData=ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putInt(6).put("123456".toByteArray()).array()).use { fake ->
                val driver=NikonPtpDriver(targetContext.applicationContext)
                try {
                    driver.openWifi("127.0.0.1",fake.port,"127.0.0.1").getOrThrow()
                    verify(driver.readNikonPairingCode().getOrThrow()=="123456","PIN driver path")
                    val job=launch{driver.confirmNikonPairingCode()}
                    delay(150);job.cancelAndJoin()
                    verify(fake.confirm.get()==0,"Cancelled settle must not confirm")
                }finally{driver.close()}
                fake.assertHealthy()
            }
            phase="mock non-Nikon identity rejected"
            FakeNikon(manufacturer="Canon").use { fake ->
                val driver=NikonPtpDriver(targetContext.applicationContext)
                try {
                    verify(driver.openWifi("127.0.0.1",fake.port,"127.0.0.1").isFailure,"Non-Nikon identity accepted")
                    verify(fake.prepare.get()==0 && fake.confirm.get()==0,"Vendor command to non-Nikon")
                }finally{driver.close()}
                fake.assertHealthy()
            }
            phase="mock unsupported prepare"
            FakeNikon(prepareCode=0x2005,advertiseRegistration=false).use { fake ->
                val driver=NikonPtpDriver(targetContext.applicationContext)
                try{
                    val info=driver.openWifi("127.0.0.1",fake.port,"127.0.0.1").getOrThrow()
                    verify(!info.supportsNikonPinPairing,"Absent operation advertisement")
                    verify(!info.supportsNikonHostRegistration,"Nikon identity alone must not enable host registration")
                    verify(driver.readNikonPairingCode().isFailure,"Unsupported prepare must fail")
                    verify(driver.confirmNikonPairingCode().isFailure,"Confirm after rejected prepare")
                    verify(fake.confirm.get()==0,"Unsupported registration sent confirm")
                }finally{driver.close()}
                fake.assertHealthy()
            }
            phase="client identity UUID normalization and legacy brand-name fallback"
            val idPrefs=targetContext.applicationContext.getSharedPreferences("ptpip_client",android.content.Context.MODE_PRIVATE)
            val origName=idPrefs.getString("initiator_name",null)
            val origCustomHex=idPrefs.getString("initiator_guid_hex",null)
            try {
                FakeNikon(expectedClientName="Z-Cloud AI").use { fake ->
                    val driver=NikonPtpDriver(targetContext.applicationContext)
                    try {
                        driver.savePtpIpClientIdentity("Zbox","af2a3fd3-781c-45f0-9077-1dcf5a77ad53")
                        val saved=driver.ptpIpClientIdentity()
                        verify(saved.name=="Zbox" && saved.guidHex=="AF2A3FD3781C45F090771DCF5A77AD53","UUID hyphen normalization")
                        val info=driver.openWifi("127.0.0.1",fake.port,"127.0.0.1").getOrThrow()
                        verify(info.model=="NIKON Z 50II","Fallback openWifi model")
                        verify(fake.initRequests.get()==2,"Expected one InitCommand rejection then fallback retry")
                        verify(driver.ptpIpClientIdentity().name=="Z-Cloud AI","Accepted fallback client name persisted")
                    } finally { driver.close() }
                    fake.assertHealthy()
                }
            } finally {
                idPrefs.edit().apply {
                    if(origName==null) remove("initiator_name") else putString("initiator_name",origName)
                    if(origCustomHex==null) remove("initiator_guid_hex") else putString("initiator_guid_hex",origCustomHex)
                }.commit()
            }
        }
        result.putString("stream","PASS: $checks compatibility checks: phone chunk bounds/content/source validation/manual resume/cancellation; policy bounds/PIN/model/safe writes; both TCP settle timings and cancellation; strict init/event/existing-session failures; five model fixtures (not hardware); firmware/serial preservation; loopback command+event handshake, STA ping/random session and non-STA legacy profile; no-PIN host settle/confirm and stale post-registration request rejection; actual vendor write/delete blocked; PIN cancellation and non-Nikon identity rejected; absent capability, unsupported and repeated confirmation blocked. No actual camera or cloud operation.\n")
        finish(Activity.RESULT_OK,result)
        }catch(t:Throwable){result.putString("stream","FAIL phase=$phase: ${t.javaClass.simpleName}: ${t.message}\n");finish(Activity.RESULT_CANCELED,result)}
    }
}

private const val OBJECT_BYTES=9*1024*1024+113

private class FakeNikon(private val prepareCode:Int=0x2001,private val pinData:ByteArray=byteArrayOf(),private val manufacturer:String="Nikon",private val advertiseRegistration:Boolean=true,private val model:String="NIKON Z 50Ⅱ",
    private val initRejectReason:Int?=null,private val acceptEvents:Boolean=true,private val openCode:Int=0x2001,private val maxAccepted:Int=4*1024*1024,private val rejectReadCode:Int=0x2006,
    private val expectedClientName:String?=null):Closeable {
    private val server=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
    val port=server.localPort
    val initRequests=AtomicInteger();val commandInitDelay=AtomicInteger();val eventInitDelay=AtomicInteger()
    val requests=java.util.concurrent.CopyOnWriteArrayList<Int>()
    val offsets=java.util.concurrent.CopyOnWriteArrayList<Long>();val maxChunk=AtomicInteger()
    val commands=AtomicInteger();val session=AtomicInteger();val prepare=AtomicInteger();val confirm=AtomicInteger();val pings=AtomicInteger()
    private val failure=AtomicReference<Throwable?>()
    @Volatile private var closed=false
    @Volatile private var command:Socket?=null
    @Volatile private var event:Socket?=null
    private fun u32(a:ByteArray,o:Int)=ByteBuffer.wrap(a,o,4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun payload(vararg v:Int):ByteArray=ByteBuffer.allocate(v.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{v.forEach{putInt(it)}}.array()
    private fun packet(s:Socket,type:Int,data:ByteArray=byteArrayOf()) {s.getOutputStream().apply{write(payload(data.size+8,type));write(data);flush()}}
    private fun read(s:Socket):Pair<Int,ByteArray> {val input=DataInputStream(s.getInputStream());val h=ByteArray(8);input.readFully(h);val len=u32(h,0);require(len in 8..10000);val data=ByteArray(len-8);input.readFully(data);return u32(h,4) to data}
    private fun response(s:Socket,code:Int,tx:Int){packet(s,7,ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(code.toShort()).putInt(tx).array())}
    private fun deviceInfo():ByteArray {
        val b=ByteArrayOutputStream()
        fun u16(n:Int){b.write(n and 255);b.write((n ushr 8) and 255)}
        fun u32(n:Int){b.write(payload(n))}
        fun str(s:String){if(s.isEmpty())b.write(0)else{b.write(s.length+1);b.write((s+"\u0000").toByteArray(Charsets.UTF_16LE))}}
        u16(100);u32(10);u16(100);str("");u16(0)
        val ops=listOf(0x1001,0x1002,0x1003,0x1004)+(if(advertiseRegistration) listOf(0x952B,0x935A) else emptyList());u32(ops.size);ops.forEach{u16(it)}
        repeat(4){u32(0)};str(manufacturer);str(model);str("1.00");str("TEST-SERIAL")
        return b.toByteArray()
    }
    private fun objectInfo():ByteArray {
        val header=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0,1);header.putShort(4,0x3801.toShort());header.putInt(8,OBJECT_BYTES);header.putInt(26,100);header.putInt(30,100)
        fun str(s:String)=byteArrayOf((s.length+1).toByte())+(s+"\u0000").toByteArray(Charsets.UTF_16LE)
        return header.array()+str("TEST.JPG")+byteArrayOf(0)
    }
    private fun sendData(c:Socket,tx:Int,data:ByteArray){packet(c,9,payload(tx,data.size,0));packet(c,12,payload(tx)+data);response(c,0x2001,tx)}
    private val worker=Thread {
        try{
            var acceptedSocket:Socket
            while(true){
                val candidate=server.accept().also{command=it}
                val accepted=System.nanoTime()
                val initPacket=read(candidate)
                check(initPacket.first==1)
                commandInitDelay.set(((System.nanoTime()-accepted)/1_000_000).toInt())
                initRequests.incrementAndGet()
                if(initRejectReason!=null){packet(candidate,5,payload(initRejectReason));return@Thread}
                if(expectedClientName!=null){
                    val actualName=String(initPacket.second,16,initPacket.second.size-20,Charsets.UTF_16LE).trimEnd('\u0000')
                    if(actualName!=expectedClientName){
                        packet(candidate,5,payload(PtpIp.INIT_FAIL_REJECTED))
                        runCatching{candidate.close()}
                        continue
                    }
                }
                acceptedSocket=candidate
                break
            }
            val c=acceptedSocket
            val ack=payload(7)+ByteArray(16)+"MOCK NIKON\u0000".toByteArray(Charsets.UTF_16LE)+payload(0x10000)
            packet(c,2,ack)
            val e=server.accept().also{event=it};val eventAccepted=System.nanoTime();val init=read(e)
            eventInitDelay.set(((System.nanoTime()-eventAccepted)/1_000_000).toInt())
            check(init.first==3 && u32(init.second,0)==7)
            if(!acceptEvents){packet(e,5,payload(2));return@Thread};packet(e,4)
            Thread {try{while(!closed){val p=read(e);if(p.first==13){pings.incrementAndGet();packet(e,14)}}}catch(_:Exception){}}.apply{isDaemon=true;start()}
            while(!closed){val p=read(c);commands.incrementAndGet();check(p.first==6);val op=(p.second[4].toInt() and 255) or ((p.second[5].toInt() and 255) shl 8);val tx=u32(p.second,6)
                when(op){
                    0x1002 -> {session.set(u32(p.second,10));response(c,openCode,tx)}
                    0x1001 -> {val data=deviceInfo();packet(c,9,payload(tx,data.size,0));packet(c,12,payload(tx)+data);response(c,0x2001,tx)}
                    0x1008 -> sendData(c,tx,objectInfo())
                    0x101B -> {val offset=u32(p.second,14).toLong() and 0xffffffffL;val requested=u32(p.second,18);check(requested in 1..PhoneToolsPolicy.CHUNK_BYTES)
                        offsets.add(offset);requests.add(requested)
                        if(requested>maxAccepted){response(c,rejectReadCode,tx);continue}
                        maxChunk.set(maxOf(maxChunk.get(),requested));val length=minOf(requested.toLong(),OBJECT_BYTES.toLong()-offset).toInt()
                        sendData(c,tx,ByteArray(length){((offset+it)%251).toByte()})}
                    0x952B -> {prepare.incrementAndGet();if(pinData.isNotEmpty()){packet(c,9,payload(tx,pinData.size,0));packet(c,12,payload(tx)+pinData)};response(c,prepareCode,tx)}
                    0x935A -> {check(u32(p.second,10)==0x2001);confirm.incrementAndGet();response(c,0x2001,tx)}
                    0x1003 -> response(c,0x2001,tx)
                    else -> response(c,0x2005,tx)
                }
            }
        }catch(e:EOFException){}catch(e:SocketException){}catch(t:Throwable){if(!closed)failure.set(t)}
    }.apply{isDaemon=true;start()}
    fun assertHealthy(){failure.get()?.let{throw it}}
    override fun close(){closed=true;runCatching{command?.close()};runCatching{event?.close()};runCatching{server.close()};worker.join(1000)}
}
