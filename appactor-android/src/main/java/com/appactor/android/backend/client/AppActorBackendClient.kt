package com.appactor.android.backend.client

import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorExperimentAssignmentEnvelopeDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncResponseDTO
import com.appactor.android.backend.dto.toRestoreRequest
import com.appactor.android.backend.dto.toSyncResponse
import com.appactor.android.backend.dto.AppActorIdentifyRequestDTO
import com.appactor.android.backend.dto.AppActorLoginRequestDTO
import com.appactor.android.backend.dto.AppActorLoginResponseDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.backend.dto.AppActorRemoteConfigsEnvelopeDTO

internal interface AppActorBackendClient {
    suspend fun identify(request: AppActorIdentifyRequestDTO): AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO>

    suspend fun login(request: AppActorLoginRequestDTO): AppActorBackendHttpResponse<AppActorLoginResponseDTO>

    suspend fun getOfferings(eTag: String? = null): AppActorBackendHttpResponse<AppActorOfferingsEnvelopeDTO>

    suspend fun getCustomer(
        appUserId: String,
        eTag: String? = null,
    ): AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO>

    suspend fun getRemoteConfigs(
        appUserId: String?,
        appVersion: String?,
        country: String?,
        eTag: String? = null,
    ): AppActorBackendHttpResponse<AppActorRemoteConfigsEnvelopeDTO>

    suspend fun postExperimentAssignment(
        experimentKey: String,
        appUserId: String,
        appVersion: String?,
        country: String?,
    ): AppActorBackendHttpResponse<AppActorExperimentAssignmentEnvelopeDTO>

    suspend fun postGoogleReceipt(
        request: AppActorGoogleReceiptRequestDTO,
    ): AppActorBackendHttpResponse<AppActorGoogleReceiptResponseDTO>

    suspend fun postGoogleRestore(
        request: AppActorGoogleRestoreRequestDTO,
    ): AppActorBackendHttpResponse<AppActorGoogleRestoreResponseDTO>

    suspend fun postGoogleSync(
        request: AppActorGoogleSyncRequestDTO,
    ): AppActorBackendHttpResponse<AppActorGoogleSyncResponseDTO> {
        val restore = postGoogleRestore(request.toRestoreRequest())
        return AppActorBackendHttpResponse(
            body = restore.body?.toSyncResponse(),
            statusCode = restore.statusCode,
            requestId = restore.requestId,
            eTag = restore.eTag,
            isNotModified = restore.isNotModified,
            signatureHeaders = restore.signatureHeaders,
            signatureVerified = restore.signatureVerified,
        )
    }
}
