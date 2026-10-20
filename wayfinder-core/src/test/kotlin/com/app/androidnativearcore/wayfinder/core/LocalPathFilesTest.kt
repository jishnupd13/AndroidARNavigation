package com.app.androidnativearcore.wayfinder.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Every path file in the project's `local_ar_files/paths` folder must be readable by the app's own loader. */
class LocalPathFilesTest {
    private val folder = File("../local_ar_files/paths")

    @Test
    fun everyPathFileInTheFolderLoads() {
        assumeTrue(folder.isDirectory)
        val files = folder.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        // Load them through the real store, in a copy of the folder, so a file the app can't read would be missing.
        val copy = java.nio.file.Files.createTempDirectory("local-paths").toFile()
        try {
            val paths = File(copy, "paths").apply { mkdirs() }
            files.forEach { it.copyTo(File(paths, it.name)) }
            val loaded = ArPathStore(paths).load("demo-mall")
            assertEquals("these files could not be read: ${files.map { it.name } - paths.listFiles()!!.map { it.name }.toSet()}",
                files.size, loaded.size)
            for (path in loaded) {
                assertTrue("${path.destination.name} has no path points", path.walkedPoints.isNotEmpty())
                assertTrue("${path.destination.name}: points belong to another destination",
                    path.walkedPoints.all { it.destinationId == path.destination.id })
            }
        } finally {
            copy.deleteRecursively()
        }
    }
}
