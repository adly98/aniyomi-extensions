package eu.kanade.tachiyomi.animeextension.ar.tuktuk.dto

import kotlinx.serialization.Serializable

@Serializable
data class IFrameResponse(
    val props: Props,
)

@Serializable
data class Props(
    val streams: Streams,
)

@Serializable
data class Streams(
    val data: List<DataX>,
    val msg: String,
    val status: String,
    val token: String,
)

@Serializable
data class DataX(
    val label: String,
    val mirrors: List<Mirror>,
    val size: Int,
)

@Serializable
data class Mirror(
    val driver: String,
    val link: String,
    val symbol: String,
)
