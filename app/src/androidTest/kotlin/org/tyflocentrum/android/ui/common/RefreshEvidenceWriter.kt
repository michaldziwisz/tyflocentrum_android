package net.tyflopodcast.tyflocentrum.ui.common

import java.security.MessageDigest

/** Wspólna logika UiAutomation i kontroli na rzeczywistych procesach JVM. */
internal class RefreshEvidenceWriter(
    private val directory: String,
    private val readCommand: (String) -> ByteArray,
    private val writeCommand: (String, ByteArray) -> Unit,
) {
    init {
        require(directory.startsWith("/") && directory.matches(Regex("[/a-zA-Z0-9_-]+")))
    }

    fun write(name: String, text: String): String {
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        val path = "$directory/$name"
        val bytes = text.toByteArray(Charsets.UTF_8)
        readCommand("mkdir -p $directory")
        // dd nie kopiuje wejścia na stdout: duże drzewo nie blokuje dwóch pełnych potoków.
        writeCommand("dd of=$path", bytes)
        val mode = readCommand("stat -c %f $path").toString(Charsets.US_ASCII).trim().toInt(16)
        check(mode and 0xf000 == 0x8000) { "Dowód nie jest zwykłym plikiem: $path" }
        val actual = readCommand("cat $path")
        check(bytes.contentEquals(actual)) { "Niezgodny odczyt dowodu: $path" }
        val hash = MessageDigest.getInstance("SHA-256").digest(actual).joinToString("") { "%02x".format(it) }
        return "REFRESH_EVIDENCE $path bytes=${actual.size} sha256=$hash readback=true"
    }
}
