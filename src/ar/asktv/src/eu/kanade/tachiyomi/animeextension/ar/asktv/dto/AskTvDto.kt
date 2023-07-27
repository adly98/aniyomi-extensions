package eu.kanade.tachiyomi.animeextension.ar.asktv.dto

import kotlinx.serialization.Serializable

@Serializable
data class EpisodeData(
    val backUrl: String,
    val codeDaily: String,
    val postID: String,
    val servers: List<Server>,
    val type: String
)

@Serializable
data class Server(
    val id: String,
    val name: String
)
