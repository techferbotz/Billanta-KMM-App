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
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.data.local.SyncMetaLocal
import com.ferbotz.billanta.data.local.TemplateLocalDataSource
import com.ferbotz.billanta.data.repo.CompanyRepository
import com.ferbotz.billanta.data.repo.CustomerRepository
import com.ferbotz.billanta.data.repo.InvoiceRepository
import com.ferbotz.billanta.data.repo.MediaRepository
import com.ferbotz.billanta.data.repo.SettingsRepository
import com.ferbotz.billanta.data.repo.TemplateRepository
import com.ferbotz.billanta.data.sync.SyncManager
import com.ferbotz.billanta.session.SignInCoordinator
import com.ferbotz.billanta.session.TokenManager
import com.ferbotz.billanta.session.TokenStore
import com.ferbotz.billanta.session.UserManager
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
) {
    /** Small local prefs (dark mode, etc.) — same store the session uses. */
    val prefs: KeyValueStore = keyValueStore

    /** Platform sign-in UIs register their Google idToken provider here. */
    val signInCoordinator = SignInCoordinator()
    // ---- storage ----
    private val db = createBillantaDb(driverFactory)
    private val invoiceLocal = InvoiceLocalDataSource(db, ioDispatcher)
    private val customerLocal = CustomerLocalDataSource(db, ioDispatcher)
    private val profileLocal = ProfileLocalDataSource(db, ioDispatcher)
    private val templateLocal = TemplateLocalDataSource(db, ioDispatcher)
    private val syncMetaLocal = SyncMetaLocal(db, ioDispatcher)

    // ---- network ----
    private val tokenStore = TokenStore(keyValueStore)
    private val authApi = AuthApi(createAuthlessHttpClient(config))
    private val tokenManager = TokenManager(tokenStore, authApi, clock)
    val api = BillantaApi(createAuthedHttpClient(config, tokenManager))

    // ---- session ----
    private val wipeLocalData: suspend () -> Unit = {
        invoiceLocal.clearAll()
        customerLocal.clearAll()
        profileLocal.clearProfile()
        syncMetaLocal.clearAll()
    }

    val userManager = UserManager(
        authApi = authApi,
        api = api,
        tokenManager = tokenManager,
        profileLocal = profileLocal,
        keyValueStore = keyValueStore,
        wipeLocalData = wipeLocalData,
        clock = clock,
    )

    val syncManager = SyncManager(
        scope = appScope,
        api = api,
        authState = userManager.authState,
        invoiceLocal = invoiceLocal,
        customerLocal = customerLocal,
        profileLocal = profileLocal,
        syncMeta = syncMetaLocal,
        connectivity = connectivity,
        clock = clock,
    )

    // ---- repositories ----
    private val onMutation: () -> Unit = { syncManager.requestSync() }

    val invoiceRepository = InvoiceRepository(invoiceLocal, customerLocal, profileLocal, clock, onMutation)
    val customerRepository = CustomerRepository(customerLocal, clock, onMutation)
    val companyRepository = CompanyRepository(profileLocal, clock, onMutation)
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
