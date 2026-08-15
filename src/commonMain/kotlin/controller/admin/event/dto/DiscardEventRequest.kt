package controller.admin.event.dto

import kotlinx.serialization.Serializable

@Serializable
data class DiscardEventRequest(val reason: String? = null)
