package com.app.androidnativearcore.wayfinder.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/** Data exported by another app (the iOS app's bundle) writes `null` for values it doesn't have; it must still load. */
class ImportCompatibilityTest {
    private fun tempDir(): File = Files.createTempDirectory("import").toFile()

    /** A path file exactly as found in an exported bundle: "startMarkerId" and the accuracy values are null. */
    private val pathWithNulls = """
        {
          "alignmentSpreadCm" : null,
          "destination" : {
            "createdAtMillis" : 1793167368551,
            "floorY" : -0.5808499,
            "id" : "custom-2d672f9e-0ef8-4377-8c9a-63dfe778d300",
            "level" : "L0",
            "name" : "Obj1",
            "node" : "",
            "x" : -3.2216487,
            "z" : 0.6733601
          },
          "driftCorrectedCm" : null,
          "formatVersion" : 1,
          "objects" : [ ],
          "startMarkerId" : null,
          "venueId" : "demo-mall",
          "walkedPoints" : [ ]
        }
    """.trimIndent()

    /** A reference point exactly as found in the same bundle: its width is null. */
    private val markerWithNullWidth = """
        {
          "marker" : {
            "facing" : 326.9389,
            "id" : "ref-503d7f55-2d8c-4499-be31-1825907e3b1c",
            "image" : "ref-503d7f55-2d8c-4499-be31-1825907e3b1c.png",
            "level" : "L0",
            "name" : { "en" : "Ref1" },
            "physicalWidth" : null,
            "x" : -3.250573,
            "y" : -1.4108524,
            "z" : -4.1682053
          },
          "venueId" : "demo-mall"
        }
    """.trimIndent()

    @Test
    fun aPathWithNullValuesLoads() {
        val dir = tempDir()
        File(dir, "Obj1.json").writeText(pathWithNulls)
        val paths = ArPathStore(dir).load("demo-mall")
        assertEquals(1, paths.size)
        assertEquals("Obj1", paths.single().destination.name)
        assertEquals("", paths.single().startMarkerId)
    }

    @Test
    fun aReferencePointWithoutAWidthLoadsAndJoinsTheVenue() {
        val dir = tempDir()
        File(dir, "ref-503d7f55-2d8c-4499-be31-1825907e3b1c.json").writeText(markerWithNullWidth)
        File(dir, "ref-503d7f55-2d8c-4499-be31-1825907e3b1c.png").writeBytes(byteArrayOf(1, 2, 3))
        val markers = CapturedMarkerStore(dir).load("demo-mall")
        assertEquals(1, markers.size)
        assertEquals("unknown width", 0f, markers.single().physicalWidth, 0f)

        val venue = Venue(
            id = "demo-mall", name = LocalizedText("Demo"),
            levels = listOf(Venue.Level("L0", LocalizedText("Ground"), 0f)),
            nodes = emptyList(), edges = emptyList(), pois = emptyList(), markers = emptyList(),
        )
        assertEquals(1, venue.withExtraMarkers(markers).markers.size)
    }
}
