package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

@Serializable
data class Genre(
    val hits: List<Hit>,
) {

    @Serializable
    data class Hit(
        val id: Long,
        val name: String,
    )
}

@Serializable
data class StationGenre(
    val hits: List<Hit>,
) {

    @Serializable
    data class Hit(
        val genres: List<Genre>
    ) {

        @Serializable
        data class Genre(
            val id: Long,
            val name: String,
        )

    }
}

@Serializable
data class Station(
    val hits: List<Hit>,
) {

    @Serializable
    data class Hit(
        val id: Long,
        val name: String,
        val description: String,
        val logo: String? = null,
        val streams: Stream,
    ) {

        @Serializable
        data class Stream(
            @SerialName("secure_hls_stream")
            val hls: String? = null,
            @SerialName("secure_shoutcast_stream")
            val shoutcast: String? = null,
            @SerialName("secure_pls_stream")
            val pls: String? = null,
        )
    }
}

@Serializable
data class StationSearch(
    val stations: List<Station>,
) {

    @Serializable
    data class Station(
        val id: Long,
    )
}

class TestExtension : ExtensionClient, HomeFeedClient, TrackClient, RadioClient, SearchFeedClient {
    override suspend fun onExtensionSelected() {}

    override val settingItems
        get() = listOf(
            SettingSwitch(
                "Display Default Genres",
                "default_genres",
                "Whether to display only default genres on the home page or all available genres",
                defaultGenres
            )
        )

    private val defaultGenres get() = setting.getBoolean("default_genres") ?: true

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val searchLink = "https://api.iheart.com/api/v1/catalog/searchStation/"
    private val stationLink = "https://api.iheart.com/api/v2/content/liveStations/"
    private val genreLink = "https://api.iheart.com/api/v2/content/genre/"

    private val client by lazy { OkHttpClient.Builder().build() }
    private suspend fun call(url: String) = client.newCall(
        Request.Builder().url(url).build()
    ).await().body.string()

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private inline fun <reified T> String.toData() =
        runCatching { json.decodeFromString<T>(this) }.getOrElse {
            throw IllegalStateException("Failed to parse JSON: $this", it)
        }

    private suspend fun parsePLS(stream: String?): String {
        if (stream != null) {
            val content = call(stream)
            for (line in content.lines()) {
                if (line.startsWith("File1=")) {
                    return line.substring(6)
                }
            }
        }
        return ""
    }

    private fun createStreamableServers(streams: Station.Hit.Stream): List<Streamable> =
        listOfNotNull(
            streams.hls?.let { it to "HLS" },
            streams.shoutcast?.let { it to "Shoutcast" },
            streams.pls?.let { it to "PLS" }
        ).mapIndexed { idx, stream ->
            Streamable.server(
                stream.first,
                idx,
                stream.second,
                mapOf("type" to stream.second.lowercase())
            )
        }

    private fun String.toShelf(): List<Shelf> {
        return this.toData<Station>().hits.map {
            Track(
                id = it.id.toString(),
                title = it.name,
                subtitle = it.description,
                description = it.description,
                cover = it.logo?.toImageHolder(),
                streamables = createStreamableServers(it.streams)
            ).toMediaItem().toShelf()
        }
    }

    override fun getHomeFeed(tab: Tab?) = PagedData.Single {
        call("$stationLink?&genreId=${tab!!.id}&limit=5000").toShelf()
    }.toFeed()

    override suspend fun getHomeTabs(): List<Tab> {
        return if (defaultGenres) {
            call(genreLink).toData<Genre>().hits.map {
                Tab(title = it.name, id = it.id.toString())
            }
        }
        else {
            call("$stationLink?&limit=5000").toData<StationGenre>().hits
                .flatMap { it.genres }
                .distinctBy { it.id }
                .map {
                    Tab(title = it.name, id = it.id.toString())
                }
        }
    }

    override fun getShelves(track: Track): PagedData<Shelf> {
        return PagedData.empty()
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val source = if (streamable.extras["type"] == "pls")
            parsePLS(streamable.id) else streamable.id
        val type = if (streamable.extras["type"] == "hls")
            Streamable.SourceType.HLS else Streamable.SourceType.Progressive
        return Streamable.Media.Server(
            listOf(source.toSource(type = type)),
            false
        )
    }

    override suspend fun loadTrack(track: Track) = track
    override fun loadTracks(radio: Radio) = PagedData.empty<Track>()
    override suspend fun radio(track: Track, context: EchoMediaItem?) = Radio("", "")
    override suspend fun radio(album: Album) = throw ClientException.NotSupported("Album radio")
    override suspend fun radio(artist: Artist) = throw ClientException.NotSupported("Artist radio")
    override suspend fun radio(user: User) = throw ClientException.NotSupported("User radio")
    override suspend fun radio(playlist: Playlist) =
        throw ClientException.NotSupported("Playlist radio")

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}
    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        return emptyList()
    }

    private suspend fun String.toSearchShelf(): List<Shelf> =
        this.toData<StationSearch>().stations.map { result ->
            val station = call(stationLink + result.id).toData<Station>().hits[0]
            Track(
                id = station.id.toString(),
                title = station.name,
                subtitle = station.description,
                description = station.description,
                cover = station.logo?.toImageHolder(),
                streamables = createStreamableServers(station.streams)
            ).toMediaItem().toShelf()
        }

    override fun searchFeed(query: String, tab: Tab?) =
        PagedData.Single {
            call("$searchLink?&keywords=\"$query\"").toSearchShelf()
        }.toFeed()

    override suspend fun searchTabs(query: String) = emptyList<Tab>()
}
