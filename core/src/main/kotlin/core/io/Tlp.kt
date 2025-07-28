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
import kotlinx.serialization.json.JsonArray
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
                        tickPosition = it.pos.toLong() / TICK_RATE,
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
                        tickOn = note.pos.toLong(),
                        tickOff = (note.pos + note.dur).toLong(),
                        lyric = note.lyric.takeUnless { it.isBlank() } ?: params.defaultLyric,
                        key = note.pitch,
                        phoneme = note.properties?.phoneme
                    )
                }


        return core.model.Track(
            id = trackIndex,
            name = track.name,
            notes = notes,
            pitch = core.model.Pitch(listOf(0L to 0.0), isAbsolute = false),
        ).validateNotes()
    }

    private const val TICK_RATE = 1470000L
    private val format = Format.Tlp

    private val jsonSerializer =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    @Serializable
    private data class Project(
        var version: Int,
        var tempos: List<Tempo> = listOf(),
        var timeSignatures: List<TimeSignature> = listOf(),
        var tracks: List<Track> = listOf(),
    )

    @Serializable
    private data class Track(
        var name: String = "Unknown",
        var gain: Double,
        var pan: Double,
        var mute: Boolean,
        var solo: Boolean,
        var color: String = "#737CE5",
        var asRefer: Boolean = true,
        var parts: List<Part> = listOf(),
    )

    @Serializable
    private data class Tempo(
        var pos: Double,
        var bpm: Double,
    )

    @Serializable
    private data class TimeSignature(
        var barIndex: Int,
        var numerator: Int,
        var denominator: Int,
    )

    @Serializable
    private data class Part(
        var name: String = "Unknown",
        var pos: Double,
        var dur: Double,
        var type: String = "midi",
        var voice: Voice? = null,
        var properties: JsonElement? = null,
        var notes: List<Note> = listOf(),
        var automations: Automations?,
        var pitch: Array<Array<Double>>? = null,
        var vibratos: JsonArray? = null,
    )

    @Serializable
    private data class Automations(
        var PitchBend: JsonElement? = null,
        var PitchBendSensitivity: AutomationEventType? = null,
        var Dynamics: JsonElement? = null,
        var Brightness: JsonElement? = null,
        var Gender: JsonElement? = null,
        var Growl: JsonElement? = null,
        var Clearness: JsonElement? = null,
        var Exciter: JsonElement? = null,
        var Breathiness: JsonElement? = null,
        var Air: JsonElement? = null,
    )

    @Serializable
    private data class AutomationEventType(
        var default: Double,
        var values: JsonArray,
    )

    @Serializable
    public data class Voice(
        var type: String = "Unknown",
        var id: String,
    )

    @Serializable
    private data class Note(
        var pos: Double,
        var dur: Double,
        var pitch: Int,
        var lyric: String,
        var pronunciation: String,
        var properties: NoteProperties? = null,
    )

    @Serializable
    private data class NoteProperties(
        var phoneme: String? = null,
    )
}
