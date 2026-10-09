package dev.kortex.core

import dev.kortex.core.tool.builtin.MAX_ATTACHED_FILE_BYTES
import dev.kortex.core.tool.builtin.readFileTool
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

class ReadFileToolTest {
    @TempDir
    lateinit var dir: File

    private suspend fun read(file: File) =
        readFileTool().execute(buildJsonObject { put("file_path", file.absolutePath) })

    @Test
    fun `refuses non-text files over the size limit`() = runTest {
        val video = File(dir, "clip.mp4")
        RandomAccessFile(video, "rw").use { it.setLength(MAX_ATTACHED_FILE_BYTES + 1) }

        val result = read(video)
        result.ok shouldBe false
        result.content shouldContain "too large"
        result.attachments shouldBe emptyList()
    }

    @Test
    fun `attaches small non-text files`() = runTest {
        val image = File(dir, "pic.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val result = read(image)
        result.ok shouldBe true
        result.attachments.single().mimeType shouldBe "image/png"
        result.attachments.single().dataBase64 shouldBe "AQID"
    }

    @Test
    fun `returns only the first 100k chars of a text file`() = runTest {
        val text = File(dir, "notes.txt").apply { writeText("a".repeat(150_000)) }

        val result = read(text)
        result.ok shouldBe true
        result.content.substringAfter("\n").length shouldBe 100_000
    }
}
