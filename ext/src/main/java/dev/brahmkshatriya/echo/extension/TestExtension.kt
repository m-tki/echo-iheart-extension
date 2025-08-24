package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Feed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun getSettingItems() = listOf(
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

    private val searchLink = "https://api.iheart.com/api/v1/catalog/searchStation"
    private val stationLink = "https://api.iheart.com/api/v2/content/liveStations"
    private val genreLink = "https://api.iheart.com/api/v2/content/genre"

    private val client by lazy { OkHttpClient.Builder().build() }
    private suspend fun call(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(url).build()
        ).await().body.string()
    }

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private inline fun <reified T> String.toData() =
        runCatching { json.decodeFromString<T>(this) }.getOrElse {
            throw IllegalStateException("Failed to parse JSON: $this", it)
        }

    private fun isStreamAvailable(streams: Station.Hit.Stream) =
        streams.hls?.isNotEmpty() == true ||
                streams.shoutcast?.isNotEmpty() == true ||
                streams.pls?.isNotEmpty() == true

    private fun createStreamableServers(streams: Station.Hit.Stream): List<Streamable> =
        listOfNotNull(
            streams.pls?.takeIf { it.isNotEmpty() }?.let { it to "PLS" },
            streams.shoutcast?.takeIf { it.isNotEmpty() }?.let { it to "Shoutcast" },
            streams.hls?.takeIf { it.isNotEmpty() }?.let { it to "HLS" },
        ).mapIndexed { idx, stream ->
            Streamable.server(
                stream.first,
                idx,
                stream.second,
                mapOf("type" to stream.second.lowercase())
            )
        }

    private fun createTrack(station: Station.Hit) =
        Track(
            station.id.toString(),
            station.name,
            subtitle = station.description,
            description = station.description,
            cover = station.logo?.toImageHolder(),
            streamables = createStreamableServers(station.streams),
            isPlayable = if (isStreamAvailable(station.streams))
                Track.Playable.Yes else
                Track.Playable.No("No Supported Streams Found")
        ).toShelf()

    private fun String.toShelf(): List<Shelf> {
        return this.toData<Station>().hits.map {
            createTrack(it)
        }
    }

    private fun createCategory(id: Long, name: String) =
        Shelf.Category(
            id.toString(),
            name,
            PagedData.Single {
                call("$stationLink?genreId=$id&limit=5000").toShelf()
            }.toFeed()
        )

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val categories = if (defaultGenres) {
            call(genreLink).toData<Genre>().hits.map {
                createCategory(it.id, it.name)
            }
        }
        else {
            call("$stationLink?limit=5000").toData<StationGenre>().hits
                .flatMap { it.genres }
                .distinctBy { it.id }
                .map {
                    createCategory(it.id, it.name)
                }
        }
        return listOf(
            Shelf.Lists.Categories(
                "countries",
                "Countries",
                categories,
                type = Shelf.Lists.Type.Grid
            )
        ).toFeed()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

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

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val source = if (streamable.extras["type"] == "pls")
            parsePLS(streamable.id) else streamable.id
        val type = if (streamable.extras["type"] == "hls")
            Streamable.SourceType.HLS else Streamable.SourceType.Progressive
        return Streamable.Media.Server(
            listOf(source.toSource(type = type, isLive = true)),
            false
        )
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track
    override suspend fun loadTracks(radio: Radio): Feed<Track> = PagedData.empty<Track>().toFeed()
    override suspend fun loadRadio(radio: Radio): Radio  = Radio("", "")
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = Radio("", "")

    private suspend fun String.toSearchShelf(): List<Shelf> =
        this.toData<StationSearch>().stations.map { result ->
            val station = call("$stationLink/${result.id}").toData<Station>().hits[0]
            createTrack(station)
        }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> =
        call("$searchLink?keywords=\"$query\"").toSearchShelf().toFeed()
}
