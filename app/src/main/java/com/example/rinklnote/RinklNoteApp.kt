package com.example.rinklnote

import android.app.Application
import com.example.rinklnote.data.db.AppDatabase
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

class RinklNoteApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: BillRepository by lazy { BillRepositoryImpl(database) }
    val tokenManager: TokenManager by lazy { TokenManager(this) }
    val apiService: ApiService by lazy { RetrofitClient.create(tokenManager) }
    val syncManager: SyncManager by lazy { SyncManager(apiService, tokenManager, database.billDao(), database.billTemplateDao(), database.budgetDao()) }

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
