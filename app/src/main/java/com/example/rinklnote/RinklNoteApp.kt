package com.example.rinklnote

import android.app.Application
import com.example.rinklnote.data.db.AppDatabase
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.RetrofitClient
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.data.repository.BillRepositoryImpl
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.widget.RinklNoteAppWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 打开快速记账抽屉时的预填参数。`categoryId` 来自主屏小组件；其余字段来自
 * `rinklnote://add` 深链。全部字段可空：全空表示「打开抽屉并落到默认分类」。
 */
data class PendingQuickAdd(
    val amount: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val remark: String? = null,
    val billType: String? = null
)

/** 应用入口即服务定位器：无 DI 框架，所有单例（数据库/仓库/令牌/设置/网络/同步）在此按需 lazy 创建，
 * 供各 ViewModel 的 Factory 注入。 */
class RinklNoteApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    // onBillMutated：记账增改删后触发主屏小组件刷新（仓库为最低公共点，避免 ViewModel 持有 Context）。
    val repository: BillRepository by lazy {
        BillRepositoryImpl(database, onBillMutated = {
            // updateAll 为 suspend，需在协程内执行；应用级作用域随 App 生命周期存活。
            applicationScope.launch { RinklNoteAppWidget().updateAll(this@RinklNoteApp) }
        })
    }
    val tokenManager: TokenManager by lazy { TokenManager(this) }
    val settingsManager: SettingsManager by lazy { SettingsManager(this) }
    val apiService: ApiService by lazy { RetrofitClient.create(tokenManager) }
    val syncManager: SyncManager by lazy { SyncManager(apiService, tokenManager, database.billDao(), database.billTemplateDao(), database.budgetDao(), database.accountDao()) }

    // 主屏小组件点分类 / 深链 rinklnote://add → 欲预填快速记账抽屉的参数；
    // MainActivity 从 Intent extra / deep-link URI 装入，AppNavigation 消费后清空。
    private val _pendingQuickAdd = MutableStateFlow<PendingQuickAdd?>(null)
    val pendingQuickAdd: StateFlow<PendingQuickAdd?> = _pendingQuickAdd.asStateFlow()

    fun setPendingQuickAdd(pending: PendingQuickAdd?) {
        _pendingQuickAdd.value = pending
    }

    // 应用级作用域：只承载启动时的种子数据填充等一次性任务，App 结束时统一取消。
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 金额隐私：注入持久化回写 + 启动时从 DataStore 恢复，App 与小组件共用同一开关。
        BalancePrivacy.writer = { hidden ->
            applicationScope.launch { settingsManager.setBalanceHidden(hidden) }
        }
        applicationScope.launch {
            settingsManager.balanceHidden.collect { BalancePrivacy.restore(it) }
        }
        applicationScope.launch {
            repository.seedIfNeeded()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }
}
