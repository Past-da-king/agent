package com.past9.phoneaos.wearproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code field, the QR address and the local-network road, and the diagnosis that replaces "no watch answered". */
class PairLanTest {
    @Test fun theCodeFieldTakesATypedPastedOrDashedCodeAndShowsItFourPlusFour() {
        assertEquals("9H4T-5PBS", PairCode.format("9H4T5PBS"))
        assertEquals("9H4T-5PBS", PairCode.format("9H4T-5PBS"))
        assertEquals("9H4T-5PBS", PairCode.format(" 9h4t 5pbs "))
        assertEquals("9H4T", PairCode.format("9h4t")); assertEquals("", PairCode.format(""))
        assertEquals("9H4T5PBS", PairCode.clean("9H4T-5PBS-EXTRA"))
        assertTrue(PairCode.complete("9H4T-5PBS")); assertFalse(PairCode.complete("9H4T-5PB"))
    }

    @Test fun lettersThePeopleMixUpReadAsTheDigitsAndLettersTheCodeNeverUsesAreDropped() {
        assertEquals("01100110", PairCode.clean("OIL0O1l0"))
        assertEquals("ABCD", PairCode.clean("AUBUCUDU"))
    }

    @Test fun theQrCarriesTheWatchAddressAndStillParsesWithoutOne() {
        val o = PairOffer("node-1", PairCode.secretOf(ByteArray(16) { it.toByte() }), 1L)
        val with = PairCode.parseQr(o.qr("192.168.1.49:41475"))!!
        assertEquals("192.168.1.49:41475", with.host); assertEquals("node-1", with.node); assertEquals(o.secret, with.secret)
        assertEquals("", PairCode.parseQr(o.qr())!!.host)
    }

    @Test fun onlyAddressesOnAHomeOrOfficeNetworkAreEverDialled() {
        assertEquals("192.168.1.49" to 41475, PairLan.parseHost("192.168.1.49:41475"))
        assertEquals("10.0.0.7" to 5000, PairLan.parseHost("10.0.0.7:5000"))
        assertEquals("172.20.1.1" to 5000, PairLan.parseHost("172.20.1.1:5000"))
        assertNull("a public address", PairLan.parseHost("8.8.8.8:5000")); assertNull(PairLan.parseHost("172.32.0.1:5000"))
        assertNull("a name", PairLan.parseHost("evil.example.com:5000")); assertNull(PairLan.parseHost("localhost:5000"))
        assertNull("a low port", PairLan.parseHost("192.168.1.1:80")); assertNull(PairLan.parseHost("192.168.1.1")); assertNull(PairLan.parseHost("192.168.1.999:5000"))
    }

    @Test fun aLineIsTheRequestWithANewlineAfterIt() {
        val b = PairLan.line(PairWire.request(PairProof(code = "9H4T5PBS"), "Pixel", "phone-node"))
        assertEquals('\n'.code.toByte(), b.last())
        assertEquals("phone-node", PairWire.parseRequestNode(b.copyOf(b.size - 1)))
    }

    @Test fun theAnswerCarriesTheWatchNodeSoThePhoneCanBindWithoutTheDataLayer() {
        val a = PairWire.parseResult(PairWire.result(PairReply.Accepted("Galaxy Watch4", "watch-node")))
        assertEquals(PairReply.Accepted("Galaxy Watch4", "watch-node"), a)
        assertEquals(PairReply.Refused(PairReason.WRONG), PairWire.parseResult(PairWire.result(PairReply.Refused(PairReason.WRONG))))
    }

    @Test fun theDiagnosisNamesTheLikelyCause() {
        val ok = PairFacts(userId = 0, playServices = true, connectedWatches = 1, sentOverDataLayer = 1, foundOnWifi = 0, wifiConnected = true)
        assertEquals("a Secure Folder or work profile", PairProblem.NOT_MAIN_PROFILE, PairDiagnosis.of(ok.copy(userId = 150)))
        assertEquals(PairProblem.NO_PLAY_SERVICES, PairDiagnosis.of(ok.copy(playServices = false)))
        assertEquals("no watch anywhere", PairProblem.WATCH_NOT_CONNECTED, PairDiagnosis.of(ok.copy(connectedWatches = 0, sentOverDataLayer = 0)))
        assertEquals("not on wifi", PairProblem.WIFI_UNREACHABLE, PairDiagnosis.of(ok.copy(wifiConnected = false)))
        assertEquals("connected but silent", PairProblem.WATCH_DID_NOT_ANSWER, PairDiagnosis.of(ok))
        assertEquals("a watch on the wifi that did not accept", PairProblem.WATCH_DID_NOT_ANSWER, PairDiagnosis.of(ok.copy(connectedWatches = 0, foundOnWifi = 1)))
        assertEquals("the profile wins over everything else", PairProblem.NOT_MAIN_PROFILE, PairDiagnosis.of(ok.copy(userId = 10, playServices = false, connectedWatches = 0)))
    }
}
