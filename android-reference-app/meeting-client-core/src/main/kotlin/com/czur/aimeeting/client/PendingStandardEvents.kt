package com.czur.aimeeting.client

import com.czur.aimeeting.client.server.StandardEventPacket
import com.czur.aimeeting.client.server.standardEventDigest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Logger
import org.json.JSONObject

/** 本机已有原文，但不能证明这些数据已被原场后端确认。 */
class UnverifiedStandardEventsException : IOException("原场标准事件仍待确认，保留本机原文")

/** 云场唯一标准事件日志。持久化原包后才发送，连续确认落盘后才移除对应包。 */
internal class PendingStandardEvents private constructor(
    private val directory: File,
    private val meetingId: String,
    private var receipt: Receipt,
    private val legacyJournal: File? = null,
) {
    private data class Receipt(val sequence: Int, val eventId: String?, val bodyDigest: String?)

    private val stateFile = File(directory, "receipt.json")
    private val unfinishedReceipt = File(directory, "receipt.json.part")
    private var queuedThrough = receipt.sequence

    @Synchronized
    fun append(packet: StandardEventPacket) {
        if (packet.sequence != queuedThrough + 1) throw UnverifiedStandardEventsException()
        val file = eventFile(packet.sequence)
        if (file.exists()) throw UnverifiedStandardEventsException()
        write(file, JSONObject().put("body", packet.body).put("sha256", standardEventDigest(packet.body)))
        queuedThrough = packet.sequence
    }

    @Synchronized
    fun acknowledge(packet: StandardEventPacket) {
        if (packet.sequence != receipt.sequence + 1 ||
            readPacket(eventFile(packet.sequence), packet.sequence).body != packet.body)
            throw UnverifiedStandardEventsException()
        val confirmed = Receipt(packet.sequence, StandardEventPacket.serverEventId(meetingId, packet.key),
            standardEventDigest(packet.body))
        persistReceipt(confirmed)
        receipt = confirmed
        // An upgraded B9 scene may need this original packet to recheck its diagnostic line after another restart.
        if (legacyJournal == null) eventFile(packet.sequence).delete()
    }

    @Synchronized
    fun pending(): List<StandardEventPacket> = stored {
        val files = entries()
        val legacyPackets = recheckLegacyJournal(files)
        // 若进程在持久回执与移除原包之间退出，仍要核末条回执对应的原包。
        files[receipt.sequence]?.let { source ->
            val packet = legacyPackets[receipt.sequence] ?: readPacket(source, receipt.sequence)
            if (receipt.eventId != StandardEventPacket.serverEventId(meetingId, packet.key) ||
                receipt.bodyDigest?.let { it != standardEventDigest(packet.body) } == true)
                throw UnverifiedStandardEventsException()
        }
        files.filterKeys { it > receipt.sequence }.entries.mapIndexed { index, (sequence, source) ->
            if (sequence != receipt.sequence + index + 1) throw UnverifiedStandardEventsException()
            val packet = legacyPackets[sequence] ?: readPacket(source, sequence)
            val target = eventFile(sequence)
            if (source != target) {
                FileChannel.open(source.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                if (!source.renameTo(target)) throw UnverifiedStandardEventsException()
                syncDirectory(directory)
            }
            packet
        }
    }

    fun markWriteFailed() {
        // A local write failure may not look like a confirmed, empty queue at restart.
        runCatching {
            if (File(directory, "write-failed").createNewFile()) syncDirectory(directory)
        }
    }

    @Synchronized
    fun discardAcknowledged(checkScope: Runnable, removeRecord: Runnable) {
        if (pending().isNotEmpty()) throw UnverifiedStandardEventsException()
        checkScope.run()
        // The diagnostics are now covered by durable receipts. Keep the packets until the old log is gone.
        if (legacyJournal != null) {
            if (legacyJournal.exists() && !legacyJournal.delete())
                throw IOException("原场旧诊断日志清理未完成")
            syncDirectory(legacyJournal.parentFile)
        }
        for (source in entries().values) {
            if (!source.delete()) throw IOException("原场事件文件清理未完成")
        }
        // Keep the durable receipt if removing the capturing record fails or the process exits.
        checkScope.run()
        removeRecord.run()
        if (!removeUnfinishedReceipt() || !stateFile.delete() || !directory.delete())
            LOG.warning("原场标准事件回执目录待清理")
    }

    private fun recheckLegacyJournal(files: Map<Int, File>): Map<Int, StandardEventPacket> {
        val journal = legacyJournal ?: return emptyMap()
        val packets = files.mapValues { (sequence, source) -> readPacket(source, sequence) }
        val byKey = packets.values.groupBy { it.key }
        var lastSequence = 0
        journal.forEachLine { row ->
            val line = JSONObject(row)
            val key = "${line.getString("segmentId")}-r${line.getInt("revision")}"
            val packet = byKey[key]?.singleOrNull() ?: throw UnverifiedStandardEventsException()
            if (packet.sequence <= lastSequence || !packet.matchesLegacyFinal(line))
                throw UnverifiedStandardEventsException()
            lastSequence = packet.sequence
        }
        return packets
    }

    private fun readPacket(source: File, sequence: Int): StandardEventPacket = stored {
        val json = JSONObject(source.readText())
        val body = json.getString("body")
        val packet = StandardEventPacket.read(body)
        if (packet.sequence != sequence || standardEventDigest(body) != json.getString("sha256"))
            throw UnverifiedStandardEventsException()
        packet
    }

    private fun entries(): Map<Int, File> {
        val files = directory.listFiles() ?: throw UnverifiedStandardEventsException()
        val events = sortedMapOf<Int, File>()
        for (file in files) {
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) throw UnverifiedStandardEventsException()
            if (file == stateFile || file == unfinishedReceipt) continue
            val finishedName = file.name.removeSuffix(".part")
            val sequence = finishedName.removeSuffix(".event").toIntOrNull()
            if (sequence == null || sequence <= 0 || finishedName != eventFile(sequence).name ||
                events.put(sequence, file) != null) throw UnverifiedStandardEventsException()
        }
        return events
    }

    private fun eventFile(sequence: Int): File = File(directory, "$sequence.event")

    private fun removeUnfinishedReceipt(): Boolean = !unfinishedReceipt.exists() ||
        (!Files.isSymbolicLink(unfinishedReceipt.toPath()) && unfinishedReceipt.isFile && unfinishedReceipt.delete())

    private fun persistReceipt(confirmed: Receipt) {
        if (!removeUnfinishedReceipt()) throw UnverifiedStandardEventsException()
        write(stateFile, JSONObject().put("version", 2).put("meetingId", meetingId)
            .put("acknowledgedThrough", confirmed.sequence).put("eventId", confirmed.eventId)
            .put("requestSha256", confirmed.bodyDigest))
    }

    private fun write(target: File, json: JSONObject) {
        val temporary = File(directory, target.name + ".part")
        if (!temporary.createNewFile()) throw UnverifiedStandardEventsException()
        FileOutputStream(temporary).use { stream ->
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (!temporary.renameTo(target)) throw IOException("原场标准事件同步失败")
        syncDirectory(directory)
    }

    companion object {
        private val LOG = Logger.getLogger(PendingStandardEvents::class.java.name)

        fun initialize(record: CapturingMeeting): PendingStandardEvents {
            val directory = directory(record)
            val parent = directory.parentFile ?: throw UnverifiedStandardEventsException()
            if ((!parent.isDirectory && !parent.mkdirs()) || Files.isSymbolicLink(parent.toPath()))
                throw UnverifiedStandardEventsException()
            if (!directory.mkdir()) throw UnverifiedStandardEventsException()
            syncDirectory(parent)
            val empty = Receipt(0, null, null)
            return PendingStandardEvents(directory, record.meetingId(), empty).also { it.persistReceipt(empty) }
        }

        fun open(record: CapturingMeeting): PendingStandardEvents? = stored {
            val directory = directory(record)
            // 只核本场的固定私有诊断路径。有原文时须绑定仍在磁盘上的完整原请求。
            val defaultJournal = File(directory.parentFile, "${record.meetingId()}.jsonl")
            val legacy = defaultJournal.takeIf(::hasLegacyTranscript)
            record.journalFile()?.takeIf { it != defaultJournal }?.let {
                if (hasLegacyTranscript(it)) throw UnverifiedStandardEventsException()
            }
            val attributes = attributesOrNull(directory)
            if (!record.hasStandardEventJournal() && attributes == null) {
                if (legacy != null) throw UnverifiedStandardEventsException()
                return@stored null
            }
            if (attributes?.isDirectory != true) throw UnverifiedStandardEventsException()

            val stateFile = File(directory, "receipt.json")
            if (Files.isSymbolicLink(directory.toPath()) || !stateFile.isFile || Files.isSymbolicLink(stateFile.toPath()))
                throw UnverifiedStandardEventsException()
            val json = JSONObject(stateFile.readText())
            val sequence = json.getInt("acknowledgedThrough")
            val version = json.getInt("version")
            val eventId = if (sequence > 0) json.getString("eventId") else null
            val bodyDigest = if (version == 2 && sequence > 0) json.getString("requestSha256") else null
            if (version !in 1..2 || json.getString("meetingId") != record.meetingId() || sequence < 0 ||
                eventId?.matches(Regex("evt_client_[0-9a-f]{64}")) == false ||
                bodyDigest?.matches(Regex("[0-9a-f]{64}")) == false) throw UnverifiedStandardEventsException()
            PendingStandardEvents(directory, record.meetingId(), Receipt(sequence, eventId, bodyDigest), legacy)
        }

        private fun hasLegacyTranscript(file: File): Boolean {
            val attributes = attributesOrNull(file) ?: return false
            if (!attributes.isRegularFile) throw UnverifiedStandardEventsException()
            return attributes.size() > 0L
        }

        private fun attributesOrNull(file: File): BasicFileAttributes? = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }

        private fun directory(record: CapturingMeeting): File {
            val id = record.meetingId()
            if (!id.matches(Regex("[A-Za-z0-9_-]+"))) throw UnverifiedStandardEventsException()
            return File(record.audioFile().parentFile, "$id.standard-events")
        }

        private fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

        private inline fun <T> stored(read: () -> T): T = try {
            read()
        } catch (e: UnverifiedStandardEventsException) {
            throw e
        } catch (_: IOException) {
            throw UnverifiedStandardEventsException()
        } catch (_: RuntimeException) {
            throw UnverifiedStandardEventsException()
        }
    }
}
