package eu.kanade.tachiyomi.animeextension.ar.test.dto

import kotlinx.serialization.Serializable

@Serializable
data class IframeResponse(
    val component: String,
    val props: Props,
    val url: String,
    val version: String
)
@Serializable
data class Props(
    val streams: Streams
)
@Serializable
data class Streams(
    val data: List<Data>,
    val msg: String,
    val status: String,
    val token: String
)
@Serializable
data class Data(
    val hashId: String,
    val label: String,
    val mirrors: List<Mirror>,
    val resolution: String,
    val size: Long
)
@Serializable
data class Mirror(
    val driver: String,
    val link: String,
)
