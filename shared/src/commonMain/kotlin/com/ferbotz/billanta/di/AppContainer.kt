package com.ferbotz.billanta.di

import com.ferbotz.billanta.core.AlwaysOnlineConnectivity
import com.ferbotz.billanta.core.ConnectivityObserver
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.KeyValueStore
import com.ferbotz.billanta.core.SystemClock
import com.ferbotz.billanta.data.api.AuthApi
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.BillantaApiConfig
import com.ferbotz.billanta.data.api.createAuthedHttpClient
import com.ferbotz.billanta.data.api.createAuthlessHttpClient
import com.ferbotz.billanta.data.db.DatabaseDriverFactory
import com.ferbotz.billanta.data.db.createBillantaDb
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProductLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.data.local.SyncMetaLocal
import com.ferbotz.billanta.data.local.TemplateLocalDataSource
import com.ferbotz.billanta.data.repo.CompanyRepository
import com.ferbotz.billanta.domain.model.toSnapshot
import com.ferbotz.billanta.data.repo.CustomerRepository
import com.ferbotz.billanta.data.repo.InvoiceRepository
import com.ferbotz.billanta.data.repo.MediaRepository
import com.ferbotz.billanta.data.repo.ProductRepository
import com.ferbotz.billanta.data.repo.SettingsRepository
import com.ferbotz.billanta.data.repo.TemplateRepository
import com.ferbotz.billanta.data.sync.SyncManager
import com.ferbotz.billanta.render.paint.InvoiceImageLoader
import com.ferbotz.billanta.media.ImagePickerCoordinator
import com.ferbotz.billanta.session.SignInCoordinator
import com.ferbotz.billanta.session.TokenManager
import com.ferbotz.billanta.session.TokenStore
import com.ferbotz.billanta.session.UserManager
import com.ferbotz.billanta.share.FileShareService
import com.ferbotz.billanta.share.InvoiceExporter
import com.ferbotz.billanta.share.NoopFileShareService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-wired dependency graph for the data layer — one instance per process, created by the
 * platform entry point (see `createAppContainer` in androidMain/iosMain).
 */
class AppContainer(
    driverFactory: DatabaseDriverFactory,
    keyValueStore: KeyValueStore,
    val config: BillantaApiConfig,
    val connectivity: ConnectivityObserver = AlwaysOnlineConnectivity,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    clock: EpochClock = SystemClock,
    /** Opens an external URL (terms/privacy pages) — provided by the platform factory. */
    val openUrl: (String) -> Unit = {},
    /** Platform share sheet for exported invoices. */
    shareService: FileShareService = NoopFileShareService,
) {
    /** The core deliverable: rendered invoice → PDF/PNG/JPEG → share sheet. */
    val invoiceExporter = InvoiceExporter(shareService)
    /** Small local prefs (dark mode, etc.) — same store the session uses. */
    val prefs: KeyValueStore = keyValueStore

    /** Platform sign-in UIs register their Google idToken provider here. */
    val signInCoordinator = SignInCoordinator()

    /** Set by the platform entry point; see ImagePickerCoordinator. */
    val imagePickerCoordinator = ImagePickerCoordinator()
    // ---- storage ----
    private val db = createBillantaDb(driverFactory)
    private val invoiceLocal = InvoiceLocalDataSource(db, ioDispatcher)
    private val customerLocal = CustomerLocalDataSource(db, ioDispatcher)
    private val profileLocal = ProfileLocalDataSource(db, ioDispatcher)
    private val templateLocal = TemplateLocalDataSource(db, ioDispatcher)
    private val productLocal = ProductLocalDataSource(db, ioDispatcher)
    private val syncMetaLocal = SyncMetaLocal(db, ioDispatcher)

    // ---- network ----
    private val tokenStore = TokenStore(keyValueStore)
    private val authlessClient = createAuthlessHttpClient(config)
    private val authApi = AuthApi(authlessClient)
    private val tokenManager = TokenManager(tokenStore, authApi, clock)
    val api = BillantaApi(createAuthedHttpClient(config, tokenManager))

    /** Fetches logo/signature/QR bitmaps so a shared file never has a half-loaded image. */
    val invoiceImageLoader = InvoiceImageLoader(authlessClient)

    // ---- session ----
    private val wipeLocalData: suspend () -> Unit = {
        invoiceLocal.clearAll()
        customerLocal.clearAll()
        productLocal.clearAll()
        profileLocal.clearProfile()
        syncMetaLocal.clearAll()
    }

    /**
     * Hands the existing local rows to a new server identity, rather than deleting them.
     *
     * Everything becomes pending push again and the sync cursors are dropped. Without the reset,
     * rows the *old* account had already synced would still be marked synced against ids the new
     * account has never heard of, and the pull's "deleted elsewhere" reconcile would delete them
     * locally — the same data loss by a slower route.
     */
    private val reownLocalData: suspend () -> Unit = {
        syncMetaLocal.clearAll()
        invoiceLocal.markAllDirty()
        customerLocal.markAllDirty()
        productLocal.markAllDirty()
        profileLocal.markProfileDirty()
    }

    val userManager = UserManager(
        authApi = authApi,
        api = api,
        tokenManager = tokenManager,
        profileLocal = profileLocal,
        keyValueStore = keyValueStore,
        wipeLocalData = wipeLocalData,
        reownLocalData = reownLocalData,
        clock = clock,
    )

    val syncManager = SyncManager(
        scope = appScope,
        api = api,
        authState = userManager.authState,
        invoiceLocal = invoiceLocal,
        customerLocal = customerLocal,
        productLocal = productLocal,
        profileLocal = profileLocal,
        syncMeta = syncMetaLocal,
        connectivity = connectivity,
        clock = clock,
        onAccountVanished = { userManager.onAccountVanished() },
    )

    // ---- repositories ----
    private val onMutation: () -> Unit = { syncManager.requestSync() }

    val invoiceRepository = InvoiceRepository(invoiceLocal, customerLocal, profileLocal, clock, onMutation)
    val customerRepository = CustomerRepository(customerLocal, clock, onMutation)
    val productRepository = ProductRepository(productLocal, clock, onMutation)
    val companyRepository = CompanyRepository(
        local = profileLocal,
        clock = clock,
        onLocalMutation = onMutation,
        onCompanyChanged = { invoiceRepository.restampCompanySnapshot(it.toSnapshot()) },
    )
    val settingsRepository = SettingsRepository(profileLocal, clock, onMutation)
    val templateRepository = TemplateRepository(templateLocal, api, clock)
    val mediaRepository = MediaRepository(api)

    init {
        appScope.launch {
            userManager.restore()
            // Warm the template catalogue opportunistically; offline failure is fine.
            templateRepository.refreshCatalogue()
        }
    }
}
