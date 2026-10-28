package com.app.androidnativearcore.wayfinder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataBundleTest {
    @Test
    fun theAppsOwnLayoutIsKept() {
        assertEquals("paths/Obj1.json", DataBundle.targetPath("paths/Obj1.json"))
        assertEquals("captured-markers/ref-1.png", DataBundle.targetPath("captured-markers/ref-1.png"))
    }

    @Test
    fun aBundleCompressedAgainOnAMacIsFound() {
        // As in wayfinder-2.zip: everything inside a folder named after the export.
        assertEquals("paths/Obj1.json", DataBundle.targetPath("wayfinder-export-1793167194165/paths/Obj1.json"))
        assertEquals(
            "captured-markers/ref-9cb48f0e.json",
            DataBundle.targetPath("wayfinder-export-1793167194165/captured-markers/ref-9cb48f0e.json"),
        )
    }

    @Test
    fun theMacsExtrasAreIgnored() {
        assertNull(DataBundle.targetPath("__MACOSX/wayfinder-export-1793167194165/paths/._Obj1.json"))
        assertNull(DataBundle.targetPath("wayfinder-export-1793167194165/.DS_Store"))
        assertNull(DataBundle.targetPath("paths/._Obj1.json"))
    }

    @Test
    fun foldersAndOtherFilesAreSkipped() {
        assertNull(DataBundle.targetPath("wayfinder-export-1793167194165/paths/"))
        assertNull(DataBundle.targetPath("wayfinder-export-1793167194165/readme.txt"))
        assertNull(DataBundle.targetPath("paths"))
    }

    @Test
    fun climbingOutIsRefused() {
        assertNull(DataBundle.targetPath("paths/../../shared_prefs/settings.xml"))
    }
}
