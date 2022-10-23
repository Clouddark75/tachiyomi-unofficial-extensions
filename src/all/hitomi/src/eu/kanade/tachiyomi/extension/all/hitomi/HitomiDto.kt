package eu.kanade.tachiyomi.extension.all.hitomi

import kotlinx.serialization.Serializable

@Serializable
data class HitomiGalleryDto(
    val title: String,
    val id: String,
    val artists: List<HitomiArtistDto>? = null,
    val parodys: List<HitomiParodyDto>? = null,
    val characters: List<HitomiCharacterDto>? = null,
    val tags: List<HitomiTagsDto>? = null,
    val files: List<HitomiFileDto> = emptyList(),
    val date: String,
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

@Serializable
data class HitomiTagsDto(
    val tag: String,
    val url: String,
)

@Serializable
data class HitomiCharacterDto(
    val character: String,
    val url: String,
)
