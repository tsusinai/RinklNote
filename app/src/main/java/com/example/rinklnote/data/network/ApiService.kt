package com.example.rinklnote.data.network

import com.example.rinklnote.data.network.dto.*
import retrofit2.http.*

interface ApiService {
    @POST("api/auth/register")
    suspend fun register(@Body request: LoginRequest): LoginResponse

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/auth/bind-qq")
    suspend fun bindQQ(@Body request: BindQQRequest): MessageResponse

    @GET("api/bills/sync")
    suspend fun syncBills(@Query("after") after: Long? = null): SyncResponse
}
