package com.zbox.ai.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.zbox.ai.CameraConnectionService
import kotlinx.coroutines.flow.update
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zbox.ai.ZboxApp
import com.zbox.ai.ai.AiColorGradingRepository
import com.zbox.ai.ai.ImageFeatureExtractor
import com.zbox.ai.ai.LlmProvider
import com.zbox.ai.ai.OpenAiCompatibleModelApi
import com.zbox.ai.ai.ProviderAccount
import com.zbox.ai.core.CameraStorage
import com.zbox.ai.core.CameraAperturePolicy
import com.zbox.ai.core.BuiltInPreset
import com.zbox.ai.core.BuiltInPresets
import com.zbox.ai.core.CubicSplineLut
import com.zbox.ai.core.ImagePipeline
import com.zbox.ai.core.NcpParserAndBuilder
import com.zbox.ai.core.Recipe
import com.zbox.ai.core.RecipeShareCode
import com.zbox.ai.data.SavedPreset
import com.zbox.ai.ptp.HexLog
import com.zbox.ai.ptp.HexLogEntry
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.ptp.Ptp
import com.zbox.ai.ptp.PtpException
import com.zbox.ai.ptpip.HotspotCameraDiscovery
import com.zbox.ai.ptpip.HotspotCameraEndpoint
import com.zbox.ai.ptpip.HotspotConnectionPolicy
import com.zbox.ai.util.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

private const val PTPIP_HEALTH_INTERVAL_MS = 15_000L
private const val PTPIP_HEALTH_TIMEOUT_MS = 12_000

/** 单个槽位的机身状态。 */
data class SlotState(
    val index: Int = 0,               // 1..9
    val ptpSlot: Int = 0,             // 0x00CB..
    val name: String = "--",
    val loaded: Boolean = false,
    val isTemplate: Boolean = false,  // 是否已作为写入模板缓存
    val rawSize: Int = 0,
    /** 机身明确回答「这个槽位没有预设」（AccessDenied / 36B 全零空壳）时为 true。 */
    val isEmpty: Boolean = false,
)

data class UiState(
    val startupUpdate: com.zbox.ai.core.ReleaseUpdate? = null,
    val updateChecking: Boolean = false,
    val compositionOpen: Boolean = false,
    val compositionBusy: Boolean = false,
    val compositionSnapshot: Bitmap? = null,
    val compositionAdvice: com.zbox.ai.core.CompositionAdvice? = null,
    val compositionError: String = "",
    val selectedTab: Int = 0,
    val referenceBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val showOriginal: Boolean = false,
    val recipe: Recipe = Recipe(),
    val features: com.zbox.ai.ai.ImageFeatures = com.zbox.ai.ai.ImageFeatures.EMPTY,
    val analyzing: Boolean = false,
    val analyzingMsg: String = "",
    val lastAnalysis: String = "",
    val userHint: String = "",

    val usbState: NikonPtpDriver.State = NikonPtpDriver.State.DISCONNECTED,
    val deviceInfo: NikonPtpDriver.DeviceInfo = NikonPtpDriver.DeviceInfo(),
    val hotspotCameras: List<HotspotCameraEndpoint> = emptyList(),
    val hotspotScanBusy: Boolean = false,
    val wifiConnectBusy: Boolean = false,
    val wifiInitRejected: Boolean = false,
    val hotspotScanMessage: String = "",
    val clientIdentityName: String = "Zbox",
    val clientIdentityGuidHex: String = "",
    val slots: List<SlotState> = (1..9).map { SlotState(it, Ptp.CUSTOM_SLOTS[it - 1]) },
    val targetSlotIndex: Int = 1,
    val templatePayload: ByteArray? = null,
    val writing: Boolean = false,
    val writeMsg: String = "",
    val pairingCode: String? = null,
    val pairingBusy: Boolean = false,
    val pairingStatus: String = "",

    /** 实时取景（0x9201 开 / 0x9203 取帧 / 0x9202 关） */
    val liveViewActive: Boolean = false,
    val liveViewBitmap: Bitmap? = null,
    val liveViewFrames: Int = 0,
    val touchFocusBusy:Boolean=false,
    val touchFocusRequest:Long=0,
    val touchFocusX:Float=-1f,
    val touchFocusY:Float=-1f,
    val touchFocusMoved:Boolean=false,
    val touchFocusMessage:String="",
    val pendingFocusModePoint:Pair<Float,Float>?=null,
    val cameraMenuBusy:Boolean=false,
    val cameraMenuEntries:List<CameraMenuEntry> = com.zbox.ai.core.CameraIMenuPolicy.definitions.map{CameraMenuEntry(it)},
    val cameraMenuMessage:String="",
    val liveViewError: String = "",

    val accounts: List<ProviderAccount> = emptyList(),
    val providerModelOptions: Map<String, List<String>> = emptyMap(),
    val providerModelFetchStatus: Map<String, String> = emptyMap(),
    val providerModelTestStatus: Map<String, String> = emptyMap(),
    val providerModelBusy: Set<String> = emptySet(),
    val activeProviderId: String = LlmProvider.GLM_FLASH.id,

    val presets: List<SavedPreset> = emptyList(),
    val toast: String = "",

    // ---------------- 监看 / 拍摄 ----------------
    val liveViewReady: Boolean = false,
    val capturing: Boolean = false,
    val burstActive: Boolean = false,
    val captureMsg: String = "",
    /** 刚拍下的一张（从相机卡回传的 JPEG 或 NEF 内嵌 JPEG 解码结果），用于「拍完即看」。 */
    val lastCapture: Bitmap? = null,
    val lastCaptureHandle: Long = 0L,
    val movieRecording: Boolean = false,
    val movieBusy: Boolean = false,
    val liveViewAutoFocus: Boolean = false,

    // ---------------- 拍摄参数（0x1015 / 0x1016） ----------------
    val iso: Int = 100,
    val aperture: Float = 5.6f,
    val shutterSeconds: Float = 1f / 125f,
    val exposureComp: Float = 0f,
    val exposureSyncing: Boolean = false,
    val usbConnectBusy: Boolean = false,
    val usbConnectionCandidates: List<UsbDevice> = emptyList(),
    val exposureStatus: String = "修改参数后自动同步到机身",
    val exposureProgram: Int = 3,   // 1=M 2=P 3=A 4=S
    val apertureValues: List<Float> = emptyList(),
    val apertureRangeReady: Boolean = false,
    val apertureWritable: Boolean = false,
    val exposureCompWritable: Boolean = false,
    val whiteBalance: Int = 0x0002,
    val picCtrlItem: Int = 1,       // 1=标准 2=自然 3=鲜艳 4=单色 5=人像 6=风景 7=平直 8=自动
    val paramsLoaded: Boolean = false,
    val batteryPercent: Int = -1,

    // ---------------- 相册（照片管理） ----------------
    val galleryVolumes: List<CameraStorage.Volume> = emptyList(),
    val galleryItems: List<CameraStorage.Item> = emptyList(),
    val galleryLoading: Boolean = false,
    val galleryProgress: Int = 0,
    val galleryFilter: Int = 0,     // 默认全部；1=照片预览 2=视频 4=收藏 5=待选
    val thumbnails: Map<Long, ByteArray> = emptyMap(),
    val galleryPreviewBitmap: Bitmap? = null,
    val galleryPreviewHandle: Long = 0L,
    val galleryPreviewBusy: Boolean = false,
    val galleryPreviewError: String = "",
    val downloadingHandle: Long = 0L,
    val downloadMsg: String = "",
    /** 每批显示条目数，不是浏览总数上限。 */
    val galleryLimit: Int = 18,
    val galleryHasMore: Boolean = false,
    val galleryTotal: Int = 0,

    // ---------------- 内置预设库 ----------------
    /** null = 全部；否则是 BuiltInPresets.CAT_* 之一 */
    val builtinCategory: String? = null,
    val builtinQuery: String = "",
    /** 内置库界面当前预览的那一套（点卡片先看曲线和参数，再决定刷不刷） */
    val builtinPreviewId: String? = null,

    // ---------------- 设置可见性 ----------------
    /** AI 供应商配置区是否展开。配置完点「收起」，Key 就藏起来，避免被人看到。 */
    val aiPanelExpanded: Boolean = false,

    // ---------------- 配方快传 ----------------
    val shareCode: String = "",
    val shareCodeInput: String = "",

    /**
     * 优化校准操作码的参数形态。
     * false = 两参数 `(slot, 0)`，标准写法，Z30/EXPEED 6 正常路径；
     * true  = 单参数 `(slot)`，仅作兼容退化，个别老固件只认一个参数。
     */
    val useAltOpcode: Boolean = false,

    /** 是否正在跑写入机制矩阵探测。 */
    val probingWrite: Boolean = false,
    /** 探测过程的实时进度行（最多保留最近几条）。 */
    val probeLines: List<String> = emptyList(),
    /** 已固化的写入配方描述；空 = 还没探测成功。 */
    val writeModeLabel: String = "",

    /**
     * 机身自报「只读」的属性码集合（来自 0x1014 描述符第 5 字节）。
     *
     * 真机实测 Z30：`0x500E` 曝光程序、`0x500D` 标准快门、`0xD100` 尼康快门
     * 三条全是 `GetSet=只读`，只有 ISO / 光圈 / 曝光补偿 / 白平衡 / 优化校准项可写。
     * 只读的属性发 0x1016 必然回 `0x200F AccessDenied`，重试一万次也不会变，
     * 所以这里缓存下来，下发时直接跳过，UI 也按它禁掉滑杆。
     */
    val readOnlyProps: Set<Int> = emptySet(),

    // ---------------- 设置 ----------------
    /** 常规设置（DataStore 持久化）。 */
    val settings: com.zbox.ai.data.AppSettings = com.zbox.ai.data.AppSettings(),
    /** USB 调试台是否以覆盖层打开（入口挪到设置里了，主界面不再占一个 tab）。 */
    val consoleOpen: Boolean = false,
    /** 设置页是否以覆盖层打开（入口：右上角齿轮）。 */
    val settingsOpen: Boolean = false,
) {
    val activeAccount: ProviderAccount
        get() = accounts.firstOrNull { it.providerId == activeProviderId }
            ?: ProviderAccount(providerId = activeProviderId)

    val isConnected: Boolean
        get() = usbState == NikonPtpDriver.State.SESSION_OPEN && !wifiConnectBusy && !usbConnectBusy && !pairingBusy
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private var compositionJob: Job? = null
    private var compositionEpoch = 0L
    private val zapp = app as ZboxApp
    private val driver = zapp.driver
    private val nativeBurst = com.zbox.ai.ptp.NativeBurstController(driver)
    private val burstRequest = com.zbox.ai.ptp.NativeBurstRequest()
    private var nativeBurstJob: Job? = null
    private val store = zapp.store

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    internal val poses=PoseRepository(getApplication(),viewModelScope)
    internal val phone=PhoneWorkbench(app=getApplication(),scope=viewModelScope,driver=driver,ui={_ui.value},update={f->_ui.update(f)},workPending={pendingExposure.isNotEmpty() || exposureWriteJob!=null || galleryLoadJob?.isActive==true || previewJob?.isActive==true})

    val hexLog: StateFlow<List<HexLogEntry>> = HexLog.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var previewJob: Job? = null
    @Volatile private var galleryLoadJob: Job? = null
    @Volatile private var thumbnailJob: Job? = null
    @Volatile private var connectionMonitorJob: Job? = null
    @Volatile private var userRequestedDisconnect: Boolean = false
    private var galleryIndex: List<CameraStorage.Item> = emptyList()
    private var galleryIndexComplete = false
    @Volatile private var thumbnailDemand: List<CameraStorage.Item> = emptyList()
    private var templateCache = mutableMapOf<Int, ByteArray>()

    /** 刚尝试拍摄后相册可能过时：当前就在相册时立即刷新，否则等下次进入时刷新。 */
    private var lastCaptureDirty = false

    /** 默认目标槽位只在首次读到设置时应用一次，避免把用户临时改的槽位覆盖回去。 */
    private data class ExposureEdit(val code: Int, val value: Double, val revision: Long)
    private val pendingExposure = LinkedHashMap<Int, ExposureEdit>()
    private val exposureVersions = HashMap<Int, Long>()
    private var exposureRevision = 0L
    private var exposureSession = 0L
    private var burstProbeSession = -1L
    private var exposureWriteJob: Job? = null
    private var apertureCapabilityJob: Job? = null

    private fun resetExposureSync() {
        exposureSession++
        exposureRevision++
        pendingExposure.clear()
        exposureVersions.clear()
        exposureWriteJob?.cancel()
        exposureWriteJob = null
        apertureCapabilityJob?.cancel()
        apertureCapabilityJob = null
    }

    /** Called only by the first root composition of this process, never on foreground return. */
    fun checkStartupUpdate(canShowNow: () -> Boolean) {
        if (!com.zbox.ai.data.GitHubReleaseUpdater.startupGate.claim()) return
        val started = android.os.SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                val candidate = com.zbox.ai.data.GitHubReleaseUpdater.latest(com.zbox.ai.BuildConfig.VERSION_NAME) ?: return@launch
                val state = _ui.value
                val busy = state.pairingBusy || state.capturing || state.movieRecording || state.movieBusy || state.writing || state.exposureSyncing ||
                    state.galleryLoading || state.galleryPreviewBusy || state.wifiConnectBusy || state.usbConnectBusy ||
                    state.settingsOpen || state.consoleOpen || state.compositionOpen || state.selectedTab != 0
                if (com.zbox.ai.core.ReleaseUpdatePolicy.startupWindowAllows(
                        android.os.SystemClock.elapsedRealtime() - started, canShowNow(), busy)) {
                    _ui.update { it.copy(startupUpdate = candidate) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { HexLog.info("启动更新", "检查不可用，本次静默跳过") }
        }
    }

    fun dismissStartupUpdate() { _ui.update { it.copy(startupUpdate = null) } }

    /** Explicit About-page check. It only offers the existing confirmation dialog; it never installs. */
    fun checkForUpdatesManually() {
        if (_ui.value.updateChecking) return
        _ui.update { it.copy(updateChecking = true, toast = "") }
        viewModelScope.launch {
            try {
                val candidate = com.zbox.ai.data.GitHubReleaseUpdater.latest(com.zbox.ai.BuildConfig.VERSION_NAME)
                if (candidate != null) _ui.update { it.copy(updateChecking = false, startupUpdate = candidate) }
                else _ui.update { it.copy(updateChecking = false, toast = "没有发现可安装的新版本（当前 v${com.zbox.ai.BuildConfig.VERSION_NAME}）") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _ui.update { it.copy(updateChecking = false, toast = "检查更新失败：${e.message?.take(90) ?: "请稍后重试"}") }
            }
        }
    }

    fun openStartupUpdate() {
        val update = _ui.value.startupUpdate ?: return
        dismissStartupUpdate()
        if (!com.zbox.ai.core.ReleaseUpdatePolicy.trustedApk(update.apkUrl)) return
        runCatching {
            getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { _ui.update { it.copy(toast = "无法打开浏览器，请到 GitHub Release 下载") } }
    }

    private var defaultSlotApplied = false

    init {
        runCatching { driver.ptpIpClientIdentity() }.getOrNull()?.let { id ->
            _ui.update { it.copy(clientIdentityName = id.name, clientIdentityGuidHex = id.guidHex) }
        }
        // 挂上驱动状态
        viewModelScope.launch {
            driver.state.collect { st ->
                val disconnected = st == NikonPtpDriver.State.DISCONNECTED || st == NikonPtpDriver.State.ERROR
                val current = _ui.value
                val unexpectedDrop = disconnected && current.isConnected && !userRequestedDisconnect
                if (disconnected) {
                    stopNativeBurst(resumeLiveView = false)
                    phone.stopSession()
                    resetExposureSync()
                    getApplication<Application>().stopService(Intent(getApplication(), CameraConnectionService::class.java))
                    galleryIndex = emptyList()
                    galleryIndexComplete = false
                    thumbnailDemand = emptyList()
                    galleryLoadJob?.cancel()
                    thumbnailJob?.cancel()
                    previewJob?.cancel()
                    val disconnectedLiveViewJob = liveViewJob
                    liveViewJob = null
                    disconnectedLiveViewJob?.cancel()
                    templateCache.clear()
                }
                _ui.value = if (disconnected) {
                    current.copy(
                        usbState = st,
                        deviceInfo = NikonPtpDriver.DeviceInfo(),
                        slots = (1..9).map { SlotState(it, Ptp.CUSTOM_SLOTS[it - 1]) },
                        templatePayload = null,
                        liveViewActive = false,
                        liveViewError = "",
                        liveViewBitmap = null,
                        liveViewReady = false,
                        liveViewFrames = 0,
                        capturing = false,
                        burstActive = false,
                        galleryVolumes = emptyList(),
                        galleryItems = emptyList(),
                        galleryHasMore = false,
                        galleryTotal = 0,
                        thumbnails = emptyMap(),
                        galleryLoading = false,
                        galleryPreviewBusy = false,
                        downloadingHandle = 0L,
                        movieRecording = false,
                        exposureSyncing = false,
                        exposureStatus = "连接后修改参数将自动同步",
                        apertureValues = emptyList(),
                        apertureRangeReady = false,
                        apertureWritable = false,
                        exposureCompWritable = false,
                        paramsLoaded = false,
                        pairingBusy = false,
                        pairingCode = null,
                        pairingStatus = "",
                        batteryPercent = -1,
                        writeMsg = if (unexpectedDrop) "相机连接已断开，请重新扫描连接" else current.writeMsg,
                        toast = if (unexpectedDrop) "相机连接已断开" else current.toast,
                    )
                } else {
                    current.copy(
                        usbState = st,
                        liveViewError = if (st == NikonPtpDriver.State.SESSION_OPEN) "" else current.liveViewError,
                    )
                }
                if (st == NikonPtpDriver.State.SESSION_OPEN) {
                    refreshSlots()
                    if (driver.isPtpIpSession) {
                        startPtpIpConnectionMonitor()
                        runCatching {
                            ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), CameraConnectionService::class.java))
                        }.onFailure { HexLog.warn("后台连接保活", "前台服务启动失败：${it.message}") }
                    }
                } else if (disconnected) {
                    stopPtpIpConnectionMonitor()
                }
            }
        }
        viewModelScope.launch {
            driver.info.collect { info -> _ui.value = _ui.value.copy(deviceInfo = info) }
        }
        viewModelScope.launch {
            store.accounts.collect { list -> _ui.value = _ui.value.copy(accounts = list) }
        }
        viewModelScope.launch {
            store.activeProviderId.collect { id -> _ui.value = _ui.value.copy(activeProviderId = id) }
        }
        viewModelScope.launch {
            store.presets.collect { list -> _ui.value = _ui.value.copy(presets = list) }
        }
        viewModelScope.launch {
            store.settings.collect { s ->
                // galleryLimit 在 UiState 里也留一份，GalleryScreen 直接读它，避免改一堆调用点
                _ui.value = _ui.value.copy(settings = s, galleryLimit = s.galleryLimit)
                // 默认目标槽位只在第一次拿到设置时应用一次，
                // 之后用户在界面上临时改的槽位不能被设置流覆盖回去
                if (!defaultSlotApplied) {
                    defaultSlotApplied = true
                    if (s.defaultTargetSlot in 1..9 && _ui.value.targetSlotIndex != s.defaultTargetSlot) {
                        _ui.value = _ui.value.copy(targetSlotIndex = s.defaultTargetSlot)
                    }
                }
                // 设置里的两项直接作用在全局日志总线上，改完立刻生效
                HexLog.hexDumpEnabled = s.hexInLog
                HexLog.fileLoggingEnabled = s.logToFile
                if (!s.logToFile) HexLog.stopFileLogging() else HexLog.startFileLogging(getApplication())
            }
        }
        // 首次进入：没有参考图就用内置样张，保证预览区立刻有东西
        viewModelScope.launch(Dispatchers.Default) {
            val sample = ImagePipeline.builtinSample()
            val feats = ImageFeatureExtractor.extract(sample)
            _ui.value = _ui.value.copy(referenceBitmap = sample, features = feats)
            renderPreview()
        }
    }

    // ---------------- Tab ----------------

    fun selectTab(i: Int) {
        if (i != 0) stopNativeBurst(resumeLiveView = false)
        _ui.value = _ui.value.copy(selectedTab = i)
        // 进相册页：还没读过、或者刚拍过新照片 → 拉一次，省掉用户多点一下
        if (i == 2 && driver.isOpen && !_ui.value.galleryLoading) {
            if (_ui.value.galleryItems.isEmpty() || lastCaptureDirty) {
                refreshGallery()
            }
        }
    }

    // ---------------- USB ----------------

    fun nikonDevices(): List<UsbDevice> = driver.findNikonDevices()

    /**
     * 主动扫描一次 USB 并**写进日志**。
     *
     * 为什么单独开一个方法：`nikonDevices()` 会被 Compose 在重组里反复调用，
     * 在那里写日志会刷屏；而真机排障时「这一轮到底枚举到了什么」恰恰是最关键的一条证据——
     * 用户拔线接相机跑一轮，回来只看日志文件就知道是没枚举到，还是枚举到了但握手失败。
     */
    fun scanUsbDevices(): List<UsbDevice> {
        val all = runCatching { usbManager().deviceList.values.toList() }.getOrDefault(emptyList())
        val nikon = driver.findNikonDevices()
        val detail = if (all.isEmpty()) {
            "0 个（OTG 主机未建立：无外设）"
        } else {
            all.joinToString("; ") {
                "%04X:%04X %s".format(it.vendorId, it.productId, it.productName ?: "?")
            }
        }
        HexLog.info(
            "USB 扫描",
            "共 ${all.size} 个外设 / Nikon ${nikon.size} 个 → $detail",
        )
        return nikon
    }

    /** One user action, deterministic USB priority; never silently fall through a USB permission denial. */
    fun connectAutomatically(requestPermission: (UsbDevice) -> Unit) {
        if (driver.isOpen || _ui.value.isConnected || _ui.value.usbConnectBusy ||
            _ui.value.wifiConnectBusy || _ui.value.hotspotScanBusy) return
        cancelHotspotScan()
        val devices = scanUsbDevices()
        _ui.update { it.copy(usbConnectionCandidates = emptyList()) }
        when (com.zbox.ai.core.MonitorPolicy.connectionRoute(devices.size)) {
            com.zbox.ai.core.MonitorPolicy.ConnectionRoute.HOTSPOT -> scanPhoneHotspot()
            com.zbox.ai.core.MonitorPolicy.ConnectionRoute.USB_SINGLE -> {
                val device = devices.single()
                if (hasPermission(device)) connectDevice(device) else {
                    _ui.update { it.copy(hotspotScanMessage = "已发现有线相机，等待 USB 授权…") }
                    requestPermission(device)
                }
            }
            com.zbox.ai.core.MonitorPolicy.ConnectionRoute.USB_CHOOSE -> _ui.update {
                it.copy(usbConnectionCandidates = devices, hotspotScanMessage = "发现多台有线相机，请选择一台。")
            }
        }
    }

    fun onUsbPermissionDenied() {
        _ui.update { it.copy(hotspotScanMessage = "未获得 USB 授权。可再次点击连接授权，或拔除数据线后使用热点。") }
    }

    fun hasPermission(dev: UsbDevice): Boolean = driver.hasPermission(dev)

    fun usbManager(): UsbManager =
        getApplication<Application>().getSystemService(android.content.Context.USB_SERVICE) as UsbManager

    private var pairingJob:Job?=null
    private var hotspotScanJob: Job? = null
    private var wifiConnectJob: Job? = null
    private var hotspotScanGeneration = 0L

    private fun cancelHotspotScan() {
        hotspotScanGeneration++
        hotspotScanJob?.cancel()
        hotspotScanJob = null
        _ui.update { it.copy(hotspotScanBusy = false, hotspotCameras = emptyList(), hotspotScanMessage = "") }
    }

    /** One explicit scan: auto-connect only after the complete scan finds exactly one camera. */
    fun scanPhoneHotspot() {
        if (_ui.value.usbConnectBusy || hotspotScanJob?.isActive == true || _ui.value.hotspotScanBusy || _ui.value.wifiConnectBusy || driver.isOpen || _ui.value.isConnected) return
        val generation = ++hotspotScanGeneration
        _ui.update { it.copy(hotspotScanBusy = true, hotspotCameras = emptyList(), hotspotScanMessage = "正在扫描相机…") }
        hotspotScanJob = viewModelScope.launch {
            try {
                val decision = withContext(Dispatchers.IO) { HotspotConnectionPolicy.decide(HotspotCameraDiscovery.discover(getApplication())) }
                if (generation != hotspotScanGeneration || driver.isOpen || _ui.value.isConnected || _ui.value.wifiConnectBusy) return@launch
                when (decision.action) {
                    HotspotConnectionPolicy.Action.NONE -> _ui.update { it.copy(hotspotScanMessage = "未找到相机，请确认相机已加入手机热点并启用计算机连接模式，再试一次。") }
                    HotspotConnectionPolicy.Action.AUTO_CONNECT -> {
                        val camera = decision.candidates.single()
                        _ui.update { it.copy(hotspotScanMessage = "发现一台相机，正在自动连接…") }
                        HexLog.info("热点扫描并连接", "发现 1 台，自动连接 ${camera.cameraAddress} via ${camera.phoneAddress} (${camera.interfaceName})")
                        connectPhoneHotspotCamera(camera.cameraAddress, camera.phoneAddress)
                    }
                    HotspotConnectionPolicy.Action.CHOOSE -> {
                        _ui.update { it.copy(hotspotCameras = decision.candidates, hotspotScanMessage = "发现 ${decision.candidates.size} 台相机，请选择要连接的一台。") }
                        HexLog.info("热点扫描并连接", "发现 ${decision.candidates.size} 台，等待用户选择，不自动连接")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == hotspotScanGeneration) _ui.update { it.copy(hotspotScanMessage = "扫描失败：${failure.message ?: "请稍后重试"}") }
            } finally {
                if (generation == hotspotScanGeneration) {
                    _ui.update { it.copy(hotspotScanBusy = false) }
                    hotspotScanJob = null
                }
            }
        }
    }

    fun connectPhoneHotspotCamera(cameraIp: String, phoneIp: String) {
        connectWifi(cameraIp, 15740, phoneIp)
    }

    fun ptpIpClientIdentity(): NikonPtpDriver.PtpIpClientIdentity = driver.ptpIpClientIdentity()

    fun savePtpIpClientIdentity(name: String, guidHex: String): Result<Unit> = runCatching {
        driver.savePtpIpClientIdentity(name, guidHex)
        clearAllAutoPairingSubmitted()
        val updated = driver.ptpIpClientIdentity()
        _ui.update {
            it.copy(
                clientIdentityName = updated.name,
                clientIdentityGuidHex = updated.guidHex,
                wifiInitRejected = false,
                hotspotScanMessage = "客户端身份已更新（名称：${updated.name}），可点击连接重试。",
                toast = "客户端身份已保存：${updated.name}",
            )
        }
    }

    fun resetAutoPairingHistory() {
        clearAllAutoPairingSubmitted()
        _ui.update {
            it.copy(
                pairingStatus = "已重置自动配对记录；相机进入配对等待画面后点「连接」即可自动配对一次。",
                toast = "已重置自动配对记录",
            )
        }
    }

    fun readNikonPairingCode() {
        val st = _ui.value
        if (st.pairingBusy) return
        val blockedMessage = when {
            phone.cameraBusy -> "手机端相机任务正忙，完成后再准备 PIN。"
            st.downloadingHandle != 0L || st.capturing || st.movieBusy || st.movieRecording || st.writing || st.exposureSyncing ->
                "相机当前正忙（拍摄、传输或写入），请等操作完成后再准备 PIN。"
            st.liveViewActive -> "请先停止取景，再准备主机注册 PIN。"
            else -> null
        }
        if (blockedMessage != null) {
            _ui.update { it.copy(pairingStatus = blockedMessage) }
            return
        }
        pairingJob = viewModelScope.launch {
            _ui.update { it.copy(pairingBusy = true, pairingCode = null, pairingStatus = "正在准备主机注册…") }
            val result = driver.readNikonPairingCode()
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@launch
            if (result.isSuccess) {
                val code = result.getOrThrow()
                _ui.update { it.copy(
                    pairingBusy = false,
                    pairingCode = code,
                    pairingStatus = if (code.isEmpty()) "准备成功，无需输入 PIN。请在机身批准后手动重新连接。" else "已读取 PIN；请核对并手动输入后点确认注册。确认后需手动重新连接。"
                ) }
            } else {
                val error = result.exceptionOrNull()?.message ?: "未知错误"
                _ui.update { it.copy(
                    pairingBusy = false,
                    pairingCode = null,
                    pairingStatus = "注册准备失败：$error。请到 设置 → 高级 → Wi-Fi 主机配对 手动处理。"
                ) }
            }
        }
    }

    fun confirmNikonPairingCode(enteredCode: String) {
        val expected = _ui.value.pairingCode
        if (phone.cameraBusy || _ui.value.pairingBusy)return
        if (expected == null) {
            _ui.value = _ui.value.copy(pairingStatus = "请先点击准备主机注册")
            return
        }
        if (expected.isNotEmpty() && enteredCode.filter(Char::isDigit) != expected) {
            _ui.value = _ui.value.copy(pairingStatus = "输入数字与相机返回码不一致；未发送配对确认")
            return
        }
        pairingJob=viewModelScope.launch { submitNikonPairingCode() }
    }

    private suspend fun submitNikonPairingCode() {
        _ui.update { it.copy(pairingBusy = true, pairingStatus = "正在提交主机注册确认…") }
        val confirmation = driver.confirmNikonPairingCode()
        if (!kotlinx.coroutines.currentCoroutineContext().isActive)return
        if (confirmation.isFailure) {
            _ui.value = _ui.value.copy(pairingBusy = false, pairingStatus = "配对确认失败：${confirmation.exceptionOrNull()?.message}。请到 设置 → 高级 → Wi-Fi 主机配对 手动处理。")
            return
        }
        _ui.update { it.copy(
            pairingBusy = false,
            pairingCode = null,
            pairingStatus = "主机注册确认已发送。若相机要求批准，请在机身确认后手动重新连接；应用不会自动重连或再次注册。",
            usbState = NikonPtpDriver.State.DISCONNECTED,
            deviceInfo = NikonPtpDriver.DeviceInfo(),
            liveViewActive = false,
            liveViewBitmap = null,
        ) }
    }

    private fun autoPairingEndpointKey(host: String, port: Int): String = "${host.trim().lowercase()}:$port"

    private fun autoPairingWasSubmitted(endpointKey: String): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences(
            "ptpip_auto_pairing", android.content.Context.MODE_PRIVATE
        )
        return endpointKey in prefs.getStringSet("submitted_endpoints", emptySet()).orEmpty()
    }

    private fun markAutoPairingSubmitted(endpointKey: String) {
        val prefs = getApplication<Application>().getSharedPreferences(
            "ptpip_auto_pairing", android.content.Context.MODE_PRIVATE
        )
        val submitted = prefs.getStringSet("submitted_endpoints", emptySet()).orEmpty().toMutableSet()
        submitted += endpointKey
        prefs.edit().putStringSet("submitted_endpoints", submitted).apply()
    }

    private fun clearAutoPairingSubmitted(endpointKey: String) {
        val prefs = getApplication<Application>().getSharedPreferences(
            "ptpip_auto_pairing", android.content.Context.MODE_PRIVATE
        )
        val submitted = prefs.getStringSet("submitted_endpoints", emptySet()).orEmpty().toMutableSet()
        if (submitted.remove(endpointKey)) {
            prefs.edit().putStringSet("submitted_endpoints", submitted).apply()
        }
    }

    private fun clearAllAutoPairingSubmitted() {
        getApplication<Application>()
            .getSharedPreferences("ptpip_auto_pairing", android.content.Context.MODE_PRIVATE)
            .edit()
            .remove("submitted_endpoints")
            .apply()
    }

    /** Only runs after the user taps Connect and PTP/IP is already open. One valid PIN, one submit, one reconnect. */
    private suspend fun autoPairAndReconnectOnce(host: String, port: Int, bindLocalAddress: String?) {
        val endpointKey = autoPairingEndpointKey(host, port)
        if (autoPairingWasSubmitted(endpointKey)) {
            _ui.update { it.copy(
                pairingBusy = false,
                pairingCode = null,
                pairingStatus = "此相机地址此前已提交过自动注册；为避免重复提交 PIN，本次不再自动注册。可正常连接，若需重新配对请到 设置 → 高级 手动处理。"
            ) }
            return
        }
        _ui.update { it.copy(pairingBusy = true, pairingCode = null, pairingStatus = "正在检查相机是否返回有效 PIN…") }
        val read = driver.readNikonPairingCode()
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return
        if (read.isFailure) {
            _ui.update { it.copy(
                pairingBusy = false,
                pairingStatus = "连接已建立，但读取自动配对 PIN 失败：${read.exceptionOrNull()?.message}。未提交注册，可到 设置 → 高级 手动处理。"
            ) }
            return
        }
        val pin = read.getOrThrow()
        if (pin.isBlank()) {
            _ui.update { it.copy(
                pairingBusy = false,
                pairingCode = null,
                pairingStatus = "相机未返回有效 PIN，未自动提交注册。若机身要求批准，请在机身确认后手动连接。"
            ) }
            return
        }

        // The PIN is used only in memory; never display or log it. Automatic submission is authorized
        // by the user's tap on Connect, and is limited to this single successful PIN read.
        _ui.update { it.copy(pairingBusy = true, pairingCode = null, pairingStatus = "已读取有效 PIN，正在提交一次主机注册确认…") }
        // Persist the one-shot latch before sending, so a failed response or app restart cannot loop registration.
        markAutoPairingSubmitted(endpointKey)
        val confirmation = driver.confirmNikonPairingCode()
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return
        if (confirmation.isFailure) {
            _ui.update { it.copy(
                pairingBusy = false,
                pairingStatus = "自动配对确认失败：${confirmation.exceptionOrNull()?.message}。未自动重试注册，可到 设置 → 高级 手动处理。"
            ) }
            return
        }

        _ui.update { it.copy(
            pairingBusy = true,
            pairingCode = null,
            pairingStatus = "注册确认已发送，正在尝试一次重连…",
            usbState = NikonPtpDriver.State.DISCONNECTED,
            deviceInfo = NikonPtpDriver.DeviceInfo(),
            liveViewActive = false,
            liveViewBitmap = null,
        ) }
        delay(900L)
        if (!kotlinx.coroutines.currentCoroutineContext().isActive || userRequestedDisconnect) return
        val reconnect = driver.openWifi(host, port, bindLocalAddress)
        if (reconnect.isSuccess) {
            val info = reconnect.getOrThrow()
            _ui.update { it.copy(
                pairingBusy = false,
                pairingCode = null,
                pairingStatus = "自动配对并重连成功；本次只提交一次 PIN。",
                writeMsg = "Wi-Fi 已连接 ${info.model.ifBlank { "Nikon" }}",
                toast = "自动配对并重连成功",
            ) }
            driver.probeOpcodeProfile()
            _ui.update { it.copy(useAltOpcode = driver.useAltOpcodeProfile) }
            refreshSlots()
            loadShootingParams()
        } else {
            val error = reconnect.exceptionOrNull()?.message ?: "相机暂未接受连接"
            _ui.update { it.copy(
                pairingBusy = false,
                pairingStatus = "PIN 已提交，但一次自动重连失败：$error。未重复注册；请检查机身是否已批准，然后手动点连接。",
                toast = "自动配对已提交，重连失败",
            ) }
        }
    }

    fun connectWifi(host: String, port: Int = 15740, bindLocalAddress: String? = null) {
        if (_ui.value.usbConnectBusy || _ui.value.wifiConnectBusy || wifiConnectJob?.isActive == true || driver.isOpen || _ui.value.isConnected) return
        _ui.update { it.copy(wifiConnectBusy = true, wifiInitRejected = false) }
        wifiConnectJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val self = kotlinx.coroutines.currentCoroutineContext()[Job]
            try {
            _ui.value = _ui.value.copy(writeMsg = "正在建立 PTP/IP 会话…", pairingCode = null, pairingBusy = false, pairingStatus = "")
            val r = driver.openWifi(host, port, bindLocalAddress)
            if (r.isSuccess) {
                val info = r.getOrThrow()
                val activeIdentity = runCatching { driver.ptpIpClientIdentity() }.getOrNull()
                _ui.value = _ui.value.copy(
                    wifiInitRejected = false,
                    clientIdentityName = activeIdentity?.name ?: _ui.value.clientIdentityName,
                    clientIdentityGuidHex = activeIdentity?.guidHex ?: _ui.value.clientIdentityGuidHex,
                    writeMsg = "Wi-Fi 已连接 ${info.model.ifBlank { "Nikon" }}",
                    toast = "PTP/IP 会话已建立",
                )
                driver.probeOpcodeProfile()
                _ui.value = _ui.value.copy(useAltOpcode = driver.useAltOpcodeProfile)
                refreshSlots()
                loadShootingParams()
                if (info.supportsNikonHostRegistration) autoPairAndReconnectOnce(host, port, bindLocalAddress)
            } else {
                val t = r.exceptionOrNull()
                val initRejected = (t as? com.zbox.ai.ptpip.PtpIpException)?.let {
                    it.stage == "InitCommand" && it.code == com.zbox.ai.ptpip.PtpIp.INIT_FAIL_REJECTED
                } == true
                if (initRejected) {
                    clearAutoPairingSubmitted(autoPairingEndpointKey(host, port))
                }
                _ui.update {
                    it.copy(
                        wifiInitRejected = initRejected,
                        pairingCode = null,
                        pairingBusy = false,
                        writeMsg = "Wi-Fi 连接失败：${t?.message}",
                        hotspotScanMessage = "连接失败：${t?.message ?: "请重新扫描"}",
                        toast = "Wi-Fi 连接失败：${t?.message}",
                    )
                }
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _ui.update { it.copy(hotspotScanMessage = "连接失败：${failure.message}", toast = "连接失败，请重新扫描") }
            } finally {
                if (wifiConnectJob === self) {
                    _ui.update { it.copy(wifiConnectBusy = false) }
                    wifiConnectJob = null
                }
            }
        }
        wifiConnectJob?.start()
    }

    /**
     * Poll an idle PTP/IP session so a silent Wi-Fi drop does not leave the header stuck on
     * “已连接”. Normal camera operations already detect failures themselves; skip heartbeats while
     * an operation/stream is active to avoid competing with gallery and shutter traffic.
     */
    private fun startPtpIpConnectionMonitor() {
        if (!driver.isPtpIpSession || connectionMonitorJob?.isActive == true) return
        connectionMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            delay(PTPIP_HEALTH_INTERVAL_MS)
            while (isActive && driver.isPtpIpSession) {
                val current = _ui.value
                val cameraBusy = phone.cameraBusy || current.pairingBusy || current.capturing || current.movieRecording || current.movieBusy || current.writing || current.exposureSyncing || current.liveViewActive ||
                    current.galleryLoading || current.galleryPreviewBusy || current.downloadingHandle != 0L ||
                    galleryLoadJob?.isActive == true || thumbnailJob?.isActive == true
                if (!cameraBusy) {
                    try {
                        driver.pingPtpIpSession(PTPIP_HEALTH_TIMEOUT_MS)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        HexLog.warn(
                            "PTP/IP 连接监测",
                            "心跳失败，正在清理旧会话：${error.message ?: error.javaClass.simpleName}",
                        )
                        if (driver.isPtpIpSession) runCatching { driver.close() }
                        break
                    }
                }
                delay(PTPIP_HEALTH_INTERVAL_MS)
            }
        }
    }

    private fun stopPtpIpConnectionMonitor() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
    }

    fun connectDevice(dev: UsbDevice) {
        if (_ui.value.wifiConnectBusy || _ui.value.usbConnectBusy || driver.isOpen) return
        cancelHotspotScan()
        _ui.update { it.copy(usbConnectBusy = true, usbConnectionCandidates = emptyList(), hotspotScanMessage = "正在连接有线相机…") }
        viewModelScope.launch {
          try {
            _ui.value = _ui.value.copy(writeMsg = "正在握手…")
            val r = driver.open(dev)
            r.onSuccess { info ->
                _ui.value = _ui.value.copy(
                    writeMsg = "已连接 ${info.model.ifBlank { "Nikon" }}",
                    toast = "Z30 已连接，开始读取槽位",
                )
                // 探测操作码族
                driver.probeOpcodeProfile()
                _ui.value = _ui.value.copy(useAltOpcode = driver.useAltOpcodeProfile)
                refreshSlots()
                loadShootingParams()
            }.onFailure { t ->
                _ui.value = _ui.value.copy(
                    writeMsg = "连接失败：${t.message}",
                    toast = "连接失败：${t.message}",
                    hotspotScanMessage = "有线连接失败：${t.message ?: "请检查数据线和相机模式"}",
                )
            }
          } finally {
            _ui.update { it.copy(usbConnectBusy = false) }
          }
        }
    }

    /** 相机被拔掉时由 Activity 调用：不发 CloseSession（通道已断），只复位本地状态。 */
    fun onDeviceDetached() {
        // A USB topology change must not tear down an unrelated Wi-Fi session.
        if (driver.isPtpIpSession) {
            HexLog.info("USB 热插", "当前使用 PTP/IP，忽略 USB 拔出事件")
            return
        }
        viewModelScope.launch {
            templateCache.clear()
            runCatching { driver.close() }
            _ui.value = _ui.value.copy(
                slots = (1..9).map { SlotState(it, Ptp.CUSTOM_SLOTS[it - 1]) },
                templatePayload = null,
                liveViewActive = false,
                liveViewBitmap = null,
                liveViewFrames = 0,
                galleryVolumes = emptyList(),
                galleryItems = emptyList(),
                thumbnails = emptyMap(),
                galleryLoading = false,
                movieRecording = false,
                paramsLoaded = false,
                writeMsg = "相机已拔出",
                toast = "Z30 已断开",
            )
        }
    }

    fun disconnect() {
        phone.stopSession()
        stopNativeBurst(resumeLiveView = false)
        userRequestedDisconnect = true
        cancelHotspotScan()
        pairingJob?.cancel();pairingJob=null
        wifiConnectJob?.cancel()
        wifiConnectJob = null
        _ui.update { it.copy(wifiConnectBusy = false) }
        resetExposureSync()
        stopPtpIpConnectionMonitor()
        val liveViewCoroutine = liveViewJob
        liveViewJob = null
        _ui.value = _ui.value.copy(writeMsg = "正在断开相机连接…")
        viewModelScope.launch {
            val galleryJob = galleryLoadJob
            val thumbnailsJob = thumbnailJob
            val photoPreviewJob = previewJob
            try {
                // Driver closes PTP/IP sockets first, unblocking a stuck transaction before it
                // waits for the shared I/O lock. Then cancel UI jobs so stale results cannot repaint.
                // Release native shutter before intentionally closing its only command channel.
                kotlinx.coroutines.withTimeoutOrNull(16000) { nativeBurstJob?.join() }
                driver.close()
                liveViewCoroutine?.cancelAndJoin()
                galleryJob?.cancelAndJoin()
                thumbnailsJob?.cancelAndJoin()
                photoPreviewJob?.cancelAndJoin()
            } finally {
                templateCache.clear()
                _ui.value = _ui.value.copy(
                    usbState = NikonPtpDriver.State.DISCONNECTED,
                    deviceInfo = NikonPtpDriver.DeviceInfo(),
                    slots = (1..9).map { SlotState(it, Ptp.CUSTOM_SLOTS[it - 1]) },
                    templatePayload = null,
                    liveViewActive = false,
                    liveViewReady = false,
                    liveViewError = "",
                    liveViewBitmap = null,
                    capturing = false,
                    burstActive = false,
                    galleryVolumes = emptyList(),
                    galleryItems = emptyList(),
                    thumbnails = emptyMap(),
                    galleryLoading = false,
                    galleryPreviewBusy = false,
                    downloadingHandle = 0L,
                    movieRecording = false,
                    paramsLoaded = false,
                    pairingBusy = false,
                    pairingCode = null,
                    pairingStatus = "",
                    batteryPercent = -1,
                    writeMsg = "已断开",
                    toast = "相机连接已断开",
                )
                userRequestedDisconnect = false
            }
        }
    }

    /** 读取 C-1~C-9 当前名称，并把第一个成功读到的存为写入模板。 */
    fun refreshSlots() {
        if (cameraCaptureBusy()) return
        if (!driver.isOpen) return
        viewModelScope.launch {
            val slots = mutableListOf<SlotState>()
            var template: ByteArray? = _ui.value.templatePayload
            for (i in 1..9) {
                val ptpSlot = Ptp.CUSTOM_SLOTS[i - 1]
                val r = driver.getPicCtrlData(ptpSlot)
                val data = r.getOrNull()
                if (data != null && data.size == com.zbox.ai.core.PcPayload614.SIZE) {
                    templateCache[i] = data
                    if (template == null) template = data
                    val parsed = NcpParserAndBuilder.parsePayload(data)
                    slots.add(
                        SlotState(
                            index = i,
                            ptpSlot = ptpSlot,
                            name = parsed?.name?.ifBlank { "C-$i" } ?: "C-$i",
                            loaded = true,
                            isTemplate = (template === data),
                            rawSize = data.size,
                        )
                    )
                } else {
                    // 空槽位读回 AccessDenied 是尼康的正常行为（没注册过的槽位没有数据），
                    // 读到 36B 全零空壳也一样——这些显示「空槽位」而不是「读取失败」，
                    // 后者会让人以为程序坏了。
                    val code = (r.exceptionOrNull() as? PtpException)?.responseCode ?: -1
                    val label = when (code) {
                        Ptp.RC_ACCESS_DENIED, Ptp.RC_LOCAL_EMPTY_SLOT -> "空槽位"
                        Ptp.RC_INVALID_PARAMETER -> "参数不支持"
                        else -> "读取失败"
                    }
                    slots.add(SlotState(i, ptpSlot, name = label, loaded = false, isEmpty = true))
                }
            }
            _ui.value = _ui.value.copy(slots = slots, templatePayload = template)
        }
    }

    fun setTargetSlot(index: Int) {
        _ui.value = _ui.value.copy(targetSlotIndex = index)
    }

    /**
     * 跑一遍写入机制矩阵探测。
     *
     * 为什么把它做成用户可点的按钮：Z30 对 0x90CD 返回 AccessDenied 时不会说明缺什么条件，
     * 参数形态、载荷前缀、要不要先进 PC 控制模式、要不要先停取景——这些只能实测。
     * 与其让用户回报「写不进去」，不如让程序自己把「参数形态 × 载荷形态 × 前置动作」的组合跑一遍，命中即固化。
     */
    fun probeWriteMechanism() {
        if (exposureTransactionBusy()) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (_ui.value.probingWrite) return
        viewModelScope.launch {
            val slotIdx = _ui.value.targetSlotIndex
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIdx - 1]
            val template = templateCache[slotIdx] ?: _ui.value.templatePayload
                ?: NcpParserAndBuilder.defaultTemplate()
            val payload = NcpParserAndBuilder.buildPayload(template, _ui.value.recipe, slotIdx)

            _ui.value = _ui.value.copy(
                probingWrite = true,
                probeLines = listOf("开始探测 C-$slotIdx (0x%04X)…".format(ptpSlot)),
                writeMsg = "正在探测写入机制…",
            )
            val (hit, all) = driver.probeWriteMechanism(ptpSlot, slotIdx, payload) { probe ->
                _ui.value = _ui.value.copy(
                    probeLines = (_ui.value.probeLines + "· ${probe.label} → 0x%04X %s".format(
                        probe.responseCode, Ptp.responseName(probe.responseCode),
                    ) + (probe.readBackName?.let { " ✓$it" } ?: "")).takeLast(14),
                )
            }
            if (hit != null) {
                val mode = driver.writeMode
                _ui.value = _ui.value.copy(
                    probingWrite = false,
                    writeModeLabel = mode?.describe() ?: "",
                    writeMsg = "✅ 找到可用写入机制：${mode?.describe()}\n读回名称：${hit.readBackName}\n后续写入会自动走这套。",
                    toast = "写入机制已固化",
                )
                refreshSlots()
            } else {
                val denied = all.count { it.responseCode == Ptp.RC_ACCESS_DENIED }
                _ui.value = _ui.value.copy(
                    probingWrite = false,
                    writeMsg = "❌ 试了 ${all.size} 种组合全部失败（AccessDenied $denied 次）。\n" +
                        "请到调试台把这批 Hex 日志发我——里面能看出相机到底在拒绝什么。",
                    toast = "探测失败，请发调试台日志",
                )
            }
        }
    }

    /** 扫一片 item 号，把机身里所有能读到的优化校准列出来（找「存了却显示不出来」的槽位）。 */
    fun scanAllPicCtrlItems() {
        if (!driver.isOpen) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writeMsg = "扫描 0x00C0~0x00DC…")
            val found = driver.scanPicCtrlItems { item, name ->
                _ui.value = _ui.value.copy(
                    probeLines = (_ui.value.probeLines +
                        "· 0x%04X → %s".format(item, name ?: "无")).takeLast(14),
                )
            }
            _ui.value = _ui.value.copy(
                writeMsg = if (found.isEmpty()) {
                    "扫描完 0x00C0~0x00DC，一项都读不到。\n" +
                        "说明机身当前不允许读自定义槽位——先在相机上手动选一次「自定义优化校准」再试。"
                } else {
                    "扫到 ${found.size} 项：\n" + found.joinToString("\n") {
                        "C-%d (0x%04X) = %s".format(Ptp.customSlotIndex(it.first) + 1, it.first, it.second)
                    }
                },
                toast = "扫描完成：${found.size} 项",
            )
            refreshSlots()
        }
    }

    /**
     * 先问机身：审计优化校准能力（只读，不会改坏任何槽位）。
     *
     * 顺序上必须排在「盲写探测」前面——真机上 0x90CD 的失败码在 AccessDenied(0x200F) 和
     * InvalidParameter(0x201D) 之间跳，这两种码本身区分不出「参数错」和「没权限」，
     * 只有先拿到 0x90CF 能力表、0xD200 当前项、0x90C5 曲线通道的应答，才知道该往哪修。
     */
    fun auditPicCtrl() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (_ui.value.probingWrite) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                probingWrite = true,
                probeLines = listOf("正在向机身提问（全部只读操作）…"),
                writeMsg = "审计中…",
            )
            val r = driver.auditPictureControl()
            val text = r.getOrElse { "审计失败：${it.message}" }
            _ui.value = _ui.value.copy(
                probingWrite = false,
                writeMsg = text,
                probeLines = text.lines().filter { it.isNotBlank() }.takeLast(14),
                toast = if (r.isSuccess) "审计完成，结果见下方" else "审计失败",
            )
        }
    }

    /**
     * 无损回写：把机身某个已存在的槽位原样读出来再原样写回去。
     *
     * 这是整个写入问题的分水岭——内容一个字节都没变，所以绝不可能损坏数据，
     * 但它能回答「0x90CD 到底通不通」这个决定性问题是/否。
     */
    fun probeWriteBack() {
        if (exposureTransactionBusy()) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (_ui.value.probingWrite) return
        viewModelScope.launch {
            val slotIdx = _ui.value.targetSlotIndex
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIdx - 1]
            _ui.value = _ui.value.copy(
                probingWrite = true,
                probeLines = listOf("无损回写 C-$slotIdx (0x%04X)…".format(ptpSlot)),
                writeMsg = "正在无损回写…",
            )
            val (hit, all) = driver.probeWriteBackSame(ptpSlot) { probe ->
                _ui.value = _ui.value.copy(
                    probeLines = (_ui.value.probeLines +
                        "· ${probe.params} → 0x%04X %s".format(
                            probe.responseCode, Ptp.responseName(probe.responseCode),
                        ) + (probe.readBackName?.let { " ✓$it" } ?: "")).takeLast(14),
                )
            }
            _ui.value = if (hit != null) {
                _ui.value.copy(
                    probingWrite = false,
                    writeModeLabel = driver.writeMode?.describe() ?: "",
                    writeMsg = "✅ 无损回写成功（${hit.params}）——0x90CD 通道是通的，614 字节载荷被接受。\n" +
                        "现在直接点「刷入」就能写进去。",
                    toast = "0x90CD 通道可用",
                )
            } else {
                _ui.value.copy(
                    probingWrite = false,
                    writeMsg = "❌ 连「原样 614 字节写回」都被拒" +
                        "（${all.joinToString(" / ") { "0x%04X".format(it.responseCode) }}）。\n" +
                        "说明 0x90CD 的写权限还缺前置条件，先点「审计机身」看能力表。",
                    toast = "0x90CD 通道被拒",
                )
            }
            refreshSlots()
        }
    }

    /** 把当前 recipe 刷入目标槽位。 */
    fun writeToCamera() {
        if (cameraCaptureBusy()) return
        if (exposureTransactionBusy()) return
        val st = _ui.value
        if (!driver.isOpen) {
            _ui.value = st.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writing = true, writeMsg = "写入中…")
            val slotIdx = _ui.value.targetSlotIndex
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIdx - 1]
            // 关键：以机身读回的真实载荷为模板，只覆盖我们确定的字段。
            // 设置里可以关掉「先备份」：关掉后若没缓存过模板就直接用内置默认，
            // 快一步但风险高——万一机身对某些保留字节有校验，写坏了不好还原。
            if (templateCache[slotIdx] == null && _ui.value.settings.backupBeforeWrite) {
                val backup = driver.getPicCtrlData(ptpSlot).getOrNull()
                if (backup != null) {
                    templateCache[slotIdx] = backup
                    HexLog.info("写入准备", "已先读回 C-$slotIdx 原样模板（${backup.size}B）作为备份")
                } else {
                    HexLog.warn("写入准备", "C-$slotIdx 读不到原样模板，改用内置默认模板")
                }
            }
            val template = templateCache[slotIdx] ?: _ui.value.templatePayload
                ?: NcpParserAndBuilder.defaultTemplate()

            val payload = NcpParserAndBuilder.buildPayload(template, _ui.value.recipe, slotIdx)
            HexLog.info(
                "写入准备",
                "slot=C-$slotIdx(0x%04X) payload=%dB template=%s".format(
                    ptpSlot, payload.size, if (templateCache[slotIdx] != null) "机身读回" else "内置默认"
                ),
            )
            val r = driver.setPicCtrlData(ptpSlot, payload)
            r.onSuccess {
                // 写入后读回校验。设置里可以关掉：省一次往返，但默认开——静默失败比慢更糟。
                val okName: Boolean
                if (_ui.value.settings.verifyAfterWrite) {
                    val verify = driver.getPicCtrlData(ptpSlot)
                    val v = verify.getOrNull()
                    okName = v != null && com.zbox.ai.core.PcPayload614.readName(v) ==
                        NcpParserAndBuilder.encodeName(_ui.value.recipe.name).toString(Charsets.US_ASCII)
                } else {
                    okName = true
                }
                _ui.value = _ui.value.copy(
                    writing = false,
                    writeMsg = if (!_ui.value.settings.verifyAfterWrite) "已刷入 C-$slotIdx（未回读校验）"
                    else if (okName) "已刷入 C-$slotIdx ✓ 校验通过"
                    else "已刷入 C-$slotIdx（读回名称未完全匹配，请到相机确认）",
                    toast = "C-$slotIdx 写入完成",
                )
                refreshSlots()
            }.onFailure { t ->
                val code = (t as? PtpException)?.responseCode ?: -1
                if (code == Ptp.RC_ACCESS_DENIED && driver.writeMode == null) {
                    // 第一次遇到 AccessDenied：不要闷头跑 80 种盲探——真机实测那是浪费时间，
                    // 因为 AccessDenied 和 InvalidParameter 混在一起，错误码本身不告诉缺什么。
                    // 先跑一次「无损回写」：内容零变化，却能一刀切判定 0x90CD 通道通不通。
                    _ui.value = _ui.value.copy(
                        writing = false,
                        writeMsg = "相机拒绝了标准写入（AccessDenied），正在做无损回写判定…",
                    )
                    val (hit, all) = driver.probeWriteBackSame(ptpSlot) { p ->
                        _ui.value = _ui.value.copy(
                            probeLines = (_ui.value.probeLines +
                                "· ${p.params} → 0x%04X".format(p.responseCode)).takeLast(14),
                        )
                    }
                    if (hit != null) {
                        // 通道通：按固化下来的参数形态重试一次真实写入
                        val mode = driver.writeMode
                        val retry = driver.setPicCtrlData(ptpSlot, payload)
                        _ui.value = _ui.value.copy(
                            writeModeLabel = mode?.describe() ?: "",
                            writeMsg = if (retry.isSuccess) {
                                "✅ 无损回写确认通道可用（${hit.params}），已按该形态写入 C-$slotIdx。"
                            } else {
                                "无损回写能通（${hit.params}），但真实载荷仍被拒：${retry.exceptionOrNull()?.message}\n" +
                                    "说明问题在载荷内容而非通道——把当前配方换个更简单的曲线再试。"
                            },
                            toast = if (retry.isSuccess) "写入成功" else "载荷被拒",
                        )
                        refreshSlots()
                    } else {
                        _ui.value = _ui.value.copy(
                            writeMsg = "写入失败：连「原样写回」都被拒（${
                                all.joinToString(" / ") { "0x%04X".format(it.responseCode) }
                            }）。\n" +
                                "结论：Z30 固件封着 0x90CD 写入通道，改参数形态没有意义。\n" +
                                "请点「① 审计机身」——里面会试 0x90C5 曲线通道和 0x9435 应用模式。",
                            toast = "0x90CD 通道被封",
                        )
                    }
                } else {
                    _ui.value = _ui.value.copy(
                        writing = false,
                        writeMsg = "写入失败：${t.message}",
                        toast = "写入失败：${t.message}",
                    )
                }
            }
        }
    }

    /** 批量刷入多个槽位。 */
    fun batchWrite(targets: List<Int>, recipe: Recipe) {
        if (exposureTransactionBusy()) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writing = true, writeMsg = "批量写入 ${targets.size} 个槽位…")
            var ok = 0
            for (idx in targets) {
                val ptpSlot = Ptp.CUSTOM_SLOTS[idx - 1]
                val template = templateCache[idx] ?: NcpParserAndBuilder.defaultTemplate()
                val payload = NcpParserAndBuilder.buildPayload(template, recipe, idx)
                if (driver.setPicCtrlData(ptpSlot, payload).isSuccess) ok++
            }
            _ui.value = _ui.value.copy(
                writing = false,
                writeMsg = "批量完成 $ok/${targets.size}",
                toast = "批量写入 $ok/${targets.size} 成功",
            )
            refreshSlots()
        }
    }

    fun clearSlot(index: Int) {
        if (!driver.isOpen) return
        viewModelScope.launch {
            val ptpSlot = Ptp.CUSTOM_SLOTS[index - 1]
            val r = driver.deleteCustomPicCtrl(ptpSlot)
            _ui.value = _ui.value.copy(
                toast = if (r.isSuccess) "C-$index 已清空" else "清空失败：${r.exceptionOrNull()?.message}",
            )
            refreshSlots()
        }
    }

    // ---------------- 图像与预览 ----------------

    fun pickReference(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                ImageLoader.load(getApplication(), uri, 1600)
            }
            if (bmp == null) {
                _ui.value = _ui.value.copy(toast = "图片读取失败")
                return@launch
            }
            val feats = withContext(Dispatchers.Default) { ImageFeatureExtractor.extract(bmp) }
            _ui.value = _ui.value.copy(referenceBitmap = bmp, features = feats, lastAnalysis = "")
            renderPreview()
        }
    }

    fun setShowOriginal(show: Boolean) {
        _ui.value = _ui.value.copy(showOriginal = show)
    }

    fun setUserHint(hint: String) {
        _ui.value = _ui.value.copy(userHint = hint)
    }

    /** 拖动曲线控制点后更新 recipe 并重渲染（带 debounce）。 */
    fun updateCurvePoints(points: List<CubicSplineLut.Point>) {
        val clean = AiColorGradingRepository.sanitizeCurve(points)
        val lut = CubicSplineLut.buildLut(clean)
        _ui.value = _ui.value.copy(
            recipe = _ui.value.recipe.copy(curvePoints = clean, lut = lut, useCustomCurve = true),
        )
        renderPreview()
    }

    /** 一键恢复默认曲线：对角线控制点 + 恒等 LUT（即 Recipe 初始值）。只改 App 里的配方，不写相机。 */
    fun resetCurveToDefault() {
        _ui.value = _ui.value.copy(
            recipe = _ui.value.recipe.copy(
                curvePoints = Recipe.DEFAULT_CURVE,
                lut = CubicSplineLut.identityLut(),
                useCustomCurve = true,
            ),
            toast = "曲线已恢复默认",
        )
        renderPreview()
    }

    fun updateRecipeParam(
        sharpening: Float? = null,
        clarity: Float? = null,
        contrast: Float? = null,
        brightness: Float? = null,
        saturation: Float? = null,
        hue: Float? = null,
        name: String? = null,
    ) {
        val r = _ui.value.recipe
        val nr = r.copy(
            sharpening = sharpening ?: r.sharpening,
            clarity = clarity ?: r.clarity,
            contrast = contrast ?: r.contrast,
            brightness = brightness ?: r.brightness,
            saturation = saturation ?: r.saturation,
            hue = hue ?: r.hue,
            name = name ?: r.name,
        )
        _ui.value = _ui.value.copy(recipe = nr)
        if (contrast != null || brightness != null || saturation != null || hue != null) {
            renderPreview()
        }
    }

    /** 256 LUT 套到参考图上生成预览。debounce 180ms 避免拖动时卡顿。 */
    fun renderPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(180)
            val st = _ui.value
            val src = st.referenceBitmap ?: return@launch
            val r = st.recipe
            val bmp = withContext(Dispatchers.Default) {
                ImagePipeline.render(
                    src = src,
                    lut = r.lut,
                    contrast = r.contrast,
                    brightness = r.brightness,
                    saturation = r.saturation,
                    hue = r.hue,
                )
            }
            _ui.value = _ui.value.copy(previewBitmap = bmp)
        }
    }

    // ---------------- Manual composition: only analyzeComposition() can issue a request. ----------------
    fun openComposition() {
        _ui.update { it.copy(compositionOpen = true) }
    }

    fun clearComposition(close: Boolean = false) {
        compositionEpoch++
        compositionJob?.cancel()
        compositionJob = null
        _ui.update { it.copy(compositionOpen = if (close) false else it.compositionOpen,
            compositionBusy = false, compositionSnapshot = null, compositionAdvice = null, compositionError = "") }
    }

    fun analyzeComposition() {
        val st = _ui.value
        if (!st.compositionOpen || st.compositionBusy) return
        val account = st.activeAccount
        val shooting = st.capturing || st.burstActive || st.movieBusy || st.movieRecording || st.writing || st.analyzing
        if (!com.zbox.ai.core.CompositionPolicy.canAnalyze(st.compositionOpen,st.compositionBusy,
                st.isConnected,st.liveViewActive,st.liveViewBitmap != null,shooting,
                account.provider.supportsVision,account.enabled,account.apiKey.isNotBlank())) {
            val error = when {
                !st.isConnected || !st.liveViewActive || st.liveViewBitmap == null -> "请先连接相机并开启实时取景"
                shooting -> "拍摄或其他 AI 任务进行中，请稍后手动分析"
                !account.enabled -> "当前供应商已停用，请先调整 AI 配置"
                !account.provider.supportsVision -> "当前模型未配置视觉能力，构图不能使用纯文本降级，请选择视觉模型"
                else -> "请先在 AI 供应商设置中填写 API Key"
            }
            _ui.update { it.copy(compositionError = error) }; return
        }
        val frame = st.liveViewBitmap ?: return
        val epoch = ++compositionEpoch
        _ui.update { it.copy(compositionBusy = true, compositionError = "", compositionAdvice = null, compositionSnapshot = null) }
        compositionJob = viewModelScope.launch {
            try {
                val snapshot = com.zbox.ai.ai.CompositionRepository.snapshot(frame)
                ensureActive()
                if (epoch != compositionEpoch) return@launch
                _ui.update { it.copy(compositionSnapshot = snapshot) }
                val result = com.zbox.ai.ai.CompositionRepository.analyze(snapshot,account)
                ensureActive()
                // Usage is reported only if the provider returns it; canceled server-side billing is unknown.
                store.recordUsage(account.providerId,result.promptTokens,result.completionTokens,result.error)
                if (epoch == compositionEpoch) _ui.update { it.copy(compositionAdvice=result.advice,compositionError=result.error) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (epoch == compositionEpoch) _ui.update { it.copy(compositionError="构图分析失败，未自动重试；请检查配置后手动重试") }
            } finally {
                if (epoch == compositionEpoch) _ui.update { it.copy(compositionBusy=false) }
            }
        }
    }

    // ---------------- AI ----------------

    fun analyzeWithAi() {
        val st = _ui.value
        val bmp = st.referenceBitmap ?: run {
            _ui.value = st.copy(toast = "请先选择参考图")
            return
        }
        val account = st.activeAccount
        if (account.apiKey.isBlank()) {
            _ui.value = st.copy(toast = "${account.provider.displayName} 未配置 API Key，请到「本地预设库」填写")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                analyzing = true,
                analyzingMsg = "上传参考图 → ${account.provider.displayName}",
                lastAnalysis = "",
            )
            val (recipe, result) = AiColorGradingRepository.analyzeBitmap(bmp, account, _ui.value.userHint)
            store.recordUsage(
                providerId = account.providerId,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                error = if (result.success) "" else result.error,
            )
            if (recipe != null) {
                _ui.value = _ui.value.copy(
                    analyzing = false,
                    recipe = recipe,
                    analyzingMsg = "",
                    lastAnalysis = recipe.styleAnalysis,
                    toast = "分析完成 · 用时 ${result.elapsedMs}ms · ${result.promptTokens}+${result.completionTokens} tokens",
                )
                renderPreview()
            } else {
                _ui.value = _ui.value.copy(
                    analyzing = false,
                    analyzingMsg = "",
                    lastAnalysis = "失败：${result.error}",
                    toast = "AI 分析失败：${result.error.take(60)}",
                )
            }
        }
    }

    fun saveCurrentPreset() {
        val r = _ui.value.recipe
        viewModelScope.launch {
            val preset = SavedPreset(
                id = UUID.randomUUID().toString(),
                name = r.name,
                source = if (r.styleAnalysis.isNotBlank()) "AI" else "MANUAL",
                recipe = r,
            )
            store.savePreset(preset)
            _ui.value = _ui.value.copy(toast = "已保存：${r.name}")
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch { store.deletePreset(id) }
    }

    fun loadPreset(preset: SavedPreset) {
        _ui.value = _ui.value.copy(recipe = preset.recipe, lastAnalysis = preset.recipe.styleAnalysis)
        renderPreview()
        _ui.value = _ui.value.copy(toast = "已载入 ${preset.name}")
    }

    // ---------------- 内置 200 套预设 ----------------

    fun setBuiltinCategory(categoryId: String?) {
        _ui.value = _ui.value.copy(builtinCategory = categoryId)
    }

    fun setBuiltinQuery(q: String) {
        _ui.value = _ui.value.copy(builtinQuery = q)
    }

    fun setBuiltinPreview(id: String?) {
        _ui.value = _ui.value.copy(builtinPreviewId = if (_ui.value.builtinPreviewId == id) null else id)
    }

    /** 载入内置配方到「当前配方」，之后可继续在曲线编辑器里微调。 */
    fun loadBuiltIn(preset: BuiltInPreset) {
        _ui.value = _ui.value.copy(
            recipe = preset.recipe,
            lastAnalysis = "${preset.displayName}（${preset.categoryLabel}）— ${preset.note}",
        )
        renderPreview()
        _ui.value = _ui.value.copy(toast = "已载入「${preset.displayName}」")
    }

    /** 把内置配方直接刷进指定槽位（不经当前配方，避免覆盖用户手上的编辑）。 */
    fun writeBuiltInToSlot(preset: BuiltInPreset, slotIndex: Int) {
        if (cameraCaptureBusy()) return
        if (exposureTransactionBusy()) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writing = true, writeMsg = "写入「${preset.displayName}」→ C-$slotIndex…")
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIndex - 1]
            val template = templateCache[slotIndex] ?: NcpParserAndBuilder.defaultTemplate()
            val payload = NcpParserAndBuilder.buildPayload(template, preset.recipe, slotIndex)
            val r = driver.setPicCtrlData(ptpSlot, payload)
            r.onSuccess {
                _ui.value = _ui.value.copy(writing = false, toast = "${preset.displayName} → C-$slotIndex 成功")
                refreshSlots()
            }.onFailure {
                _ui.value = _ui.value.copy(writing = false, toast = "写入失败：${it.message}")
            }
        }
    }

    /** 内置配方 → 存进本地预设库（用户想混着自己的配方一起管理时用）。 */
    fun saveBuiltInToLibrary(preset: BuiltInPreset) {
        viewModelScope.launch {
            store.savePreset(
                SavedPreset(
                    id = UUID.randomUUID().toString(),
                    name = preset.displayName,
                    source = "BUILTIN",
                    recipe = preset.recipe,
                )
            )
            _ui.value = _ui.value.copy(toast = "已收藏「${preset.displayName}」到预设库")
        }
    }

    fun toggleAiPanel() {
        _ui.value = _ui.value.copy(aiPanelExpanded = !_ui.value.aiPanelExpanded)
    }

    fun writePresetToSlot(preset: SavedPreset, slotIndex: Int) {
        if (cameraCaptureBusy()) return
        if (exposureTransactionBusy()) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIndex - 1]
            val template = templateCache[slotIndex] ?: NcpParserAndBuilder.defaultTemplate()
            val payload = NcpParserAndBuilder.buildPayload(template, preset.recipe, slotIndex)
            val r = driver.setPicCtrlData(ptpSlot, payload)
            r.onSuccess {
                store.markPresetSlot(preset.id, slotIndex)
                _ui.value = _ui.value.copy(toast = "${preset.name} → C-$slotIndex 成功")
                refreshSlots()
            }.onFailure {
                _ui.value = _ui.value.copy(toast = "写入失败：${it.message}")
            }
        }
    }

    /**
     * 导入 .NCP 文件。
     *
     * 排障要点：这里的每条失败路径都必须给出**具体原因**（打不开 / 空文件 / 结构不认识 / 多大），
     * 因为用户在手机上点一次导入失败，我们没有 adb 就没法复现。以前只报「读取文件失败」，
     * 结果连「UI 的 OpenDocument 回调到底有没有触发」都判断不了。
     */
    fun importNcp(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            HexLog.info("NCP 导入", "开始读取 $uri")

            val name = withContext(Dispatchers.IO) { runCatching { ImageLoader.displayName(ctx, uri) }.getOrDefault("import.NCP") }

            val bytes = withContext(Dispatchers.IO) {
                runCatching { ImageLoader.readBytesOrThrow(ctx, uri) }
            }
            val data = bytes.getOrElse { t ->
                val msg = t.message ?: t.javaClass.simpleName
                HexLog.error("NCP 导入失败", "读取 $name 失败：$msg")
                _ui.value = _ui.value.copy(
                    toast = "读取失败：$msg",
                    lastAnalysis = "导入 $name 失败：$msg",
                )
                return@launch
            }
            HexLog.info("NCP 导入", "读到 $name，${data.size} 字节")

            val parsed = NcpParserAndBuilder.parseNcpFile(name, data)
            parsed.onSuccess { ncp ->
                HexLog.info("NCP 导入成功", "$name → ${ncp.recipe.name}（${ncp.layout}，源 ${ncp.rawSize}B）")
                _ui.value = _ui.value.copy(
                    recipe = ncp.recipe,
                    lastAnalysis = "从 ${ncp.fileName} 导入（${ncp.layout}，${ncp.rawSize}B）",
                    toast = "解析成功：${ncp.recipe.name}（${ncp.layout}）",
                )
                renderPreview()
                store.savePreset(
                    SavedPreset(
                        id = UUID.randomUUID().toString(),
                        name = ncp.recipe.name,
                        source = "NCP",
                        recipe = ncp.recipe,
                    )
                )
            }.onFailure { t ->
                val msg = t.message ?: t.javaClass.simpleName
                HexLog.error("NCP 导入失败", "$name（${data.size}B）解析不了：$msg")
                _ui.value = _ui.value.copy(
                    toast = "解析失败：$msg",
                    lastAnalysis = "导入 $name 失败（${data.size} 字节）：$msg",
                )
            }
        }
    }

    // ---------------- 设置 ----------------

    fun setApiKey(providerId: String, key: String) {
        viewModelScope.launch { store.setApiKey(providerId, key) }
    }

    fun setCustomBaseUrl(baseUrl: String) {
        viewModelScope.launch { store.setCustomBaseUrl(baseUrl) }
    }

    fun setCustomVision(enabled: Boolean) {
        viewModelScope.launch { store.setCustomVision(enabled) }
    }

    fun setModelOverride(providerId: String, model: String) {
        viewModelScope.launch { store.setModelOverride(providerId, model) }
    }

    fun fetchProviderModels(providerId: String, apiKey: String, baseUrl: String) {
        _ui.value = _ui.value.copy(
            providerModelBusy = _ui.value.providerModelBusy + providerId,
            providerModelFetchStatus = _ui.value.providerModelFetchStatus + (providerId to "正在读取 /models…"),
        )
        viewModelScope.launch {
            val result = OpenAiCompatibleModelApi.listModels(baseUrl, apiKey)
            val busy = _ui.value.providerModelBusy - providerId
            result.fold(
                onSuccess = { models -> _ui.value = _ui.value.copy(
                    providerModelBusy = busy,
                    providerModelOptions = _ui.value.providerModelOptions + (providerId to models),
                    providerModelFetchStatus = _ui.value.providerModelFetchStatus + (providerId to "已获取 ${models.size} 个模型；列表表示该 API Key 可见的模型"),
                ) },
                onFailure = { e -> _ui.value = _ui.value.copy(
                    providerModelBusy = busy,
                    providerModelFetchStatus = _ui.value.providerModelFetchStatus + (providerId to "获取失败：${e.message}"),
                ) },
            )
        }
    }

    fun testProviderModel(providerId: String, apiKey: String, baseUrl: String, model: String) {
        _ui.value = _ui.value.copy(
            providerModelBusy = _ui.value.providerModelBusy + providerId,
            providerModelTestStatus = _ui.value.providerModelTestStatus + (providerId to "正在发起最小模型请求…"),
        )
        viewModelScope.launch {
            val result = OpenAiCompatibleModelApi.testModel(baseUrl, apiKey, model)
            result.fold(
                onSuccess = { message -> _ui.value = _ui.value.copy(
                    providerModelBusy = _ui.value.providerModelBusy - providerId,
                    providerModelTestStatus = _ui.value.providerModelTestStatus + (providerId to message),
                ) },
                onFailure = { e -> _ui.value = _ui.value.copy(
                    providerModelBusy = _ui.value.providerModelBusy - providerId,
                    providerModelTestStatus = _ui.value.providerModelTestStatus + (providerId to "测试失败：${e.message}"),
                ) },
            )
        }
    }

    fun setActiveProvider(providerId: String) {
        viewModelScope.launch {
            store.setActiveProvider(providerId)
            _ui.value = _ui.value.copy(toast = "已切换到 ${LlmProvider.byId(providerId).displayName}")
        }
    }

    fun resetUsage(providerId: String) {
        viewModelScope.launch {
            store.resetUsage(providerId)
            _ui.value = _ui.value.copy(toast = "用量已清零")
        }
    }

    // ---------------- 常规设置 ----------------

    /**
     * 改一项常规设置。
     *
     * 统一走 `updateSettings { it.copy(...) }`：DataStore 里存的是一个 JSON，
     * 并发改两个开关时各自 copy 再写会互相覆盖，所以 store 里是先 first() 再写，
     * 这里只负责把用户动作翻译成一次 copy。
     */
    private fun editSettings(transform: (com.zbox.ai.data.AppSettings) -> com.zbox.ai.data.AppSettings) {
        viewModelScope.launch {
            store.updateSettings(transform)
            // 改完立刻回灌一次，避免开关视觉上慢半拍
            _ui.value = _ui.value.copy(settings = store.settings.first())
        }
    }

    fun setGalleryLimit(v: Int) = editSettings { it.copy(galleryLimit = v) }

    fun setLiveViewInterval(v: Int) = editSettings { it.copy(liveViewIntervalMs = com.zbox.ai.core.MonitorPolicy.normalizeInterval(v)) }

    fun setBackupBeforeWrite(v: Boolean) = editSettings { it.copy(backupBeforeWrite = v) }

    fun setVerifyAfterWrite(v: Boolean) = editSettings { it.copy(verifyAfterWrite = v) }

    fun setAutoSaveCapture(v: Boolean) = editSettings { it.copy(autoSaveCapture = v) }

    fun setLogToFile(v: Boolean) = editSettings { it.copy(logToFile = v) }

    fun setHexInLog(v: Boolean) = editSettings { it.copy(hexInLog = v) }

    fun setDefaultTargetSlot(v: Int) = editSettings { it.copy(defaultTargetSlot = v) }

    fun setKeepScreenOn(v: Boolean) = editSettings { it.copy(keepScreenOn = v) }

    fun setLightTheme(v: Boolean) = editSettings { it.copy(lightTheme = v) }

    /** 恢复默认设置。只动设置，不碰预设库与 API Key。 */
    fun resetSettings() {
        viewModelScope.launch {
            store.updateSettings { com.zbox.ai.data.AppSettings() }
            _ui.value = _ui.value.copy(
                settings = store.settings.first(),
                toast = "设置已恢复默认",
            )
        }
    }

    // ---------------- 调试台 ----------------

    /** 打开/关闭 USB 调试台覆盖层（入口在设置页，主界面不再占一个 tab）。 */
    fun openConsole(open: Boolean = true) {
        if (open) stopNativeBurst(resumeLiveView = false)
        _ui.value = _ui.value.copy(consoleOpen = open)
        if (open) HexLog.info("调试台", "以覆盖层方式打开（入口：设置 → 高级 → USB 调试台）")
    }

    /** 打开/关闭设置覆盖层。 */
    fun openSettings(open: Boolean = true) {
        if (open) stopNativeBurst(resumeLiveView = false)
        _ui.value = _ui.value.copy(settingsOpen = open)
    }

    /** 切换优化校准操作码的参数形态并重新探测。两参数 (slot,0) ↔ 单参数 (slot) */
    fun setOpcodeProfile(alt: Boolean) {
        if (cameraCaptureBusy()) return
        driver.useAltOpcodeProfile = alt
        _ui.value = _ui.value.copy(useAltOpcode = alt)
        HexLog.info(
            "参数形态切换",
            if (alt) "0x90CC/0x90CD/0x90CE 单参数 (slot)"
            else "0x90CC/0x90CD/0x90CE 双参数 (slot, 0)",
        )
        if (driver.isOpen) {
            templateCache.clear()
            refreshSlots()
        }
    }

    fun probeProfile() {
        if (cameraCaptureBusy()) return
        viewModelScope.launch {
            val r = driver.probeOpcodeProfile()
            _ui.value = _ui.value.copy(useAltOpcode = driver.useAltOpcodeProfile)
            _ui.value = _ui.value.copy(
                toast = r.fold(
                    onSuccess = { ok -> if (ok) "操作码可用：0x%04X".format(driver.opGetPicCtrl) else "两种操作码族都无响应" },
                    onFailure = { it.message ?: "探测失败" },
                )
            )
            if (driver.isOpen) refreshSlots()
        }
    }

    /** 主动进/出 PC 控制模式（0x90C2），有些机身只有在这个模式下才吐自定义槽位。 */
    fun enterPcControlMode(on: Boolean = true) {
        if (cameraCaptureBusy()) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            val r = driver.changeCameraMode(on)
            HexLog.info(
                "ChangeCameraMode 0x90C2",
                if (r.isSuccess) "已切到 ${if (on) "PC 控制" else "普通"} 模式" else "失败：${r.exceptionOrNull()?.message}",
            )
            driver.deviceReadyBlocking()
            _ui.value = _ui.value.copy(
                toast = if (r.isSuccess) "已切 PC 控制模式，正在重读槽位" else "切换失败",
            )
            templateCache.clear()
            refreshSlots()
        }
    }

    /** 一键诊断：分 4 层定位卡在哪一步，结果进日志和 toast。 */
    fun runDiagnose() {
        if (cameraCaptureBusy()) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writeMsg = "诊断中…")
            val r = driver.diagnose()
            _ui.value = _ui.value.copy(
                writeMsg = r.getOrElse { "诊断失败：${it.message}" },
                toast = if (r.isSuccess) "诊断完成，见日志" else "诊断失败",
            )
        }
    }

    /**
     * 问机身：这 8 条属性到底哪些是「只读」。
     *
     * 真机上 `0x500E` 回 AccessDenied、光圈 ISO 却能写，光看错误码分不清是
     * 「属性本身只读」还是「当前状态被锁」。`0x1014` 描述符里第 5 字节就是
     * GetSet 标志，是机身自己给的权威答案。全程只读，不改任何机身状态。
     */
    fun probePropWritable() {
        if (cameraCaptureBusy()) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(probingWrite = true, writeMsg = "正在读属性描述符…")
            val list = runCatching { driver.probeWritableProps() }.getOrElse {
                _ui.value = _ui.value.copy(probingWrite = false, writeMsg = "读属性失败：${it.message}")
                return@launch
            }
            val lines = list.map { p ->
                val flag = when (p.writable) {
                    true -> "可写"
                    false -> "只读"
                    null -> "未知"
                }
                "%-18s %s  当前=%s  %s".format(p.label, flag, p.currentHex, p.note)
            }
            val rw = list.count { it.writable == true }
            val ro = list.count { it.writable == false }
            val head = "属性可写性（0x1014 描述符）：可写 $rw 条 / 只读 $ro 条 / 未知 ${list.size - rw - ro} 条"
            HexLog.info("属性可写性", (listOf(head) + lines).joinToString(" ⏎ "))
            _ui.value = _ui.value.copy(
                probingWrite = false,
                probeLines = lines,
                writeMsg = head,
                // 把「机身自己说只读」的那些记下来：下发时直接跳过，不再白发一次 0x1016
                readOnlyProps = list.filter { it.writable == false }.map { it.code }.toSet(),
                toast = "可写 $rw 条 / 只读 $ro 条，详见日志",
            )
        }
    }

    /** 重新读一个槽位并把 84 字节原始载荷写进 Hex 日志，方便联调对偏移。 */
    fun dumpSlotRaw(index: Int) {
        if (cameraCaptureBusy()) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            val ptpSlot = Ptp.CUSTOM_SLOTS[index - 1]
            val r = driver.getPicCtrlData(ptpSlot)
            r.onSuccess { bytes ->
                templateCache[index] = bytes
                HexLog.log(
                    HexLogEntry.Direction.RX, -1,
                    "GetPicCtrlData C-$index 原始载荷 ${bytes.size}B", bytes,
                    note = "槽位 0x%04X".format(ptpSlot),
                )
                _ui.value = _ui.value.copy(toast = "C-$index 读回 ${bytes.size} 字节，见日志")
            }.onFailure { t ->
                _ui.value = _ui.value.copy(toast = "读取失败：${t.message}")
            }
        }
    }

    /** 把指定槽位的模板塞进当前 recipe 的曲线段（用于“基于机身模板微调”）。 */
    suspend fun captureTemplateFor(index: Int): ByteArray? {
        if (!driver.isOpen) return templateCache[index] ?: _ui.value.templatePayload
        val ptpSlot = Ptp.CUSTOM_SLOTS[index - 1]
        val bytes = driver.getPicCtrlData(ptpSlot).getOrNull() ?: return templateCache[index]
        templateCache[index] = bytes
        return bytes
    }

    fun clearLog() = HexLog.clear()

    /**
     * 把 Hex 日志写成文件，路径回显在 toast 里。
     *
     * 设计动机：真机上只有一个 USB-C 口，接了电脑做 adb 就没法接相机 —— 用户没法一边联调一边取日志。
     * 让 App 自己落盘到 `Android/data/com.zbox.ai/files/`，拔线接相机跑一轮，之后用任意文件管理器
     * 或插回电脑取文件即可。这个目录不需要任何存储权限。
     */
    fun exportLogToFile() {
        val ctx = getApplication<Application>()
        val f = HexLog.saveToFile(ctx)
        if (f == null) {
            _ui.value = _ui.value.copy(toast = "日志导出失败（目录不可写）")
        } else {
            HexLog.info("日志已导出", f.absolutePath)
            _ui.value = _ui.value.copy(
                toast = "已导出：${f.absolutePath}（${f.length() / 1024}KB）",
                writeMsg = "日志文件：\n${f.absolutePath}",
            )
        }
    }

    /** 手机当前是否枚举到了任何 USB 外设。false 说明 OTG 主机没起来，压根没到 PTP 层。 */
    fun usbDeviceCount(): Int = runCatching { usbManager().deviceList.size }.getOrDefault(0)

    /**
     * 把最新日志用系统分享发出去（微信/QQ/邮件/网盘都行）。
     *
     * 为什么不只给路径：Android 11+ 之后 `/sdcard/Android/data/<pkg>/` 对文件管理器不可见，
     * 普通用户根本进不去那个目录。走 FileProvider + ACTION_SEND，一键就能把文件发到电脑上。
     */
    fun shareLatestLog() {
        val ctx = getApplication<Application>()
        val src = HexLog.currentLogFile() ?: HexLog.saveToFile(ctx)
        if (src == null || !src.exists()) {
            _ui.value = _ui.value.copy(toast = "还没有日志文件")
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", src
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Zbox 日志 ${src.name}")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(intent, "发送日志文件").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
            _ui.value = _ui.value.copy(
                toast = "已调起分享：${src.name}（${src.length() / 1024}KB）",
                writeMsg = "日志文件：${src.absolutePath}（${src.length() / 1024}KB）",
            )
        }.onFailure { t ->
            _ui.value = _ui.value.copy(toast = "分享失败：${t.message}")
        }
    }

    // ---------------- 实时取景（Live View） ----------------

    private var liveViewJob: Job? = null

    /** 开/关实时取景。Z30 的 0x9201 只负责进 LV 模式，画面靠 0x9203 轮询拉 JPEG。 */
    fun toggleLiveView() {
        if (phone.cameraBusy || _ui.value.downloadingHandle!=0L || _ui.value.pairingBusy || _ui.value.capturing || _ui.value.movieRecording || _ui.value.movieBusy) return
        if (_ui.value.liveViewActive) stopLiveView() else startLiveView()
    }

    fun startLiveView() {
        if(phone.cameraBusy || _ui.value.cameraMenuBusy)return
        if (_ui.value.pairingBusy || _ui.value.burstActive || _ui.value.movieBusy) return
        if (_ui.value.movieRecording && !driver.liveViewRunning) return
        if (_ui.value.exposureSyncing) return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (liveViewJob != null) return
        _ui.value = _ui.value.copy(liveViewActive = true, liveViewError = "", liveViewFrames = 0)
        liveViewJob = viewModelScope.launch {
            try {
                val started = if (_ui.value.movieRecording && driver.liveViewRunning) Result.success(Unit) else driver.startLiveView()
                if (started.isFailure) {
                    val msg = started.exceptionOrNull()?.message ?: "0x9201 无响应"
                    HexLog.warn("实时取景开启失败", msg)
                    _ui.value = _ui.value.copy(liveViewActive = false, liveViewError = msg)
                    liveViewJob = null
                    return@launch
                }
                HexLog.info("实时取景", "0x9201 StartLiveView 成功，开始轮询 0x9203")
                var frames = 0
                var missStreak = 0
                while (_ui.value.liveViewActive) {
                    if(_ui.value.touchFocusBusy || _ui.value.cameraMenuBusy){delay(50);continue}
                    val frameStartedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    val r = driver.getLiveViewFrame()
                    val bytes = r.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bmp = withContext(Dispatchers.Default) {
                            runCatching {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            frames++
                            missStreak = 0
                            _ui.value = _ui.value.copy(liveViewBitmap = bmp, liveViewFrames = frames)
                        } else {
                            missStreak++
                        }
                    } else {
                        missStreak++
                        if (missStreak == 8) {
                            HexLog.warn("实时取景", r.exceptionOrNull()?.message ?: "连续 8 帧无数据")
                        }
                    }
                    // Include transfer and decode in the frame budget; keep PTP requests serial.
                    val waitMs = if (missStreak > 3) 400L else com.zbox.ai.core.MonitorPolicy.remainingDelayMs(
                        android.os.SystemClock.elapsedRealtimeNanos() - frameStartedNanos,
                        _ui.value.settings.liveViewIntervalMs,
                    )
                    if (waitMs > 0) delay(waitMs) else kotlinx.coroutines.yield()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                HexLog.error("实时取景中断", message)
                if (!driver.isOpen) runCatching { driver.close() }
                _ui.value = _ui.value.copy(
                    liveViewActive = false,
                    liveViewBitmap = null,
                    liveViewError = message,
                    toast = if (driver.isOpen) "实时取景异常" else "相机连接已断开",
                )
                liveViewJob = null
            }
        }
    }

    private var menuResumeEpoch=0L
    fun stopLiveView() {
        menuResumeEpoch++
        if (!_ui.value.liveViewActive && liveViewJob == null) return
        _ui.value = _ui.value.copy(liveViewActive = false)
        val job = liveViewJob
        liveViewJob = null
        viewModelScope.launch {
            job?.cancel()
            driver.endLiveView()
            _ui.value = _ui.value.copy(liveViewBitmap = null)
            HexLog.info("实时取景", "0x9202 EndLiveView")
        }
    }

    /**
     * 停取景并**等它真的停掉**再返回。
     *
     * `stopLiveView()` 是 fire-and-forget（内部 launch），拍摄/下发参数前如果只调它，
     * 0x9202 还在飞的时候 0x9207 / 0x1016 就发出去了，机身一律回 AccessDenied。
     * 这是「点下发报 0x500E AccessDenied」「拍完拿不到照片」的共同原因。
     */
    private suspend fun stopLiveViewAwait() {
        val job = liveViewJob
        liveViewJob = null
        _ui.value = _ui.value.copy(liveViewActive = false, liveViewBitmap = null)
        job?.cancelAndJoin()
        runCatching { driver.endLiveView() }
        // 给机身一点时间真正退出 LV 模式，否则紧接着的属性写入仍会被判无权限
        delay(200)
        HexLog.info("实时取景", "0x9202 EndLiveView（已确认停止）")
    }

    // ---------------- 拍摄参数（0x1015 / 0x1016） ----------------

    /** 从机身读回当前曝光参数，填充 UI。读不到的项保持默认值。 */
    fun loadShootingParams() {
        if(phone.cameraBusy)return
        if (!driver.isOpen || pendingExposure.isNotEmpty() || _ui.value.exposureSyncing) return
        val revisionAtStart = exposureRevision
        val sessionAtStart = exposureSession
        viewModelScope.launch {
            val iso = driver.getDeviceProp(Ptp.DPC_ISO).getOrNull()?.let { CameraStorage.u16(it, 0) }
            val fnum = driver.getDeviceProp(Ptp.DPC_F_NUMBER).getOrNull()?.let { CameraStorage.u16(it, 0) }
            val apertureCapability = driver.getDevicePropDesc(Ptp.DPC_F_NUMBER).getOrNull()
                ?.let { CameraAperturePolicy.parseDescriptor(it) }
            val prog = driver.getDeviceProp(Ptp.DPC_EXPOSURE_PROGRAM_MODE).getOrNull()
                ?.let { CameraStorage.u16(it, 0) }
            val ec = driver.getDeviceProp(Ptp.DPC_EXPOSURE_BIAS_COMPENSATION).getOrNull()
                ?.let { CameraStorage.u16(it, 0).toShort().toInt() }
            val exposureCompWritable = driver.getDevicePropDesc(Ptp.DPC_EXPOSURE_BIAS_COMPENSATION).getOrNull()
                ?.let { it.size >= 5 && (it[4].toInt() and 0xff) == 1 } == true
            val wb = driver.getDeviceProp(Ptp.DPC_WHITE_BALANCE).getOrNull()
                ?.let { CameraStorage.u16(it, 0) }
            val pc = driver.getDeviceProp(Ptp.DPC_NIKON_ACTIVE_PICCTRL_ITEM).getOrNull()
                ?.let { CameraStorage.u16(it, 0) }
            val shutter = driver.getDeviceProp(Ptp.DPC_NIKON_EXPOSURE_TIME).getOrNull()?.let { d ->
                if (d.size >= 4) {
                    val packed = CameraStorage.u32(d, 0)
                    val num = (packed shr 16) and 0xFFFF
                    val den = packed and 0xFFFF
                    if (num in 1..0xFFFF && den in 1..0xFFFF) num.toFloat() / den.toFloat() else null
                } else null
            }
            val battery = driver.getDeviceProp(Ptp.DPC_BATTERY_LEVEL).getOrNull()
                ?.let { if (it.isEmpty()) -1 else (it[0].toInt() and 0xFF) }
            if (revisionAtStart != exposureRevision || sessionAtStart != exposureSession || _ui.value.exposureSyncing || !driver.isOpen) return@launch
            _ui.value = _ui.value.copy(
                iso = (iso ?: _ui.value.iso).coerceIn(32, 409600),
                aperture = fnum?.let { it / 100f } ?: _ui.value.aperture,
                apertureValues = apertureCapability?.values.orEmpty(),
                apertureRangeReady = apertureCapability?.values?.size?.let { it > 1 } == true,
                apertureWritable = apertureCapability?.writable == true,
                shutterSeconds = shutter ?: _ui.value.shutterSeconds,
                exposureComp = ec?.let { it / 1000f } ?: _ui.value.exposureComp,
                exposureCompWritable = exposureCompWritable,
                exposureProgram = if (prog != null && prog in 1..4) prog else _ui.value.exposureProgram,
                whiteBalance = if (wb != null) wb else _ui.value.whiteBalance,
                picCtrlItem = if (pc != null && pc in 1..8) pc else _ui.value.picCtrlItem,
                paramsLoaded = true,
                batteryPercent = battery ?: _ui.value.batteryPercent,
            )
            HexLog.info(
                "参数读回",
                "ISO=${iso ?: "-"} F=${fnum?.let { it / 100f } ?: "-"} 程序=${prog ?: "-"} " +
                    "EC=${ec ?: "-"} WB=0x%04X PC=%d".format(wb ?: 0, pc ?: 0),
            )
            if (burstProbeSession != sessionAtStart && driver.isOpen && !_ui.value.capturing) {
                burstProbeSession = sessionAtStart
                driver.probeNativeBurstReadOnly()
            }
        }
    }

    /** Refreshes the lens-specific F-number menu while the monitor is visible. */
    fun refreshApertureCapabilities() {
        if (!driver.isOpen || phone.cameraBusy || pendingExposure.isNotEmpty() || _ui.value.exposureSyncing || apertureCapabilityJob?.isActive == true) return
        val session = exposureSession
        apertureCapabilityJob = viewModelScope.launch {
            val capability = driver.getDevicePropDesc(Ptp.DPC_F_NUMBER).getOrNull()
                ?.let { CameraAperturePolicy.parseDescriptor(it) }
            if (session != exposureSession || !driver.isOpen) return@launch
            if (capability == null) {
                _ui.update { if (it.isConnected) it.copy(apertureRangeReady = false, apertureWritable = false) else it }
                return@launch
            }
            _ui.update { state ->
                if (!state.isConnected) state else state.copy(
                    apertureValues = capability.values,
                    apertureRangeReady = capability.values.size > 1,
                    apertureWritable = capability.writable,
                    aperture = capability.current ?: state.aperture,
                )
            }
        }
    }

    private fun cameraCaptureBusy(): Boolean {
        if (!_ui.value.cameraMenuBusy && !_ui.value.touchFocusBusy && !phone.cameraBusy && _ui.value.downloadingHandle==0L && !_ui.value.capturing && !_ui.value.pairingBusy) return false
        _ui.update { it.copy(toast = "请等拍摄/快门收尾完成后再操作相机") }
        return true
    }

    private fun exposureTransactionBusy(): Boolean {
        val busy = _ui.value.cameraMenuBusy || _ui.value.touchFocusBusy || _ui.value.exposureSyncing || pendingExposure.isNotEmpty()
        if (busy) _ui.update { it.copy(toast = if(it.cameraMenuBusy)"快捷菜单正在同步，请稍候"else if(it.touchFocusBusy)"正在触点对焦，请稍候"else"曝光参数正在自动同步，请稍候") }
        return busy
    }

    /** Queue only the edited property; never cancel a PTP transaction to coalesce slider input. */
    private fun queueExposure(code: Int, value: Double) {
        if(_ui.value.cameraMenuBusy || _ui.value.touchFocusBusy || phone.cameraBusy || _ui.value.downloadingHandle!=0L)return
        if (_ui.value.capturing || _ui.value.movieRecording || _ui.value.movieBusy || _ui.value.pairingBusy) return
        if (!driver.isOpen || !value.isFinite()) return
        if (code == Ptp.DPC_EXPOSURE_BIAS_COMPENSATION && !_ui.value.exposureCompWritable) {
            _ui.update { it.copy(exposureStatus = "相机未声明曝光补偿可写", toast = "曝光补偿当前不可调") }
            return
        }
        if (code in _ui.value.readOnlyProps && (code != Ptp.DPC_NIKON_EXPOSURE_TIME || Ptp.DPC_EXPOSURE_TIME in _ui.value.readOnlyProps)) {
            _ui.update { it.copy(exposureStatus = "该参数由机身控制，请在相机上调整", toast = "机身声明该参数只读") }
            return
        }
        val revision = ++exposureRevision
        exposureVersions[code] = revision
        pendingExposure[code] = ExposureEdit(code, value, revision)
        _ui.update { exposureValue(it, code, value).copy(exposureStatus = "等待自动同步…") }
        if (exposureWriteJob?.isActive == true) return
        val session = exposureSession
        exposureWriteJob = viewModelScope.launch {
            val self = kotlinx.coroutines.currentCoroutineContext()[Job]
            try {
                while (pendingExposure.isNotEmpty() && driver.isOpen && exposureSession == session) {
                    delay(250) // Bounded coalescing: latest value per property, at most one serial worker.
                    val busy = _ui.value
                    if (busy.capturing || busy.writing || busy.movieRecording || busy.movieBusy || busy.probingWrite || busy.galleryLoading || busy.galleryPreviewBusy || busy.downloadingHandle != 0L) continue
                    val edits = pendingExposure.values.sortedBy { if (it.code == Ptp.DPC_EXPOSURE_PROGRAM_MODE) 0 else 1 }
                    pendingExposure.clear()
                    val wasLive = busy.liveViewActive
                    _ui.update { it.copy(exposureSyncing = true, exposureStatus = "自动同步中…") }
                    val failures = ArrayList<String>()
                    try {
                        if (wasLive) stopLiveViewAwait()
                        driver.autoResumeLiveView = false
                        for (edit in edits) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (!driver.isOpen || exposureSession != session) break
                            if (exposureVersions[edit.code] != edit.revision) continue
                            var error: String? = null
                            try {
                                val mode = readExposureValue(Ptp.DPC_EXPOSURE_PROGRAM_MODE)?.toInt()
                                if (mode != null && edit.code != Ptp.DPC_EXPOSURE_PROGRAM_MODE && Ptp.DPC_EXPOSURE_PROGRAM_MODE !in pendingExposure) {
                                    _ui.update { it.copy(exposureProgram = mode) }
                                }
                                val blocked = (edit.code == Ptp.DPC_F_NUMBER && mode != 1 && mode != 3) ||
                                    (edit.code == Ptp.DPC_NIKON_EXPOSURE_TIME && mode != 1 && mode != 4)
                                val desc = driver.getDevicePropDesc(edit.code).getOrNull()
                                val apertureCapability = if (edit.code == Ptp.DPC_F_NUMBER) desc?.let { CameraAperturePolicy.parseDescriptor(it) } else null
                                if (edit.code == Ptp.DPC_F_NUMBER && apertureCapability != null) {
                                    _ui.update { it.copy(apertureValues = apertureCapability.values,
                                        apertureRangeReady = apertureCapability.values.size > 1,
                                        apertureWritable = apertureCapability.writable) }
                                }
                                val apertureUnavailable = edit.code == Ptp.DPC_F_NUMBER &&
                                    (apertureCapability == null || apertureCapability.values.isEmpty() || !apertureCapability.writable)
                                val targetValue = if (edit.code == Ptp.DPC_F_NUMBER) {
                                    apertureCapability?.values?.minByOrNull { kotlin.math.abs(it.toDouble() - edit.value) }?.toDouble() ?: edit.value
                                } else edit.value
                                val primaryReadOnly = edit.code in _ui.value.readOnlyProps || (desc != null && desc.size >= 5 && desc[4].toInt() == 0)
                                val standardShutter = edit.code == Ptp.DPC_NIKON_EXPOSURE_TIME && primaryReadOnly &&
                                    driver.getDevicePropDesc(Ptp.DPC_EXPOSURE_TIME).getOrNull()?.let { it.size >= 5 && it[4].toInt() == 1 } == true
                                val readOnly = (primaryReadOnly && !standardShutter) || apertureUnavailable
                                val before = readExposureValue(edit.code, standardShutter)
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                if (blocked || readOnly) {
                                    error = if (apertureUnavailable) "光圈能力未确认或机身声明只读" else "${exposureName(edit.code)}由机身/当前档位控制"
                                } else if (before == null || !sameExposure(before, targetValue)) {
                                    val result = when (edit.code) {
                                        Ptp.DPC_ISO -> driver.setIso(edit.value.toInt())
                                        Ptp.DPC_F_NUMBER -> driver.setAperture(targetValue)
                                        Ptp.DPC_NIKON_EXPOSURE_TIME -> if (standardShutter) driver.setDeviceProp(Ptp.DPC_EXPOSURE_TIME,
                                            java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(Math.round(edit.value * 10_000).toInt()).array())
                                        else driver.setShutterSeconds(edit.value)
                                        Ptp.DPC_EXPOSURE_BIAS_COMPENSATION -> driver.setExposureCompensation(edit.value)
                                        Ptp.DPC_EXPOSURE_PROGRAM_MODE -> driver.setExposureProgram(edit.value.toInt())
                                        Ptp.DPC_WHITE_BALANCE -> driver.setWhiteBalance(edit.value.toInt())
                                        else -> driver.setActivePictureControl(edit.value.toInt())
                                    }
                                    error = result.exceptionOrNull()?.message
                                }
                                val actual = readExposureValue(edit.code, standardShutter)
                                if (actual == null) error = error ?: "${exposureName(edit.code)}未获得机身回读，尚未确认"
                                else {
                                    if (error == null && !sameExposure(actual, targetValue)) error = "${exposureName(edit.code)}机身未接受该值，已显示回读值"
                                    if (exposureSession == session && exposureVersions[edit.code] == edit.revision) {
                                        _ui.update { exposureValue(it, edit.code, actual) }
                                    }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "参数同步失败" }
                            if (error != null) failures += error!!
                            HexLog.info("参数自动同步", "0x%04X rev=%d target=%s %s".format(edit.code, edit.revision, edit.value, error ?: "回读确认"))
                        }
                    } finally {
                        driver.autoResumeLiveView = true
                        if (exposureSession == session) {
                            _ui.update { it.copy(exposureSyncing = false) }
                            if (wasLive && driver.isOpen && self?.isActive == true) startLiveView()
                        }
                    }
                    if (exposureSession == session && driver.isOpen) {
                        _ui.update { it.copy(
                            exposureStatus = if (pendingExposure.isNotEmpty()) "等待同步最新修改…" else if (failures.isEmpty()) "已自动同步 · 机身回读确认" else failures.joinToString("；"),
                            toast = if (failures.isEmpty()) it.toast else "参数同步失败：${failures.joinToString("；")}",
                        ) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { if (exposureWriteJob === self) exposureWriteJob = null }
        }
    }

    private fun sameExposure(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) <= maxOf(0.00001, kotlin.math.abs(b) * 0.005)
    private fun exposureName(code: Int): String = when (code) {
        Ptp.DPC_ISO -> "ISO"; Ptp.DPC_F_NUMBER -> "光圈"; Ptp.DPC_NIKON_EXPOSURE_TIME -> "快门"
        Ptp.DPC_EXPOSURE_BIAS_COMPENSATION -> "曝光补偿"; Ptp.DPC_EXPOSURE_PROGRAM_MODE -> "曝光程序"
        Ptp.DPC_WHITE_BALANCE -> "白平衡"; else -> "优化校准"
    }
    private fun exposureValue(state: UiState, code: Int, value: Double): UiState = when (code) {
        Ptp.DPC_ISO -> state.copy(iso = value.toInt())
        Ptp.DPC_F_NUMBER -> state.copy(aperture = value.toFloat())
        Ptp.DPC_NIKON_EXPOSURE_TIME -> state.copy(shutterSeconds = value.toFloat())
        Ptp.DPC_EXPOSURE_BIAS_COMPENSATION -> state.copy(exposureComp = value.toFloat())
        Ptp.DPC_EXPOSURE_PROGRAM_MODE -> state.copy(exposureProgram = value.toInt())
        Ptp.DPC_WHITE_BALANCE -> state.copy(whiteBalance = value.toInt())
        else -> state.copy(picCtrlItem = value.toInt())
    }
    private suspend fun readExposureValue(code: Int, standardShutter: Boolean = false): Double? {
        val bytes = driver.getDeviceProp(if (standardShutter) Ptp.DPC_EXPOSURE_TIME else code).getOrNull()
        return when (code) {
            Ptp.DPC_F_NUMBER -> leU16(bytes)?.div(100.0)
            Ptp.DPC_EXPOSURE_BIAS_COMPENSATION -> leI16(bytes)?.div(1000.0)
            Ptp.DPC_NIKON_EXPOSURE_TIME -> {
                if (standardShutter && bytes != null && bytes.size >= 4) return CameraStorage.u32(bytes, 0) / 10000.0
                if (bytes != null && bytes.size >= 4) {
                    val packed = CameraStorage.u32(bytes, 0)
                    val num = (packed shr 16) and 65535; val den = packed and 65535
                    if (num > 0 && den > 0) num.toDouble() / den else null
                } else null
            }
            else -> leU16(bytes)?.toDouble()
        }
    }

    private fun programShort(p: Int): String = when (p) {
        1 -> "M"
        2 -> "P"
        3 -> "A"
        4 -> "S"
        else -> "当前"
    }

    /** 属性值小端 u16 解码（读回比对用，读不到返回 null 表示未知）。 */
    private fun leU16(b: ByteArray?): Int? =
        if (b != null && b.size >= 2) (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) else null

    /** 属性值小端 i16 解码（曝光补偿是带符号的）。 */
    private fun leI16(b: ByteArray?): Int? {
        if (b == null || b.size < 2) return null
        val raw = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private fun fmtOneDecimal(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)

    private fun fmtSigned(v: Float): String = (if (v > 0f) "+" else "") + fmtOneDecimal(v)

    fun setIso(v: Int) = queueExposure(Ptp.DPC_ISO, v.toDouble())
    fun setAperture(v: Float) = queueExposure(Ptp.DPC_F_NUMBER, v.toDouble())
    fun setShutterSeconds(v: Float) = queueExposure(Ptp.DPC_NIKON_EXPOSURE_TIME, v.toDouble())
    fun setExposureComp(v: Float) = queueExposure(Ptp.DPC_EXPOSURE_BIAS_COMPENSATION, v.toDouble())
    fun setExposureProgram(v: Int) = queueExposure(Ptp.DPC_EXPOSURE_PROGRAM_MODE, v.toDouble())
    fun applyExposureProgram(v: Int) = setExposureProgram(v)
    fun setWhiteBalance(v: Int) = queueExposure(Ptp.DPC_WHITE_BALANCE, v.toDouble())
    fun setPicCtrlItem(v: Int) = queueExposure(Ptp.DPC_NIKON_ACTIVE_PICCTRL_ITEM, v.toDouble())

    // ---------------- 拍摄 / 录像 ----------------

    /**
     * 从机身存储卡直接取最新一张，显示到「刚拍的这一张」。
     *
     * 为什么需要这个按钮：真机日志（ptp-20261027(2).log）里整段会话 **0 次 0x9207**，
     * 说明用户是用相机机身上的快门按键拍的 —— 那种照片不会走 `CaptureToSdram`，
     * App 侧的 [capturePhoto] 根本不会被触发，`lastCapture` 永远是空。
     * 之前把它当成「回传失败」来修，方向就错了：照片在卡里，只是没人去取。
     *
     * 做法：停取景 → 读每个存储卷的对象句柄表尾部几个 → 取最新且能下载的那个 → 解码 → 恢复取景。
     */
    fun loadNewestFromCamera() {
        if(phone.cameraBusy)return
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (phone.cameraBusy || _ui.value.downloadingHandle!=0L || _ui.value.pairingBusy || _ui.value.capturing || _ui.value.movieRecording || _ui.value.movieBusy) return
        viewModelScope.launch {
            val wasLive = _ui.value.liveViewActive
            var wasCancelled = false
            _ui.value = _ui.value.copy(capturing = true, captureMsg = "正在从机身取最新一张…")
            try {
                // Avoid overlapping this card scan/download with album enumeration or thumbnails.
                galleryLoadJob?.join()
                thumbnailJob?.join()
                lastCaptureDirty = true
                if (wasLive) stopLiveViewAwait()
                driver.waitUntilDeviceReady(4000)
                val r = driver.downloadNewestObject(tries = 4, timeoutMs = 60_000)
                val data = r.getOrNull()
                if (data == null) {
                    _ui.value = _ui.value.copy(
                        captureMsg = "机身存储卡里没取到可用的照片：${r.exceptionOrNull()?.message ?: "空数据"}",
                        toast = if (driver.isOpen) "没取到照片" else "相机连接已断开",
                    )
                    return@launch
                }
                val bmp = withContext(Dispatchers.Default) { decodeJpegLoose(data) }
                if (bmp == null) {
                    _ui.value = _ui.value.copy(
                        captureMsg = "取回 %.1f MB 但不是可解码的 JPEG（前 4 字节 %s）".format(
                            data.size / 1024.0 / 1024.0,
                            data.take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                        ),
                        toast = "解码失败",
                    )
                } else {
                    _ui.value = _ui.value.copy(
                        lastCapture = bmp,
                        lastCaptureHandle = -1L,
                        captureMsg = "已取回机身最新一张 · %d×%d · %.1f MB".format(
                            bmp.width, bmp.height, data.size / 1024.0 / 1024.0,
                        ),
                        toast = "已取回机身最新一张",
                    )
                    HexLog.info("拍摄", "从存储卡取回最新一张：${data.size}B，解码 ${bmp.width}×${bmp.height}")
                }
            } catch (cancelled: CancellationException) {
                wasCancelled = true
                throw cancelled
            } catch (error: Exception) {
                val connected = driver.isOpen
                if (!connected) runCatching { driver.close() }
                HexLog.error("机身最新照片回传异常", "${error.javaClass.simpleName}: ${error.message}")
                _ui.value = _ui.value.copy(
                    liveViewActive = if (connected) _ui.value.liveViewActive else false,
                    liveViewBitmap = if (connected) _ui.value.liveViewBitmap else null,
                    captureMsg = if (connected) "取回中断：${error.message ?: error.javaClass.simpleName}"
                        else "相机连接已断开，取回中断：${error.message ?: error.javaClass.simpleName}",
                    toast = if (connected) "取回中断" else "相机连接已断开",
                )
            } finally {
                if (!wasCancelled && wasLive && driver.isOpen) {
                    runCatching { driver.waitUntilDeviceReady(3000) }
                    if (driver.isOpen) startLiveView()
                }
                if (_ui.value.capturing) _ui.value = _ui.value.copy(capturing = false)
                if (!wasCancelled) refreshGalleryAfterCaptureIfVisible()
            }
        }
    }

    /** 宽松解码：先当整块 JPEG 解，失败再从二进制里找 `FF D8` 起点重解一次。 */
    private fun decodeJpegLoose(data: ByteArray): Bitmap? {
        runCatching { android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) }
            .getOrNull()?.let { return it }
        return runCatching {
            var start = -1
            var i = 0
            while (i < data.size - 1) {
                if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) {
                    start = i; break
                }
                i++
            }
            if (start < 0) return@runCatching null
            android.graphics.BitmapFactory.decodeByteArray(data, start, data.size - start)
        }.getOrNull()
    }

    /** 拍一张到相机卡：停取景 → 0x9207(card) → 确认新增卡文件 → 回传预览。 */
    /** Called once by the long-press recognizer; release only flips a flag, never starts a second job. */
    fun startNativeBurst() {
        if(phone.cameraBusy || _ui.value.downloadingHandle!=0L)return
        if (exposureTransactionBusy() || !driver.isOpen || _ui.value.capturing || _ui.value.writing ||
            _ui.value.probingWrite || _ui.value.galleryPreviewBusy || _ui.value.downloadingHandle != 0L ||
            _ui.value.movieRecording || _ui.value.movieBusy || _ui.value.settingsOpen || _ui.value.consoleOpen ||
            _ui.value.selectedTab != 0 || nativeBurstJob != null) return
        burstRequest.begin()
        val wasLive = _ui.value.liveViewActive
        _ui.update { it.copy(capturing = true, burstActive = true, captureMsg = "原生高速连拍准备中 · 松开取消") }
        val burstJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                galleryLoadJob?.join()
                thumbnailJob?.join()
                previewJob?.join()
                if (!burstRequest.held || !driver.isOpen) return@launch
                if (wasLive) stopLiveViewAwait()
                if (!burstRequest.held || !driver.isOpen) return@launch
                lastCaptureDirty = true
                val result = nativeBurst.run(held = { burstRequest.held }, started = { limit ->
                    _ui.update { it.copy(captureMsg = "原生高速连拍中 · 松开停止 · 机身本组上限 $limit 张") }
                })
                if (result.started && (!result.stopAccepted || !result.ready || !result.restored))
                    burstRequest.release(resumeLiveView = false)
                _ui.update { it.copy(captureMsg = when {
                    !result.started -> "已取消连拍，未触发快门"
                    !result.stopAccepted || !result.ready || !result.restored -> "连拍停止/参数恢复尚未确认，请检查机身"
                    result.addedFiles != null -> "原生连拍已停止 · 新增 ${result.addedFiles} 个卡文件 · 相册查看"
                    else -> "原生连拍已停止 · 照片在机身卡内，可到相册查看"
                }, toast = if (!result.started) "已取消连拍" else if (result.stopAccepted && result.ready && result.restored)
                    "原生连拍已停止" else "请检查机身连拍状态") }
            } catch (cancelled: CancellationException) {
                burstRequest.release(resumeLiveView = false)
                throw cancelled
            } catch (error: Exception) {
                burstRequest.release(resumeLiveView = false)
                HexLog.error("NativeBurst", "FAILED ${error.message}")
                _ui.update { it.copy(captureMsg = "原生连拍未完成：${error.message}", toast = "连拍未完成，请检查机身") }
            } finally {
                burstRequest.release()
                nativeBurstJob = null
                _ui.update { it.copy(capturing = false, burstActive = false) }
                if (wasLive && burstRequest.mayResumeLiveView && !userRequestedDisconnect &&
                    driver.isOpen && _ui.value.selectedTab == 0) startLiveView()
                refreshGalleryAfterCaptureIfVisible()
            }
        }
        nativeBurstJob = burstJob
        burstJob.start()
    }

    fun stopNativeBurst(resumeLiveView: Boolean = true) {
        burstRequest.release(resumeLiveView)
        if (_ui.value.burstActive) _ui.update { it.copy(captureMsg = "正在释放机身快门并恢复参数…") }
    }

    fun capturePhoto() {
        if(phone.cameraBusy || _ui.value.downloadingHandle!=0L)return
        if (_ui.value.movieRecording || _ui.value.movieBusy || nativeBurstJob != null) return
        if (exposureTransactionBusy()) return
        if (!driver.isOpen || _ui.value.capturing) {
            if (!driver.isOpen) _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        val wasLive = _ui.value.liveViewActive
        viewModelScope.launch {
            _ui.value = _ui.value.copy(capturing = true, captureMsg = "正在拍摄…")
            try {
                // The album's PTP list/thumbnail jobs share one camera session with capture.
                // Marking capturing first makes the thumbnail loop stop; then drain its current
                // bounded embedded-JPEG read before issuing the shutter operation.
                galleryLoadJob?.join()
                thumbnailJob?.join()
                capturePhotoToCard(wasLive)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val connected = driver.isOpen
                HexLog.error("拍摄异常", "${error.javaClass.simpleName}: ${error.message}")
                if (!connected) runCatching { driver.close() }
                _ui.value = _ui.value.copy(
                    liveViewActive = if (connected) _ui.value.liveViewActive else false,
                    liveViewBitmap = if (connected) _ui.value.liveViewBitmap else null,
                    captureMsg = if (connected) "拍摄中断：${error.message ?: error.javaClass.simpleName}"
                        else "相机连接已断开，拍摄中断：${error.message ?: error.javaClass.simpleName}",
                    toast = if (connected) "拍摄中断" else "相机连接已断开",
                )
                if (connected && wasLive) {
                    runCatching { driver.waitUntilDeviceReady(4000) }
                    startLiveView()
                }
                refreshGalleryAfterCaptureIfVisible()
            } finally {
                if (_ui.value.capturing) _ui.value = _ui.value.copy(capturing = false)
            }
        }
    }

    private suspend fun capturePhotoToCard(wasLive: Boolean) {
        val t0 = System.currentTimeMillis()
        if (wasLive) stopLiveViewAwait()
        nativeBurst.ensureSingleShot()
        val tStop = System.currentTimeMillis()
        _ui.value = _ui.value.copy(captureMsg = "等待机身就绪并拍摄到相机卡…")

        // Even if the card-handle confirmation times out, refresh the next album visit
        // instead of leaving a possibly-written capture hidden behind a stale list.
        lastCaptureDirty = true
        val handleR = driver.captureToCard()
        val tShot = System.currentTimeMillis()
        HexLog.info("拍摄计时", "停取景 ${tStop - t0} ms；0x9207(card) 返回 ${tShot - tStop} ms")
        val handle = handleR.getOrElse {
            HexLog.error("拍摄未确认", it.message ?: "相机卡未出现新文件")
            _ui.value = _ui.value.copy(captureMsg = "未确认写入相机卡：${it.message}", toast = "拍摄未确认")
            if (wasLive && driver.isOpen) {
                runCatching { driver.waitUntilDeviceReady(4000) }
                startLiveView()
            }
            refreshGalleryAfterCaptureIfVisible()
            return
        }

        lastCaptureDirty = true
        _ui.value = _ui.value.copy(captureMsg = "已确认相机卡新增文件，正在回传预览…")
        val dl = driver.downloadCardObject(handle, timeoutMs = 60_000)
        val data = dl.getOrNull()
        HexLog.info("拍摄计时", "相机卡回传阶段 ${System.currentTimeMillis() - tShot} ms（句柄 0x%08X）".format(handle))
        if (data == null || data.isEmpty()) {
            val connected = driver.isOpen
            if (!connected) runCatching { driver.close() }
            _ui.value = _ui.value.copy(
                captureMsg = "已在相机卡发现新文件（0x%08X），但回传失败：%s".format(
                    handle, dl.exceptionOrNull()?.message ?: "空数据",
                ),
                toast = if (connected) "相机卡已拍摄，回传失败" else "相机卡已拍摄，连接已断开",
            )
        } else {
            // JPEG remains the preferred preview; for NEF-only captures decode the embedded JPEG.
            val bmp = withContext(Dispatchers.Default) { decodeJpegLoose(data) }
            if (bmp == null) {
                _ui.value = _ui.value.copy(
                    captureMsg = "照片已写入相机卡，回传 %.1f MB 但无法解码预览（前 4 字节 %s）".format(
                        data.size / 1024.0 / 1024.0,
                        data.take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                    ),
                    toast = "已写入相机卡，预览解码失败",
                )
                HexLog.warn("拍摄", "卡内句柄 0x%08X 回传 ${data.size}B，但无可解码 JPEG".format(handle))
            } else {
                _ui.value = _ui.value.copy(
                    lastCapture = bmp,
                    lastCaptureHandle = handle,
                    captureMsg = "拍摄完成 · 已写入相机卡 · %d×%d · %.1f MB".format(
                        bmp.width, bmp.height, data.size / 1024.0 / 1024.0,
                    ),
                    toast = "拍摄完成并已写入相机卡",
                )
                HexLog.info("拍摄", "相机卡句柄 0x%08X 回传 ${data.size}B，解码 ${bmp.width}×${bmp.height}".format(handle))
                if (_ui.value.settings.autoSaveCapture) saveLastCaptureToPhone()
            }
        }

        HexLog.info("拍摄计时", "拍摄全流程 ${System.currentTimeMillis() - t0} ms（取景恢复前）")
        if (wasLive && driver.isOpen) {
            driver.waitUntilDeviceReady(4000)
            startLiveView()
        }
        refreshGalleryAfterCaptureIfVisible()
    }

    private fun refreshGalleryAfterCaptureIfVisible() {
        if (_ui.value.selectedTab == 2 && driver.isOpen && !_ui.value.galleryLoading) {
            _ui.value = _ui.value.copy(capturing = false)
            refreshGallery()
        }
    }

    /** 把上一次拍摄的照片存进手机相册（MediaStore，无需存储权限）。 */    fun saveLastCaptureToPhone() {
        val bmp = _ui.value.lastCapture
        if (bmp == null) {
            _ui.value = _ui.value.copy(toast = "还没有可保存的照片")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val name = "Zbox_${System.currentTimeMillis()}.jpg"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/Zbox",
                    )
                }
                val uri = getApplication<Application>().contentResolver
                    .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            _ui.value = _ui.value.copy(toast = if (ok) "已保存到相册/Pictures/Zbox" else "保存失败")
        }
    }

    private var movieRestoreLiveView = false

    fun toggleMovieRecording() {
        if(phone.cameraBusy)return
        val state = _ui.value
        if (state.capturing || state.movieBusy || state.writing || state.probingWrite ||
            state.galleryLoading || state.galleryPreviewBusy || state.downloadingHandle != 0L) return
        if (exposureTransactionBusy()) return
        if (!driver.isOpen) {
            _ui.update { it.copy(toast = "请先连接 Z30") }; return
        }
        val wasLive = state.liveViewActive
        if (!state.movieRecording) movieRestoreLiveView = wasLive
        _ui.update { it.copy(movieBusy = true, captureMsg = if (state.movieRecording) "正在停止录像…" else "正在准备录像模式…") }
        viewModelScope.launch {
            try {
                if (liveViewJob != null) {
                    if (state.movieRecording) {
                        val frameJob = liveViewJob
                        liveViewJob = null
                        _ui.update { it.copy(liveViewActive = false, liveViewBitmap = null) }
                        frameJob?.cancelAndJoin()
                    } else stopLiveViewAwait()
                }
                val result = if (state.movieRecording) driver.endMovieRecording() else driver.startMovieRecording()
                val recording = driver.movieRecordingMayBeActive
                _ui.update { it.copy(movieRecording = recording, captureMsg = when {
                    result.isSuccess && recording -> "录像中…"
                    result.isSuccess -> "录像已停止"
                    recording -> "录像状态未确认，请检查机身或点击停止：${result.exceptionOrNull()?.message}"
                    else -> "录像失败：${result.exceptionOrNull()?.message}"
                }) }
                // Resume frame reads only after the mode/start/stop transaction completes.

            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _ui.update { it.copy(movieRecording = driver.movieRecordingMayBeActive, captureMsg = "录像操作失败：${e.message}") }
            } finally { _ui.update { it.copy(movieBusy = false) } }
            if ((_ui.value.movieRecording || movieRestoreLiveView) && driver.isOpen) startLiveView()
        }
    }

    private fun cameraMenuBlocked():Boolean{
        val s=_ui.value
        return !driver.isOpen || s.cameraMenuBusy || s.touchFocusBusy || s.capturing || s.burstActive || s.pairingBusy || s.movieBusy || s.movieRecording || s.writing || s.probingWrite || s.exposureSyncing || pendingExposure.isNotEmpty() || phone.cameraBusy || s.downloadingHandle!=0L || s.galleryLoading || s.galleryPreviewBusy
    }
    fun refreshCameraMenu(){
        if(cameraMenuBlocked()){_ui.update{it.copy(cameraMenuMessage="请先连接相机，并等当前相机操作结束")};return}
        val token=driver.burstConnectionToken
        _ui.update{it.copy(cameraMenuBusy=true,cameraMenuEntries=com.zbox.ai.core.CameraIMenuPolicy.definitions.map{d->CameraMenuEntry(d,reason="读取中")},cameraMenuMessage="正在读取机身菜单能力")}
        viewModelScope.launch{
            try{val entries=readCameraMenu(token);if(token === driver.burstConnectionToken)_ui.update{it.copy(cameraMenuEntries=entries,cameraMenuMessage="菜单已更新")}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(e:Exception){_ui.update{it.copy(cameraMenuMessage="菜单读取未完成：${e.message}")}}
            finally{_ui.update{it.copy(cameraMenuBusy=false)}}
        }
    }
    private suspend fun readCameraMenu(token:Any?):List<CameraMenuEntry>{
        val policy=com.zbox.ai.core.CameraIMenuPolicy
        return policy.definitions.map{d->
            check(token!=null && token === driver.burstConnectionToken && driver.isOpen){"相机会话改变"}
            if(d.readCode==0)CameraMenuEntry(d,reason="连接状态只读")
            else{
                val r=driver.getDevicePropDesc(d.readCode,2000).getOrNull()?.let{policy.descriptor(it,d.readCode)}
                if(r==null)CameraMenuEntry(d,reason="未开放")
                else{val w=if(d.writeCode==d.readCode)r else driver.getDevicePropDesc(d.writeCode,2000).getOrNull()?.let{policy.descriptor(it,d.writeCode)}
                    CameraMenuEntry(d,r.current,w?.takeIf{it.type==r.type},reason=if(w?.writable==true)"机身允许"else"机身只读")}
            }
        }
    }
    fun applyCameraMenu(id:String,value:com.zbox.ai.core.CameraIMenuPolicy.Value):Boolean{
        if(cameraMenuBlocked())return false
        val entry=_ui.value.cameraMenuEntries.firstOrNull{it.definition.id==id}?:return false
        if(!entry.editable || com.zbox.ai.core.CameraIMenuPolicy.encode(entry.descriptor?:return false,value)==null)return false
        val token=driver.burstConnectionToken;val wasLive=_ui.value.liveViewActive;val epoch=menuResumeEpoch
        _ui.update{it.copy(cameraMenuBusy=true,cameraMenuMessage="正在修改并等待机身读回")}
        viewModelScope.launch{
            try{
                if(wasLive)stopLiveViewAwait()
                check(token!=null && token === driver.burstConnectionToken){"相机会话改变"}
                val result=driver.setCameraMenuOnce(id,value,token)
                val entries=readCameraMenu(token)
                check(token === driver.burstConnectionToken && driver.isOpen){"读回时相机会话改变"}
                _ui.update{it.copy(cameraMenuEntries=entries,cameraMenuMessage=if(result.isSuccess)"已修改 · 机身读回确认"else "修改未确认：${result.exceptionOrNull()?.message}")}
            }catch(cancelled:CancellationException){throw cancelled}
            catch(e:Exception){_ui.update{it.copy(cameraMenuMessage="修改未确认：${e.message}")}}
            finally{
                _ui.update{it.copy(cameraMenuBusy=false)}
                if(wasLive && epoch==menuResumeEpoch && token === driver.burstConnectionToken && driver.isOpen && _ui.value.selectedTab==0 && !_ui.value.settingsOpen && !_ui.value.consoleOpen)startLiveView()
            }
        }
        return true
    }

    /** Real point relocation + AF. Acknowledgement is not optical focus confirmation. */
    fun cancelFocusModeChange(){_ui.update{it.copy(pendingFocusModePoint=null)}}
    fun focusAtFrame(x:Float,y:Float,allowSingleAreaChange:Boolean=false){
        val current=_ui.value
        if(current.touchFocusBusy || phone.cameraBusy || current.downloadingHandle!=0L || current.pairingBusy || current.capturing || current.burstActive || current.movieBusy || current.movieRecording || exposureTransactionBusy() || !current.liveViewActive || !driver.isOpen)return
        val frame=current.liveViewBitmap?:return
        if(0x9205 !in current.deviceInfo.supportedOperations || Ptp.OP_NIKON_AF_DRIVE !in current.deviceInfo.supportedOperations){_ui.update{it.copy(toast="相机未通告触点对焦能力")};return}
        val geometry=com.zbox.ai.core.TouchFocusPolicy.geometry(driver.lastLiveViewHeader,frame.width,frame.height)
        if(geometry==null){_ui.update{it.copy(toast="当前取景坐标或放大状态未验证，未移动对焦点")};return}
        val point=com.zbox.ai.core.TouchFocusPolicy.target(geometry,x,y)?:return
        val token=driver.burstConnectionToken
        _ui.update{it.copy(touchFocusBusy=true,touchFocusRequest=android.os.SystemClock.elapsedRealtime(),touchFocusX=x,touchFocusY=y,touchFocusMoved=false,touchFocusMessage="正在移动对焦区域")}
        viewModelScope.launch{
            try{
                val modeBytes=driver.getDevicePropDesc(0xd05d).getOrThrow()
                val mode=com.zbox.ai.core.TouchFocusPolicy.afModeDescriptor(modeBytes)?:error("无法核实机身AF区域模式，未移动对焦点")
                if(mode.current==0x8011){
                    if(!allowSingleAreaChange){_ui.update{it.copy(pendingFocusModePoint=x to y,touchFocusMessage="需要确认切换为单点AF")};return@launch}
                    check(current.deviceInfo.model.replace(" ","").equals("Z30",ignoreCase=true)){"此机型未验证AF模式切换，请在机身选择单点AF"}
                    check(mode.writable && 0x8010 in mode.values){"机身未开放单点AF设置"}
                    driver.setTouchAfMode(0x8010,token){_ui.value.liveViewActive}.getOrThrow()
                }
                _ui.update{it.copy(pendingFocusModePoint=null)}
                val fresh=com.zbox.ai.core.TouchFocusPolicy.geometry(driver.lastLiveViewHeader,frame.width,frame.height)?:error("切换后取景坐标未核实")
                check(fresh.width==geometry.width && fresh.height==geometry.height){"取景坐标已改变，请重新点击"}
                driver.changeAfArea(point.first,point.second,token).getOrThrow()
                var confirmed=false
                repeat(3){
                    if(!confirmed){driver.getLiveViewFrame().getOrThrow();val read=com.zbox.ai.core.TouchFocusPolicy.geometry(driver.lastLiveViewHeader,frame.width,frame.height)
                        confirmed=read!=null && kotlin.math.abs(read.afX-point.first)<=read.afWidth/2 && kotlin.math.abs(read.afY-point.second)<=read.afHeight/2
                        if(!confirmed)delay(80)
                    }
                }
                check(confirmed){"相机应答但未确认对焦区域到达点击位置；未触发AF，请检查机身AF区域模式"}
                val af=driver.touchAfDrive(token)
                _ui.update{it.copy(touchFocusMoved=true,touchFocusMessage=if(af.isSuccess)"对焦区域已确认，AF已触发；不代表已确认合焦"else"对焦区域已确认，${af.exceptionOrNull()?.message}",toast=if(af.isSuccess)"对焦区域已确认，AF已触发"else"对焦区域已确认，${af.exceptionOrNull()?.message}")}

            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){_ui.update{it.copy(touchFocusMoved=false,touchFocusMessage=error.message?:"触点对焦失败",toast=error.message?:"触点对焦失败")}}
            finally{_ui.update{it.copy(touchFocusBusy=false)}}
        }
    }

    fun triggerAutoFocusNow() {
        if(phone.cameraBusy)return
        if (phone.cameraBusy || _ui.value.downloadingHandle!=0L || _ui.value.pairingBusy || _ui.value.capturing || _ui.value.movieRecording || _ui.value.movieBusy) return
        if (exposureTransactionBusy()) return
        if (!driver.isOpen) return
        viewModelScope.launch {
            val r = driver.afDrive()
            _ui.value = _ui.value.copy(
                toast = if (r.isSuccess) "AF 已触发" else "AF 失败：${r.exceptionOrNull()?.message}",
            )
        }
    }

    fun moveFocus(step: Int) {
        if (phone.cameraBusy || _ui.value.downloadingHandle!=0L || _ui.value.pairingBusy || _ui.value.capturing || _ui.value.movieRecording || _ui.value.movieBusy) return
        if (exposureTransactionBusy()) return
        if (!driver.isOpen) return
        viewModelScope.launch { driver.manualFocus(step) }
    }

    /** 把刚拍的这张照片直接当成 AI 还原的参考图。 */
    fun useLastCaptureAsReference() {
        val bmp = _ui.value.lastCapture
        if (bmp == null) {
            _ui.value = _ui.value.copy(toast = "还没有可用的照片")
            return
        }
        viewModelScope.launch {
            val feats = withContext(Dispatchers.Default) { ImageFeatureExtractor.extract(bmp) }
            _ui.value = _ui.value.copy(
                referenceBitmap = bmp,
                features = feats,
                selectedTab = 0,
                toast = "已设为参考图，去「AI 炼丹炉」还原影调",
            )
            renderPreview()
        }
    }

    // ---------------- 相册（照片管理） ----------------

    fun refreshGallery() = readGalleryPage(reset = true)

    private fun galleryItemsForFilter(items: List<CameraStorage.Item>, filter: Int): List<CameraStorage.Item> {
        val browsable = CameraStorage.jpegBrowseItems(items)
        return when (filter) {
            1 -> browsable.filter { !it.isVideo }
            2 -> browsable.filter { it.isVideo }
            4 -> browsable.filter { phone.mark(it) == 1 }
            5 -> browsable.filter { phone.mark(it) == 2 }
            else -> browsable
        }
    }

    fun loadMoreGallery() {
        if (phone.cameraBusy) return
        if (!_ui.value.galleryHasMore || _ui.value.galleryLoading || _ui.value.capturing) return
        if (galleryIndexComplete) {
            val filtered = galleryItemsForFilter(galleryIndex, _ui.value.galleryFilter)
            val end = minOf(filtered.size, _ui.value.galleryItems.size + galleryBatchSize())
            _ui.update { it.copy(
                galleryItems = filtered.take(end),
                galleryTotal = filtered.size,
                galleryHasMore = end < filtered.size,
                downloadMsg = "已显示 $end / ${filtered.size} 项 · 缩略图按可见范围逐张读取",
            ) }
        } else readGalleryPage(reset = false)
    }

    private fun galleryBatchSize(): Int = _ui.value.settings.galleryLimit.coerceIn(18, 200)

    private fun readGalleryPage(reset: Boolean) {
        if (!driver.isOpen) { _ui.update { it.copy(toast = "请先连接 Z30") }; return }
        if (_ui.value.capturing) { lastCaptureDirty = true; return }
        if (_ui.value.galleryLoading) return
        _ui.update { it.copy(galleryLoading = true, galleryProgress = 0, downloadMsg = "正在读取相册索引…") }
        galleryLoadJob = viewModelScope.launch {
            try {
                thumbnailDemand = emptyList()
                thumbnailJob?.cancelAndJoin()
                val count = if (reset) galleryBatchSize() else galleryIndex.size + galleryBatchSize()
                val result = withContext(Dispatchers.IO) {
                    driver.listStorage(limit = count, completeStaIndex = true) { n ->
                        _ui.update { it.copy(galleryProgress = n) }
                    }
                }
                result.onSuccess { snap ->
                    if (!driver.isOpen) return@onSuccess
                    galleryIndex = snap.items
                    galleryIndexComplete = driver.hasCompleteStaAlbumIndex
                    lastCaptureDirty = false
                    val filtered = galleryItemsForFilter(snap.items, _ui.value.galleryFilter)
                    val visible = filtered.take(count)
                    _ui.update { it.copy(
                        galleryVolumes = snap.volumes,
                        galleryItems = visible,
                        galleryLoading = false,
                        galleryTotal = if (galleryIndexComplete) filtered.size else 0,
                        galleryHasMore = if (galleryIndexComplete) visible.size < filtered.size else snap.items.size >= count,
                        thumbnails = if (reset) emptyMap() else it.thumbnails,
                        downloadMsg = "已显示 ${visible.size}" +
                            (if (galleryIndexComplete) " / ${filtered.size}" else "") + " 项 · 向下滚动继续加载",
                    ) }
                    loadThumbnails(visible.take(18))
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _ui.update { it.copy(galleryLoading = false, downloadMsg = "读取失败：${error.message}", toast = "相册读取失败") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                HexLog.error("相册读取异常", error.message ?: error.javaClass.simpleName)
                _ui.update { it.copy(downloadMsg = "读取失败：${error.message}") }
            } finally {
                _ui.update { it.copy(galleryLoading = false) }
            }
        }
    }

    /** Only demand visible cells plus a small look-ahead; scrolling replaces queued work. */
    fun requestGalleryThumbnails(handles: List<Long>) {
        if (_ui.value.selectedTab != 2 || !driver.isOpen) return
        val wanted = handles.toSet()
        loadThumbnails(_ui.value.galleryItems.filter { it.handle in wanted })
    }

    private fun loadThumbnails(items: List<CameraStorage.Item>) {
        thumbnailDemand = items
        if (thumbnailJob?.isActive == true || items.isEmpty()) return
        thumbnailJob = viewModelScope.launch {
            val attempted = HashSet<Long>()
            try {
                while (isActive && driver.isOpen && !_ui.value.capturing) {
                    if (phone.cameraBusy || _ui.value.galleryPreviewBusy || _ui.value.downloadingHandle != 0L) { delay(100); continue }
                    val item = thumbnailDemand.firstOrNull {
                        it.handle !in attempted && !_ui.value.thumbnails.containsKey(it.handle)
                    } ?: break
                    attempted += item.handle
                    val (bytes, number) = withContext(Dispatchers.IO) {
                        val data = driver.getThumb(item.handle).getOrNull()
                        data to (if (data != null && data.isNotEmpty()) driver.readFileNumber(item.handle) else null)
                    }
                    if (!isActive || !driver.isOpen || _ui.value.capturing) break
                    if (bytes == null || bytes.isEmpty()) continue
                    val base = number?.let { "DSC_%04d".format(it) }
                    _ui.update { state ->
                        val cache = LinkedHashMap(state.thumbnails)
                        cache[item.handle] = bytes
                        while (cache.size > 72) {
                            val obsolete = cache.keys.firstOrNull { h -> thumbnailDemand.none { it.handle == h } } ?: cache.keys.first()
                            cache.remove(obsolete)
                        }
                        state.copy(thumbnails = cache, galleryItems = state.galleryItems.map { g ->
                            if (g.handle != item.handle || base == null) g else g.copy(
                                filename = "$base.${g.filename.substringAfterLast('.')}",
                                companionFilename = g.companionFilename?.let { "$base.${it.substringAfterLast('.')}" },
                            )
                        })
                    }
                    if (base != null) galleryIndex = galleryIndex.map { g ->
                        if (g.handle != item.handle) g else g.copy(
                            filename = "$base.${g.filename.substringAfterLast('.')}",
                            companionFilename = g.companionFilename?.let { "$base.${it.substringAfterLast('.')}" },
                        )
                    }
                    // Yield after each image rather than decoding/fetching the whole album in one burst.
                    delay(16)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                HexLog.error("相册缩略图中断", error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** On-demand full-resolution read for an item; decode a downsampled embedded JPEG for preview. */
    fun loadGalleryPreview(item: CameraStorage.Item) {
        if(phone.cameraBusy)return
        if (!driver.isOpen) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(galleryPreviewHandle = item.handle, galleryPreviewBusy = true, galleryPreviewBitmap = null, galleryPreviewError = "正在从相机读取 ${item.filename}…")
            val result = driver.getObject(item.handle, timeoutMs = 300_000)
            val bytes = result.getOrNull()
            if (bytes == null) {
                _ui.value = _ui.value.copy(galleryPreviewBusy = false, galleryPreviewError = "读取原图失败：${result.exceptionOrNull()?.message}")
                return@launch
            }
            val bitmap = withContext(Dispatchers.Default) { decodeJpegPreview(bytes) }
            _ui.value = _ui.value.copy(
                galleryPreviewBusy = false,
                galleryPreviewBitmap = bitmap,
                galleryPreviewError = if (bitmap != null) "已读取原图（${bytes.size / 1024} KB）" else "已下载 ${bytes.size / 1024} KB，但文件中未找到可解码的 JPEG 预览",
            )
        }
    }

    private fun decodeJpegPreview(data: ByteArray): Bitmap? = runCatching {
        var start = -1
        for (i in 0 until data.size - 1) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) { start = i; break }
        }
        if (start < 0) return@runCatching null
        val length = data.size - start
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(data, start, length, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeByteArray(data, start, length, opts)
    }.getOrNull()

    /** 下载一张到手机相册。 */
    /** 下载到手机相册。asRaw=true 时下载同一张照片的 NEF 原片（需要 companionHandle）。 */
    fun downloadItem(item: CameraStorage.Item, asRaw: Boolean = false) {
        phone.enqueue(item,asRaw)
    }

    private fun saveToPhoneGallery(name: String, isVideo: Boolean, bytes: ByteArray): Boolean = runCatching {
        val cr = getApplication<Application>().contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(
                android.provider.MediaStore.MediaColumns.MIME_TYPE,
                if (isVideo) (if (name.lowercase().endsWith(".mov")) "video/quicktime" else "video/mp4")
                else if (name.lowercase().endsWith(".nef")) "image/x-nikon-nef"
                else "image/jpeg",
            )
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isVideo) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES) +
                    "/Zbox",
            )
        }
        val collection = if (isVideo) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = cr.insert(collection, values) ?: return false
        cr.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        true
    }.getOrDefault(false)

    fun deleteItem(item: CameraStorage.Item) {
        if(phone.cameraBusy)return
        if (!driver.isOpen) return
        viewModelScope.launch {
            val r = driver.deleteObject(item.handle)
            if (r.isSuccess) {
                galleryIndex = galleryIndex.filterNot { it.handle == item.handle }
                thumbnailDemand = thumbnailDemand.filterNot { it.handle == item.handle }
                _ui.value = _ui.value.copy(
                    galleryItems = _ui.value.galleryItems.filterNot { it.handle == item.handle },
                    galleryTotal = if (galleryIndexComplete) galleryIndex.size else _ui.value.galleryTotal,
                    thumbnails = _ui.value.thumbnails - item.handle,
                    downloadMsg = "已删除 ${item.filename}",
                    toast = "已从机身删除",
                )
            } else {
                _ui.value = _ui.value.copy(toast = "删除失败：${r.exceptionOrNull()?.message}")
            }
        }
    }

    fun setGalleryFilter(f: Int) {
        _ui.update { it.copy(galleryFilter = f) }
        if (galleryIndex.isEmpty()) return
        val filtered = galleryItemsForFilter(galleryIndex, f)
        val count = galleryBatchSize()
        val visible = filtered.take(count)
        _ui.update { it.copy(
            galleryItems = visible,
            galleryTotal = if (galleryIndexComplete) filtered.size else 0,
            galleryHasMore = if (galleryIndexComplete) visible.size < filtered.size else galleryIndex.size >= count,
            downloadMsg = "已显示 ${visible.size}" +
                (if (galleryIndexComplete) " / ${filtered.size}" else "") + " 项 · 向下滚动继续加载",
        ) }
        loadThumbnails(visible.take(18))
    }

    // ---------------- 配方快传 ----------------

    /** 生成当前配方的快传码。 */
    fun generateShareCode() {
        val code = RecipeShareCode.encode(_ui.value.recipe)
        _ui.value = _ui.value.copy(shareCode = code, toast = "快传码已生成（${code.length} 字符）")
        HexLog.info("配方快传", "已生成 ${code.length} 字符")
    }

    fun setShareCodeInput(text: String) {
        _ui.value = _ui.value.copy(shareCodeInput = text)
    }

    /** 复制快传码到系统剪贴板。 */
    fun copyShareCodeToClipboard() {
        val code = _ui.value.shareCode
        if (code.isBlank()) return
        val cm = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Zbox 配方快传码", code))
        _ui.value = _ui.value.copy(toast = "快传码已复制，粘贴到微信/评论区发给朋友")
    }

    /** 调系统分享面板把快传码发出去。 */
    fun shareShareCode() {
        val code = _ui.value.shareCode
        if (code.isBlank()) return
        val r = _ui.value.recipe
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                android.content.Intent.EXTRA_TEXT,
                "【Zbox 配方】${r.name}\n${r.styleAnalysis}\n\n$code",
            )
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Zbox 优化校准配方")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            getApplication<Application>().startActivity(
                android.content.Intent.createChooser(send, "分享配方快传码").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { _ui.value = _ui.value.copy(toast = "没有可用的分享目标") }
    }

    /** 从粘贴的文本还原配方。 */
    fun applyShareCode() {
        val r = RecipeShareCode.decode(_ui.value.shareCodeInput)
        if (r == null) {
            _ui.value = _ui.value.copy(toast = "无法识别这段快传码")
            return
        }
        _ui.value = _ui.value.copy(recipe = r, lastAnalysis = r.styleAnalysis, toast = "已导入配方：${r.name}")
        renderPreview()
    }

    fun consumeToast() {
        if (_ui.value.toast.isNotEmpty()) _ui.value = _ui.value.copy(toast = "")
    }

    override fun onCleared() {
        phone.stopSession()
        clearComposition(close = true)
        getApplication<Application>().stopService(Intent(getApplication(), CameraConnectionService::class.java))
        stopNativeBurst(resumeLiveView = false)
        val finishingBurst = nativeBurstJob
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.withTimeoutOrNull(16000) { finishingBurst?.join() }
            driver.close()
        }
        super.onCleared()
    }

    /** 判断曲线是否接近线性（用于 UI 提示）。 */
    fun isCurveLinear(r: Recipe): Boolean {
        val lut = r.lut
        for (i in 0..255 step 8) if (abs(lut[i] - i) > 4) return false
        return true
    }
}
