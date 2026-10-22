package com.ferbotz.billanta.di

import com.ferbotz.billanta.config.AppIdentity
import com.ferbotz.billanta.config.RemoteConfigManager
import com.ferbotz.billanta.core.AlwaysOnlineConnectivity
import com.ferbotz.billanta.core.ConnectivityObserver
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.KeyValueStore
import com.ferbotz.billanta.core.SystemClock
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.data.api.AuthApi
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.BillantaApiConfig
import com.ferbotz.billanta.data.api.createAuthedHttpClient
import com.ferbotz.billanta.data.api.createAuthlessHttpClient
import com.ferbotz.billanta.data.db.DatabaseDriverFactory
import com.ferbotz.billanta.data.db.createBillantaDb
import com.ferbotz.billanta.data.local.CustomerValuesLocal
import com.ferbotz.billanta.data.local.FormsCacheLocal
import com.ferbotz.billanta.data.local.InvoiceValuesLocal
import com.ferbotz.billanta.data.local.ProductValuesLocal
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.data.local.RememberedValuesLocal
import com.ferbotz.billanta.data.local.SingletonValuesLocal
import com.ferbotz.billanta.data.local.SyncMetaLocal
import com.ferbotz.billanta.data.local.TemplateLocalDataSource
import com.ferbotz.billanta.data.repo.CustomerRepository
import com.ferbotz.billanta.data.repo.FormsRepository
import com.ferbotz.billanta.data.repo.InvoiceRepository
import com.ferbotz.billanta.data.repo.ProductRepository
import com.ferbotz.billanta.data.repo.ProfileRepository
import com.ferbotz.billanta.resources.Res
import com.ferbotz.billanta.data.repo.MediaRepository
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
    /**
     * Hands a URL to the system — a `mailto:`, a store listing — leaving the app. Provided by the
     * platform factory.
     */
    val openUrl: (String) -> Unit = {},
    /**
     * Opens a web page in an in-app browser tab (Custom Tabs / SFSafariViewController), for the
     * legal pages (BE-015): the user reads them without being sent out of the app.
     */
    val openInAppBrowser: (String) -> Unit = openUrl,
    /** Platform share sheet for exported invoices. */
    shareService: FileShareService = NoopFileShareService,
    /** Platform, version and build of this app — the device id is filled in here, per install. */
    platformIdentity: AppIdentity = AppIdentity.Unknown,
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
    private val profileLocal = ProfileLocalDataSource(db, ioDispatcher)
    private val templateLocal = TemplateLocalDataSource(db, ioDispatcher)
    private val syncMetaLocal = SyncMetaLocal(db, ioDispatcher)

    // BE-021: every record is a values map (field id → value).
    private val singletonValues = SingletonValuesLocal(db, ioDispatcher)
    private val customerValues = CustomerValuesLocal(db, ioDispatcher)
    private val productValues = ProductValuesLocal(db, ioDispatcher)
    private val invoiceValues = InvoiceValuesLocal(db, ioDispatcher)
    private val rememberedValues = RememberedValuesLocal(db, ioDispatcher)
    private val formsCache = FormsCacheLocal(db, ioDispatcher)

    // ---- identity ----
    /**
     * Who this install is, as the targeting headers tell the server (BE-014). The device id is
     * made once and kept for the life of the install; signing out removes only the token keys, so
     * it survives that, and it is read by nothing but `/config`'s experiment bucketing.
     */
    val identity: AppIdentity = platformIdentity.copy(
        deviceId = keyValueStore.getString(KEY_DEVICE_ID)
            ?: randomUuid().also { keyValueStore.putString(KEY_DEVICE_ID, it) },
    )

    // ---- network ----
    private val tokenStore = TokenStore(keyValueStore)
    private val authlessClient = createAuthlessHttpClient(config, identity)
    private val authApi = AuthApi(authlessClient)
    private val tokenManager = TokenManager(tokenStore, authApi, clock)
    // Typed explicitly: the API reads its page cap from remoteConfig, which fetches through the API.
    val api: BillantaApi = BillantaApi(
        client = createAuthedHttpClient(config, tokenManager, identity),
        maxPageSize = { remoteConfig.current.limits.maxPageSize },
    )

    /**
     * The behaviour switches from `GET /config` (BE-014). Restored from this install's cache as it
     * is constructed, so everything below reads a real document from the first frame.
     */
    val remoteConfig: RemoteConfigManager = RemoteConfigManager(
        fetch = { api.getConfigBody() },
        store = keyValueStore,
        scope = appScope,
        connectivity = connectivity,
    )

    /** Fetches logo/signature/QR bitmaps so a shared file never has a half-loaded image. */
    val invoiceImageLoader = InvoiceImageLoader(authlessClient)

    // ---- session ----
    private val wipeLocalData: suspend () -> Unit = {
        profileLocal.clearAccount()
        syncMetaLocal.clearAll()
        invoiceValues.clearAll()
        customerValues.clearAll()
        productValues.clearAll()
        singletonValues.clearAll()
        // Remembered values are this user's typing (a PO number, say): they go with the rest.
        rememberedValues.clearAll()
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
        invoiceValues.markAllDirty()
        customerValues.markAllDirty()
        productValues.markAllDirty()
        singletonValues.markAllDirty()
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
        singletons = singletonValues,
        customers = customerValues,
        products = productValues,
        invoices = invoiceValues,
        syncMeta = syncMetaLocal,
        connectivity = connectivity,
        clock = clock,
        onAccountVanished = { userManager.onAccountVanished() },
        syncEnabled = { remoteConfig.current.effectiveFeatures.cloudSync },
        limits = { remoteConfig.current.limits },
    )

    // ---- repositories ----
    private val onMutation: () -> Unit = { syncManager.requestSync() }

    val invoiceRepository = InvoiceRepository(
        invoices = invoiceValues,
        customers = customerValues,
        products = productValues,
        singletons = singletonValues,
        remembered = rememberedValues,
        clock = clock,
        onLocalMutation = onMutation,
    )
    // Drafts copy the profile and their customer, so they follow every change to either.
    val profileRepository = ProfileRepository(singletonValues, clock, onMutation) {
        invoiceRepository.refreshDrafts()
    }
    val customerRepository = CustomerRepository(customerValues, clock, onMutation) {
        invoiceRepository.refreshDrafts()
    }
    val productRepository = ProductRepository(productValues, clock, onMutation)
    val templateRepository = TemplateRepository(templateLocal, api, clock)
    val mediaRepository = MediaRepository(api)

    /** The four entity forms (BE-021): the bundled copy, then the cache, then `GET /forms`. */
    val formsRepository = FormsRepository(
        api = api,
        cache = formsCache,
        clock = clock,
        bundled = { Res.readBytes("files/forms.json").decodeToString() },
    )

    init {
        // Cold start: fetch the config first thing, without waiting on it — the cached document is
        // already in force, so nothing blocks on the network.
        remoteConfig.start()
        appScope.launch {
            userManager.restore()
            // Warm the template catalogue opportunistically; offline failure is fine.
            templateRepository.refreshCatalogue()
        }
        // The forms are public and needed signed out, so they load and revalidate on every cold start.
        appScope.launch {
            formsRepository.load()
            formsRepository.refresh()
        }
        // Sync switched back on (or maintenance over): push what piled up while it was off.
        appScope.launch {
            remoteConfig.document
                .map { it.config.effectiveFeatures.cloudSync }
                .distinctUntilChanged()
                .drop(1)
                .collect { enabled -> if (enabled) syncManager.requestSync(immediate = true) }
        }
    }

    private companion object {
        const val KEY_DEVICE_ID = "install.deviceId"
    }
}
