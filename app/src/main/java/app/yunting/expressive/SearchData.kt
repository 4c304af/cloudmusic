package app.yunting.expressive

internal enum class SearchKind(val label: String, val apiType: String) {
    Songs("歌曲", "1"),
    Artists("歌手", "100"),
    Albums("专辑", "10"),
}

internal data class SearchEntry(
    val title: String,
    val subtitle: String,
    val cover: String,
    val route: String,
)

internal data class SearchResultPage(
    val songs: List<Song> = emptyList(),
    val entries: List<SearchEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
