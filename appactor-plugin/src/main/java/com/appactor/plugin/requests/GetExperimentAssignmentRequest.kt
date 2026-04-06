package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.ExperimentAssignmentSurrogate
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class GetExperimentAssignmentRequest private constructor(
    private val experimentKey: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        val assignment = AppActor.getExperimentAssignment(experimentKey)
        return if (assignment != null) {
            PluginResult.encoding(ExperimentAssignmentSurrogate.serializer(), ExperimentAssignmentSurrogate(assignment))
        } else {
            PluginResult.nullPayload
        }
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_experiment_assignment"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return GetExperimentAssignmentRequest(p.experimentKey)
        }

        @Serializable
        private data class Params(@SerialName("experiment_key") val experimentKey: String)
    }
}
