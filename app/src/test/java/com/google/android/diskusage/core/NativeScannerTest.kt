package com.google.android.diskusage.core

import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Feeds [NativeScanner] with synthetic output of `scan.c` and checks the resulting tree.
 */
class NativeScannerTest {

    /** Builds the byte stream produced by `scan.c`. */
    private class ScanOutput {
        private val out = ByteArrayOutputStream().apply { write(0) }

        private fun field(value: Any) {
            out.write(value.toString().toByteArray())
            out.write(0)
        }

        fun file(name: String, blocks512: Long, bytes: Long) = apply {
            out.write('F'.code)
            field(name)
            field(blocks512)
            field(bytes)
        }

        fun dir(name: String, blocks512: Long = 8, content: ScanOutput.() -> Unit) = apply {
            out.write('D'.code)
            field(name)
            field(blocks512)
            field(blocks512 * 512)
            content()
            out.write('Z'.code)
        }

        fun stream(): InputStream = out.toByteArray().inputStream()
    }

    private fun scan(
        output: ScanOutput,
        allocatedBlocks: Long = 1_000_000,
        maxHeap: Int = 64 * 1024 * 1024,
    ): FileSystemEntry = NativeScanner(BLOCK_SIZE, allocatedBlocks, maxHeap).scan(output.stream())

    @Test
    fun simpleTree() {
        val root = scan(ScanOutput().dir("/storage/emulated/0") {
            file("a.bin", 16, 8000)
            file("empty", 0, 0)
            dir("sub") {
                file("b.bin", 64, 32768)
            }
        })
        assertEquals(
            """
            FileSystemFile /storage/emulated/0 blocks=12 enc=201588784
              FileSystemFile sub blocks=9 enc=151257124
                FileSystemFile b.bin blocks=8 enc=134479904
              FileSystemFile a.bin blocks=2 enc=33816583
            """.trimIndent(),
            dump(root),
        )
    }

    @Test
    fun deepTreeUsesSoftStack() {
        // Deeper than 10 levels switches to the non-recursive implementation
        fun ScanOutput.nested(level: Int) {
            file("f$level", level * 8L, level * 4096L)
            if (level < 25) dir("d$level") { nested(level + 1) }
        }
        val root = scan(ScanOutput().dir("root") { nested(0) })
        assertGolden(GOLDEN_DEEP, dump(root))
    }

    @Test
    fun smallFilesAreAggregated() {
        val random = Random(42)
        fun ScanOutput.randomTree(level: Int) {
            repeat(random.nextInt(3, 25)) { i ->
                if (level < 5 && random.nextInt(4) == 0) {
                    dir("d$level-$i") { randomTree(level + 1) }
                } else {
                    val blocks = random.nextLong(0, 5000)
                    file("f$level-$i", blocks, (blocks * 512 - random.nextLong(0, 512)).coerceAtLeast(0))
                }
            }
        }
        val root = scan(
            ScanOutput().dir("root") { randomTree(0) },
            allocatedBlocks = 200_000,
            maxHeap = 16 * 1024,
        )
        val dump = dump(root)
        val summary = "nodes=${dump.lines().size} small=${dump.lines().count { "EntrySmall" in it }} " +
            "sha256=${sha256(dump)}"
        assertGolden(GOLDEN_RANDOM, summary)
    }

    @Test
    fun truncatedStreamFails() {
        val broken = byteArrayOf(0, 'D'.code.toByte(), 'x'.code.toByte(), 0, '8'.code.toByte())
        assertThrows(RuntimeException::class.java) {
            NativeScanner(BLOCK_SIZE, 1000, 1 shl 20).scan(broken.inputStream())
        }
    }

    companion object {
        private const val BLOCK_SIZE = 4096L

        fun dump(entry: FileSystemEntry): String = buildString {
            fun walk(e: FileSystemEntry, depth: Int) {
                append("  ".repeat(depth))
                append("${e.javaClass.simpleName} ${e.name} blocks=${e.sizeInBlocks} enc=${e.encodedSize}\n")
                e.children?.forEach { walk(it, depth + 1) }
            }
            walk(entry, 0)
        }.trim()

        private fun sha256(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun assertGolden(golden: String, actual: String) =
            assertEquals(golden.trimIndent().trim(), actual)

        private val GOLDEN_DEEP = """
            FileSystemFile root blocks=351 enc=5889328508
              FileSystemFile d0 blocks=350 enc=5872551288
                FileSystemFile d1 blocks=348 enc=5838996848
                  FileSystemFile d2 blocks=345 enc=5788665188
                    FileSystemFile d3 blocks=341 enc=5721556308
                      FileSystemFile d4 blocks=336 enc=5637670208
                        FileSystemFile d5 blocks=330 enc=5537006888
                          FileSystemFile d6 blocks=323 enc=5419566348
                            FileSystemFile d7 blocks=315 enc=5285348588
                              FileSystemFile d8 blocks=306 enc=5134353608
                                FileSystemFile d9 blocks=296 enc=4966581408
                                  FileSystemFile d10 blocks=285 enc=4782031988
                                    FileSystemFile d11 blocks=273 enc=4580705348
                                      FileSystemFile d12 blocks=260 enc=4362601488
                                        FileSystemFile d13 blocks=246 enc=4127458264
                                          FileSystemFile d14 blocks=231 enc=3875799964
                                            FileSystemFile d15 blocks=215 enc=3607364444
                                              FileSystemFile d16 blocks=198 enc=3322151704
                                                FileSystemFile d17 blocks=180 enc=3020161744
                                                  FileSystemFile d18 blocks=161 enc=2701394564
                                                    FileSystemFile d19 blocks=141 enc=2365850164
                                                      FileSystemFile d20 blocks=120 enc=2013528544
                                                        FileSystemFile d21 blocks=98 enc=1644429704
                                                          FileSystemFile d22 blocks=75 enc=1258553644
                                                            FileSystemFile d23 blocks=51 enc=855900364
                                                              FileSystemFile d24 blocks=26 enc=436469864
                                                                FileSystemFile f25 blocks=25 enc=419692644
                                                              FileSystemFile f24 blocks=24 enc=402915424
                                                            FileSystemFile f23 blocks=23 enc=386138204
                                                          FileSystemFile f22 blocks=22 enc=369360984
                                                        FileSystemFile f21 blocks=21 enc=352583764
                                                      FileSystemFile f20 blocks=20 enc=335806544
                                                    FileSystemFile f19 blocks=19 enc=319029324
                                                  FileSystemFile f18 blocks=18 enc=302252104
                                                FileSystemFile f17 blocks=17 enc=285474884
                                              FileSystemFile f16 blocks=16 enc=268697664
                                            FileSystemFile f15 blocks=15 enc=251920444
                                          FileSystemFile f14 blocks=14 enc=235143224
                                        FileSystemFile f13 blocks=13 enc=218366004
                                      FileSystemFile f12 blocks=12 enc=201588784
                                    FileSystemFile f11 blocks=11 enc=184811564
                                  FileSystemFile f10 blocks=10 enc=168034344
                                FileSystemFile f9 blocks=9 enc=151257124
                              FileSystemFile f8 blocks=8 enc=134479904
                            FileSystemFile f7 blocks=7 enc=117702684
                          FileSystemFile f6 blocks=6 enc=100925464
                        FileSystemFile f5 blocks=5 enc=84148244
                      FileSystemFile f4 blocks=4 enc=67371024
                    FileSystemFile f3 blocks=3 enc=50593804
                  FileSystemFile f2 blocks=2 enc=33816584
                FileSystemFile f1 blocks=1 enc=17039364
            """
        private val GOLDEN_RANDOM =
            "nodes=3530 small=1091 sha256=e119b84794e8701e676a60cb51346dbe95391bd4a1b1e5cc9510ea6f51ba83bc"
    }
}
