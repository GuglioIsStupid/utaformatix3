package core.io

import core.model.ExportNotification
import core.model.ExportResult
import core.model.Feature
import core.model.FeatureConfig
import core.model.Format
import core.model.ImportParams
import core.model.ImportWarning
import core.util.readText
import core.model.TimeSignature
import core.process.validateNotes
import core.model.contains
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.w3c.files.Blob
import org.w3c.files.File
import org.w3c.files.BlobPropertyBag

object Tlp {
    suspend fun parse(
        file: File,
        params: ImportParams,
    ): core.model.Project {
        val rawProjects =
            file
                .readText()
                .split("\u0000")
                .map { it.trim('\u0000') }
                .filter { it.isNotBlank() }
        val projects = rawProjects.map { jsonSerializer.decodeFromString(Project.serializer(), it) }
        val project = projects.maxBy { it.version }
        val warnings = mutableListOf<ImportWarning>()
        val timeSignatures =
            project.timeSignatures
                .map {
                    TimeSignature(
                        measurePosition = it.barIndex,
                        numerator = it.numerator,
                        denominator = it.denominator,
                    )
                }?.takeIf { it.isNotEmpty() } ?: listOf(core.model.TimeSignature.default).also {
                warnings.add(ImportWarning.TimeSignatureNotFound)
            }
        val tempos =
            project.tempos
                .map {
                    core.model.Tempo(
                        tickPosition = it.pos / TICK_RATE,
                        bpm = it.bpm,
                    )
                }.takeIf { it.isNotEmpty() } ?: listOf(core.model.Tempo.default).also {
                warnings.add(ImportWarning.TempoNotFound)
            }

        val tracks =
            project.tracks.mapIndexed { index, track ->
                parseTrack(track, index, params)
            }
        
        return core.model.Project(
            format = format,
            inputFiles = listOf(file),
            name = "test",
            tracks = tracks,
            timeSignatures = timeSignatures,
            tempos = tempos,
            measurePrefix = 0,
            importWarnings = warnings,
        )
    }

    suspend fun generate(
        project: core.model.Project,
        features: List<FeatureConfig>,
    ): ExportResult {
        val content = generateContent(project, features)
        val blob = Blob(arrayOf(content), BlobPropertyBag("application/octet-stream"))

        return ExportResult(
            blob = blob,
            fileName = format.getFileName(project.name),
            notifications = listOfNotNull(
                if (project.hasXSampaData) null else ExportNotification.PhonemeResetRequiredV4,
                if (features.contains(Feature.ConvertPitch)) ExportNotification.PitchDataExported else null,
            ),
        )
    }

    private fun generateContent(
        project: core.model.Project,
        features: List<FeatureConfig>,
    ): String {
        return ""
    }

    private fun parseTrack(
        track: Track,
        trackIndex: Int,
        params: ImportParams,
    ): core.model.Track {
        val notes =
            track.parts
                .flatMap { part -> part.notes.map { part.pos to it } }
                .mapIndexed { index, (tickOffset, note) ->
                    core.model.Note(
                        id = index,
                        tickOn = note.pos,
                        tickOff = note.pos + note.dur,
                        lyric = note.lyric.takeUnless { it.isBlank() } ?: params.defaultLyric,
                        key = note.pitch,
                        phoneme = note.properties?.phoneme
                    )
                }

        // TODO: Pitch bends

        return core.model.Track(
                id = trackIndex,
                name = track.name,
                notes = notes,
                pitch = core.model.Pitch(
                    data = listOf(
                        0L to 0.0,
                        1000L to null
                    ),
                    isAbsolute = true,
                )
            ).validateNotes()
    }

    private const val TICK_RATE = 1470000L

    private val jsonSerializer =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    @Serializable
    private data class Project(
        var version: Int = 0,
        var tempos: List<Tempo> = listOf(),
        var timeSignatures: List<TimeSignature> = listOf(),
        var tracks: List<Track> = listOf(),
    )

    @Serializable
    private data class Track(
        var name: String = "Unknown",
        var gain: Double = 0.0,
        var pan: Double = 0.0,
        var mute: Boolean = false,
        var solo: Boolean = false,
        var color: String = "#737CE5",
        var asRefer: Boolean = true,
        var parts: List<Part> = listOf(),
    )

    @Serializable
    private data class Tempo(
        var pos: Long = 0,
        var bpm: Double = 0.0,
    )

    @Serializable
    private data class TimeSignature(
        var barIndex: Int = 0,
        var numerator: Int = 0,
        var denominator: Int = 0,
    )

    @Serializable
    private data class Part(
        var name: String = "Unknown",
        var pos: Long = 0,
        var dur: Long = 0,
        var type: String = "midi",
        var voice: Voice? = null,
        var properties: Properties? = null,
        var notes: List<Note> = listOf(),
        var automations: JsonElement? = null, // This kind of varies depending on the voice being used..?
        var pitch: JsonElement? = null,
        var vibratos: JsonElement? = null,
    )

    @Serializable
    public data class Voice(
        var type: String = "Unknown",
        var id: String = "",
    )

    // I dont FULLY know whats in properties yet....
    @Serializable
    private data class Properties(
        var temp: Int = 0
    )

    @Serializable
    private data class Note(
        var pos: Long = 0,
        var dur: Long = 0,
        var pitch: Int = 0,
        var lyric: String = "",
        var pronunciation: String = "",
        var properties: NoteProperties? = null,
    )

    @Serializable
    private data class NoteProperties(
        var phoneme: String? = null,
    )

    private const val BPM_RATE = 100.0
    private val format = Format.Tlp
}
