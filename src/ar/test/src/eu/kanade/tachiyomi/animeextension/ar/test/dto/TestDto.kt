package eu.kanade.tachiyomi.animeextension.ar.test.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TuktukIframe(
    val props: Props,
)

@Serializable
data class Props(
    val streams: Streams
)

@Serializable
data class Streams(
    @SerialName("data")
    val `data`: List<Data>,
    val msg: String,
    val status: String,
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
    val symbol: String,
)
