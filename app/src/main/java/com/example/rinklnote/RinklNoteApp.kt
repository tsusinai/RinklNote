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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 应用入口即服务定位器：无 DI 框架，所有单例（数据库/仓库/令牌/设置/网络/同步）在此按需 lazy 创建，
 * 供各 ViewModel 的 Factory 注入。 */
class RinklNoteApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: BillRepository by lazy { BillRepositoryImpl(database) }
    val tokenManager: TokenManager by lazy { TokenManager(this) }
    val settingsManager: SettingsManager by lazy { SettingsManager(this) }
    val apiService: ApiService by lazy { RetrofitClient.create(tokenManager) }
    val syncManager: SyncManager by lazy { SyncManager(apiService, tokenManager, database.billDao(), database.billTemplateDao(), database.budgetDao(), database.accountDao()) }

    // 应用级作用域：只承载启动时的种子数据填充等一次性任务，App 结束时统一取消。
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            repository.seedIfNeeded()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }
}
