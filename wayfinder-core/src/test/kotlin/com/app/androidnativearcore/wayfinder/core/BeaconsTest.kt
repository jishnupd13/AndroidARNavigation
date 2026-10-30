package com.app.androidnativearcore.wayfinder.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconsTest {
    private val floors = setOf("L0", "L1")

    private fun beacon(
        id: String = "BEACON_001",
        name: String = "Main Entrance",
        uuid: String = "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
        major: Int? = 1,
        minor: Int? = 1,
        mac: String = "",
        floor: String = "L0",
    ) = RegisteredBeacon(id, name, uuid, major, minor, mac, "demo-mall", floor, 2.5f, 4.0f)

    @Test
    fun uuidsAreAcceptedInAnyCaseWithOrWithoutDashes() {
        val canonical = "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"
        assertEquals(canonical, Beacons.normalizeUuid("e2c56db5dffb48d2b060d0f5a71096e0"))
        assertEquals(canonical, Beacons.normalizeUuid(" e2c56db5-dffb-48d2-b060-d0f5a71096e0 "))
        assertEquals("", Beacons.normalizeUuid("  "))
        assertNull(Beacons.normalizeUuid("not-a-uuid"))
    }

    @Test
    fun bluetoothAddressesAreNormalized() {
        assertEquals("AA:BB:CC:DD:EE:FF", Beacons.normalizeMac("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", Beacons.normalizeMac("AABBCCDDEEFF"))
        assertEquals("", Beacons.normalizeMac(""))
        assertNull(Beacons.normalizeMac("AA:BB"))
    }

    @Test
    fun aCompleteBeaconHasNoProblems() {
        assertEquals(emptyList<String>(), Beacons.problems(beacon(), floors, emptyList()))
        // A beacon known only by its address is fine too.
        assertEquals(emptyList<String>(), Beacons.problems(beacon(uuid = "", major = null, minor = null, mac = "AA:BB:CC:DD:EE:FF"), floors, emptyList()))
    }

    @Test
    fun missingOrInvalidFieldsAreReported() {
        assertTrue(Beacons.problems(beacon(name = " "), floors, emptyList()).any { "name" in it })
        assertTrue(Beacons.problems(beacon(floor = "L9"), floors, emptyList()).any { "floor" in it })
        assertTrue(Beacons.problems(beacon(uuid = "", major = null, minor = null), floors, emptyList()).any { "UUID" in it })
        assertTrue(Beacons.problems(beacon(minor = null), floors, emptyList()).any { "Major and Minor" in it })
        assertTrue(Beacons.problems(beacon(major = 70_000), floors, emptyList()).any { "between 0" in it })
        assertTrue(Beacons.problems(beacon().copy(x = null), floors, emptyList()).any { "both X and Y" in it })
        assertTrue(Beacons.problems(beacon().copy(x = Float.NaN), floors, emptyList()).any { "numbers" in it })
    }

    @Test
    fun aBeaconWithoutAPositionCanBeSaved() {
        // Registered without a reference point: found by its signal alone.
        val unplaced = beacon().copy(x = null, y = null)
        assertTrue(Beacons.problems(unplaced, floors, emptyList()).isEmpty())
        assertTrue(!unplaced.hasPosition)
        assertNull(unplaced.position)
        assertNull(unplaced.routeEndpoint())

        // Saved and read back without a position.
        val dir = Files.createTempDirectory("beacons").toFile()
        BeaconStore(dir).save(unplaced.copy(buildingId = "demo-mall"))
        val loaded = BeaconStore(dir).load("demo-mall").single()
        assertNull(loaded.x)
        assertNull(loaded.y)
        dir.deleteRecursively()
    }

    @Test
    fun theSameBeaconCannotBeRegisteredTwice() {
        val existing = beacon(id = "BEACON_001", name = "Main Entrance")
        val again = beacon(id = "BEACON_002", name = "Entrance copy")
        assertTrue(Beacons.problems(again, floors, listOf(existing)).any { "already registered" in it })
        // Editing the beacon itself is not a duplicate.
        assertEquals(emptyList<String>(), Beacons.problems(existing.copy(name = "Front door"), floors, listOf(existing)))
        // A different minor is a different beacon.
        assertFalse(Beacons.problems(beacon(id = "BEACON_002", minor = 2), floors, listOf(existing)).any { "already registered" in it })
    }

    @Test
    fun idsFollowTheSharedStyle() {
        assertEquals("BEACON_001", Beacons.nextId(emptyList()))
        assertEquals("BEACON_004", Beacons.nextId(listOf(beacon(id = "BEACON_001"), beacon(id = "BEACON_003"))))
    }

    @Test
    fun theStoreSavesEditsAndDeletesOneFilePerBeacon() {
        val dir = Files.createTempDirectory("beacons").toFile()
        val store = BeaconStore(dir)
        store.save(beacon(id = "BEACON_001", name = "Main Entrance"))
        store.save(beacon(id = "BEACON_002", name = "Lift", minor = 2, floor = "L1"))
        assertEquals(listOf("Main Entrance", "Lift"), store.load("demo-mall").map { it.name })
        assertEquals(2, dir.listFiles { f -> f.name.endsWith(".json") }!!.size)

        store.save(beacon(id = "BEACON_001", name = "Front door"))
        assertEquals("Front door", store.load("demo-mall").first { it.beaconId == "BEACON_001" }.name)
        assertEquals(2, store.load("demo-mall").size)

        assertTrue(store.delete("BEACON_002"))
        assertEquals(listOf("BEACON_001"), store.load("demo-mall").map { it.beaconId })
        assertEquals("other places' beacons are not mixed in", 0, store.load("another-place").size)
    }

    @Test
    fun theSharedFormatIsReadIncludingMissingOptionalFields() {
        // The example from the plan: no address, no building, no creation time.
        val dir = Files.createTempDirectory("beacons").toFile()
        File(dir, "beacon-001.json").writeText(
            """
            { "beaconId": "BEACON_001", "name": "Main Entrance", "uuid": "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
              "major": 1, "minor": 1, "floorId": "GROUND_FLOOR", "x": 2.5, "y": 4.0, "macAddress": null }
            """.trimIndent(),
        )
        val loaded = BeaconStore(dir).load("demo-mall").single()
        assertEquals("BEACON_001", loaded.beaconId)
        assertEquals(1, loaded.major)
        assertEquals("", loaded.macAddress)
        assertEquals(2.5f, loaded.x!!, 0f)
        assertEquals(4.0f, loaded.y!!, 0f)
    }
}
