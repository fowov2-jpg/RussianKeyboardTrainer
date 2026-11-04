package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class ExtendedFixturePackagingTest {
    @Test fun compressedJvmResourceAndExpandedAndroidAssetContainIdenticalCases() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/extended_qa.tsv.gz")).use { it.readBytes() }
        val expanded = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        assertEquals(ExtendedCase.read(ByteArrayInputStream(bytes)), ExtendedCase.read(ByteArrayInputStream(expanded)))
    }
}
