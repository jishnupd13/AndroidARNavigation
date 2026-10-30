package com.app.androidnativearcore.wayfinder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconScanTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Apple manufacturer data (after the company id) of an iBeacon: UUID E2C56DB5-…, Major 1, Minor 258, -59 dBm. */
    private val iBeaconData = bytes(
        0x02, 0x15,
        0xE2, 0xC5, 0x6D, 0xB5, 0xDF, 0xFB, 0x48, 0xD2, 0xB0, 0x60, 0xD0, 0xF5, 0xA7, 0x10, 0x96, 0xE0,
        0x00, 0x01, 0x01, 0x02, 0xC5,
    )

    @Test
    fun anIBeaconIsReadFromAppleManufacturerData() {
        val beacon = BeaconAdvertisement.iBeacon(iBeaconData)!!
        assertEquals("E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", beacon.uuid)
        assertEquals(1, beacon.major)
        assertEquals(258, beacon.minor)
        assertEquals(-59, beacon.txPowerAt1m)
    }

    @Test
    fun otherAppleDataIsNotAnIBeacon() {
        assertNull(BeaconAdvertisement.iBeacon(bytes(0x10, 0x05, 0x01, 0x02)))
        assertNull(BeaconAdvertisement.iBeacon(null))
    }

    @Test
    fun anEddystoneUidFrameIsRead() {
        val frame = bytes(0x00, 0xEE, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF, 0, 0)
        val beacon = BeaconAdvertisement.eddystoneUid(frame)!!
        assertEquals("0102030405060708090A", beacon.namespace)
        assertEquals("0A0B0C0D0E0F", beacon.instance)
        assertEquals(-18 - 41, beacon.txPowerAt1m)
        // An Eddystone URL frame (type 0x10) is not a UID.
        assertNull(BeaconAdvertisement.eddystoneUid(bytes(0x10, 0xEE, 0x03, 0x67, 0x6F, 0x6F, 0x67, 0x6C, 0x65, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun anythingElseIsKnownOnlyByItsAddress() {
        val identity = BeaconAdvertisement.identify(null, null, "aa:bb:cc:dd:ee:ff")
        assertEquals(BeaconIdentity.OtherDevice("AA:BB:CC:DD:EE:FF"), identity)
    }

    private fun reading(rssi: Int, at: Long, address: String = "AA:BB:CC:DD:EE:01", identity: BeaconIdentity = BeaconAdvertisement.iBeacon(iBeaconData)!!) =
        BeaconReading(identity, address, null, rssi, at)

    @Test
    fun oneWildReadingDoesNotMoveTheSignal() {
        val nearby = NearbyDevices()
        listOf(-60, -61, -59, -90, -60).forEachIndexed { i, rssi -> nearby.add(reading(rssi, 1_000L + i * 200)) }
        val device = nearby.current(2_000L).single()
        assertEquals(-60, device.rssi)
        assertTrue(device.isStable)
    }

    @Test
    fun aBeaconThatChangesItsAddressStaysOneDevice() {
        val nearby = NearbyDevices()
        nearby.add(reading(-60, 1_000L, address = "AA:BB:CC:DD:EE:01"))
        nearby.add(reading(-62, 1_200L, address = "11:22:33:44:55:66"))
        assertEquals(1, nearby.current(1_500L).size)
    }

    @Test
    fun devicesNotHeardForAWhileDisappear() {
        val nearby = NearbyDevices()
        nearby.add(reading(-60, 1_000L))
        assertEquals(1, nearby.current(1_000L + NearbyDevices.STALE_MILLIS).size)
        assertEquals(0, nearby.current(1_001L + NearbyDevices.STALE_MILLIS).size)
    }

    @Test
    fun theStrongestSignalComesFirstAndFewReadingsAreNotYetStable() {
        val nearby = NearbyDevices()
        val other = BeaconIdentity.OtherDevice("11:22:33:44:55:66")
        nearby.add(reading(-80, 1_000L, identity = other))
        nearby.add(reading(-55, 1_000L))
        val list = nearby.current(1_100L)
        assertEquals(-55, list.first().rssi)
        assertFalse(list.first().isStable)
        assertTrue(list.first().isBeacon)
        assertFalse(list.last().isBeacon)
    }

    @Test
    fun aRoughDistanceGrowsAsTheSignalWeakens() {
        val nearby = NearbyDevices()
        nearby.add(reading(-59, 1_000L)) // the beacon's own 1 m strength
        val atOneMeter = nearby.current(1_000L).single().roughDistanceMeters!!
        assertEquals(1f, atOneMeter, 0.01f)
        nearby.clear()
        nearby.add(reading(-75, 1_000L))
        assertTrue(nearby.current(1_000L).single().roughDistanceMeters!! > 3f)
    }

    @Test
    fun aRegisteredBeaconIsMatchedByItsIBeaconIdentityOrAddress() {
        val nearby = NearbyDevices().apply { add(reading(-60, 1_000L)) }
        val device = nearby.current(1_000L).single()
        val registered = RegisteredBeacon("BEACON_001", "Main Entrance", "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", 1, 258, floorId = "L0", x = 0f, y = 0f)
        assertTrue(registered.matches(device))
        assertFalse(registered.copy(minor = 1).matches(device))

        val plain = NearbyDevices().apply { add(reading(-60, 1_000L, identity = BeaconIdentity.OtherDevice("AA:BB:CC:DD:EE:FF"), address = "AA:BB:CC:DD:EE:FF")) }
            .current(1_000L).single()
        val byAddress = RegisteredBeacon("BEACON_002", "Tag", macAddress = "AA:BB:CC:DD:EE:FF", floorId = "L0", x = 0f, y = 0f)
        assertTrue(byAddress.matches(plain))
    }
}
