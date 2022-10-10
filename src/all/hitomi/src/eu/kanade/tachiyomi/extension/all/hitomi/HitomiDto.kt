package eu.kanade.tachiyomi.extension.all.hitomi

import kotlinx.serialization.Serializable

@Serializable
data class HitomiGalleryDto(
    val title: String,
    val id: String,
    val artists: List<HitomiArtistDto>? = null,
    val parodys: List<HitomiParodyDto>? = null,
    val files: List<HitomiFileDto> = emptyList(),
)

@Serializable
data class HitomiFileDto(
    val name: String,
    val hasavif: Int,
    val hash: String,
    val haswebp: Int,
)

@Serializable
data class HitomiArtistDto(
    val artist: String,
    val url: String,
)

@Serializable
data class HitomiParodyDto(
    val parody: String,
    val url: String,
)
