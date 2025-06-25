package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
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
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

@Serializable
data class Home(
    val totalStations: Long,
    val stations: List<Station>,
) {

    @Serializable
    data class Station(
        val id: Long,
        val name: String,
        val description: String,
        @SerialName("newlogo")
        val newLogo: String,
    )
}

@Serializable
data class Station(
    val hits: List<Hit>,
) {

    @Serializable
    data class Hit(
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

class TestExtension : ExtensionClient, HomeFeedClient, TrackClient, RadioClient, SearchFeedClient {
    override suspend fun onExtensionSelected() {}

    override val settingItems
        get() = listOf(
            SettingSwitch(
                "Use HLS Stream",
                "use_hls",
                "Whether to use HLS or shoutcast streams if both are available for particular station",
                usdHLS
            )
        )

    private val usdHLS get() = setting.getBoolean("use_hls") ?: true

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val homeLink = "https://api.iheart.com/api/v1/catalog/searchStation?&keywords=%22%22&maxRows=5000"
    private val searchLink = "https://api.iheart.com/api/v1/catalog/searchStation?&keywords="
    private val stationLink = "https://api.iheart.com/api/v2/content/liveStations/"

    private val client by lazy { OkHttpClient.Builder().build() }
    private suspend fun call(url: String) = client.newCall(
        Request.Builder().url(url).build()
    ).await().body.string()

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private inline fun <reified T> String.toData() =
        runCatching { json.decodeFromString<T>(this) }.getOrElse {
            throw IllegalStateException("Failed to parse JSON: $this", it)
        }

    private suspend fun getStream(id: String): String {
        val streams = call(stationLink + id).toData<Station>().hits[0].streams
        return if (usdHLS)
            streams.hls ?: streams.shoutcast ?: streams.pls ?: ""
        else
            streams.shoutcast ?: streams.hls ?: streams.pls ?: ""
    }

    private suspend fun String.toShelf(): List<Shelf> =
        this.toData<Home>().stations.filter { result ->
            getStream(result.id.toString()).isNotEmpty() }.map { result ->
                Track(
                    id = result.id.toString(),
                    title = result.name,
                    subtitle = result.description,
                    description = result.description,
                    cover = result.newLogo.toImageHolder(),
                    streamables = listOf(
                        Streamable.server(
                            getStream(result.id.toString()),
                            0
                        )
                    )
                ).toMediaItem().toShelf()
            }

    override fun getHomeFeed(tab: Tab?) = PagedData.Single {
        val apiResponse = call(tab!!.id)
        apiResponse.toShelf()
    }.toFeed()

    override suspend fun getHomeTabs(): List<Tab> {
        return listOf(Tab(title = "Stations", id = homeLink))
    }

    override fun getShelves(track: Track): PagedData<Shelf> {
        return PagedData.empty()
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val response = call(streamable.id).split("\n")
        return Streamable.Media.Server(
            response.map { it.toSource() },
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

    override fun searchFeed(query: String, tab: Tab?) =
        PagedData.Single {
            call("${searchLink}\"$query\"").toShelf()
        }.toFeed()

    override suspend fun searchTabs(query: String) = emptyList<Tab>()
}