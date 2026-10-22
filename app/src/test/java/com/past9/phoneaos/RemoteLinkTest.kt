package com.past9.phoneaos

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.past9.phoneaos.remote.BrainChooser
import com.past9.phoneaos.remote.BrainRef
import com.past9.phoneaos.remote.DeviceFacts
import com.past9.phoneaos.remote.Link
import com.past9.phoneaos.remote.Pairing
import com.past9.phoneaos.remote.Pick
import com.past9.phoneaos.remote.Pinning
import com.past9.phoneaos.remote.QrDecode
import com.past9.phoneaos.remote.RemoteApi
import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RpcClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Remote mode: linking to a computer (protocol s2, s3, s5, s8). No device or network outside this machine. */
class RemoteLinkTest {
    private val fp = "A".repeat(20) + "b-_" + "9".repeat(20) // 43 base64url characters

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- the QR code and the short code ----

    @Test fun aPairingQrCodeIsRead() {
        val qr = "agent://pair?v=1&mesh=m_1&node=n_desk&name=${enc("Desk PC")}&u=${enc("https://10.0.0.5:7787,https://desk.example:7787/")}&fp=$fp&c=sEcReT_123"
        val o = Pairing.parseQr(qr)!!
        assertEquals("m_1", o.meshId); assertEquals("n_desk", o.nodeId); assertEquals("Desk PC", o.name)
        assertEquals(listOf("https://10.0.0.5:7787", "https://desk.example:7787"), o.urls)
        assertEquals(fp, o.fp); assertEquals("sEcReT_123", o.code)
    }

    @Test fun commasInTheAddressListMayArriveUnencoded() {
        val o = Pairing.parseQr("agent://pair?v=1&mesh=m&node=n&name=PC&u=https://a.example:7787,https://b.example:7787&fp=$fp&c=x")!!
        assertEquals(2, o.urls.size)
    }

    @Test fun somethingThatIsNotAPairingCodeIsRefused() {
        assertNull(Pairing.parseQr("https://example.com"))
        assertNull(Pairing.parseQr("agent://pair?v=1&u=https://a.example:7787&c=x")) // no fingerprint
        assertNull(Pairing.parseQr("agent://pair?v=1&u=https://a.example:7787&fp=short&c=x"))
        assertNull(Pairing.parseQr("agent://pair?v=1&u=http://a.example:7787&fp=$fp&c=x")) // plain http only: no address left
        assertNull(Pairing.parseQr("agent://pair?v=2&u=https://a.example:7787&fp=$fp&c=x"))
        assertNull(Pairing.parseQr("agent://pair?v=1&u=https://a.example:7787&fp=$fp")) // no code
    }

    @Test fun shortCodesAreForgivingButExact() {
        assertEquals("ABCDEFGH", Pairing.shortCode("abcd-efgh"))
        assertEquals("ABCDEFGH", Pairing.shortCode(" ABCD EFGH "))
        assertEquals("XYZ23456", Pairing.shortCode("xyz23456"))
        assertNull(Pairing.shortCode("ABCD-EFG")) // 7
        assertNull(Pairing.shortCode("ABCD-EFGO")) // O is not in the alphabet
        assertNull(Pairing.shortCode("ABCD-EF10")) // nor 1 and 0
        assertNull(Pairing.shortCode("ABCD-EFGI"))
        assertEquals("ABCD-EFGH", Pairing.prettyCode("ABCDEFGH"))
    }

    @Test fun aTypedAddressBecomesAnHttpsAddressWithThePort() {
        assertEquals("https://192.168.1.20:7787", Pairing.address("192.168.1.20"))
        assertEquals("https://my-pc.local:9000", Pairing.address(" my-pc.local:9000 "))
        assertEquals("https://box.example:7787", Pairing.address("https://box.example/"))
        assertEquals("https://[fe80::1]:7787", Pairing.address("fe80::1"))
        assertEquals("https://[::1]:8000", Pairing.address("[::1]:8000"))
        assertNull(Pairing.address("http://box.example")) // never plain http
        assertNull(Pairing.address("box.example:99999"))
        assertNull(Pairing.address("box example"))
        assertNull(Pairing.address(""))
    }

    // ---- what the computer answers ----

    @Test fun thePairAnswerKeepsThePassAndTheBrains() {
        val asked = BrainRef("n_desk", "Desk PC", listOf("https://10.0.0.5:7787"), fp, 0)
        val body = """{"deviceId":"dev_1","pass":"p4ss","meshId":"m_1","node":{"id":"n_desk","name":"Desk PC"},
            "brains":[{"id":"n_desk","name":"Desk PC","urls":["https://10.0.0.5:7787"],"fp":"","priority":0},
                      {"id":"n_srv","name":"Server","urls":["https://srv.example:7787"],"fp":"${"S".repeat(43)}","priority":1}]}"""
        val p = Pairing.parsePaired(body, asked)!!
        assertEquals("p4ss", p.pass); assertEquals("dev_1", p.deviceId); assertEquals("Desk PC", p.nodeName)
        assertEquals(2, p.brains.size)
        assertEquals(fp, p.brains.first { it.id == "n_desk" }.fp) // the pinned certificate fills a blank one
    }

    @Test fun aPairAnswerWithoutBrainsUsesTheComputerThatAnswered() {
        val asked = BrainRef("node", "10.0.0.5:7787", listOf("https://10.0.0.5:7787"), fp, 0)
        val p = Pairing.parsePaired("""{"deviceId":"d","pass":"p","meshId":"m","node":{"id":"n_desk","name":"Desk PC"}}""", asked)!!
        assertEquals(listOf("n_desk"), p.brains.map { it.id }); assertEquals("Desk PC", p.brains[0].name); assertEquals(fp, p.brains[0].fp)
        assertNull(Pairing.parsePaired("""{"deviceId":"d"}""", asked)) // no pass, no link
    }

    @Test fun theDeviceListGivesOnlyLiveBrains() {
        val body = """{"devices":[
            {"id":"a","name":"Desk","roles":["brain","hands"],"urls":["https://a.example:7787"],"fp":"$fp","priority":1,"revoked":false},
            {"id":"b","name":"Phone","roles":["screen"],"urls":[],"fp":"","priority":0},
            {"id":"c","name":"Old","roles":["brain"],"urls":["https://c.example:7787"],"fp":"$fp","priority":0,"revoked":true},
            {"id":"d","payload":{"name":"Server","roles":["brain"],"urls":["https://d.example:7787"],"fp":"$fp","priority":0}}]}"""
        assertEquals(listOf("a", "d"), Pairing.parseBrains(body).map { it.id })
        assertEquals(1, Pairing.parseBrains("""[{"id":"a","roles":["brain"],"urls":["https://a.example:7787"],"fp":"$fp"}]""").size)
    }

    @Test fun theLinkSurvivesBeingStored() {
        val l = Link("m", "dev", "pass", "Desk", listOf(BrainRef("a", "Desk", listOf("https://a.example:7787"), fp, 0)), 42L, on = false)
        assertEquals(l, Link.from(l.toJson()))
        assertNull(Link.from("{}")); assertNull(Link.from(null))
    }

    @Test fun deviceFactsAreWhatTheProtocolAsksFor() {
        val f = DeviceFacts("Pixel", Pairing.kindFor(411), "0.14.0", 411, 914).toJson()
        assertEquals("phone", f.getString("kind")); assertEquals("android", f.getString("platform"))
        assertEquals(411, f.getJSONObject("screen").getInt("width")); assertTrue(f.getBoolean("canOpenLinks")); assertTrue(f.getBoolean("canReceiveFiles"))
        assertEquals("tablet", Pairing.kindFor(600))
    }

    // ---- which brain answers ----

    private val desk = BrainRef("desk", "Desk", listOf("https://desk-lan:7787", "https://desk-vpn:7787"), fp, 0)
    private val server = BrainRef("srv", "Server", listOf("https://srv:7787"), fp, 1)
    private val laptop = BrainRef("lap", "Laptop", listOf("https://lap:7787"), fp, 2)
    private val all = listOf(laptop, server, desk)

    @Test fun brainsAreTriedByPriority() {
        assertEquals(listOf("desk", "srv", "lap"), BrainChooser.ordered(all).map { it.id })
    }

    @Test fun theFirstReadyBrainWinsAndEachOfItsAddressesIsTried() = runBlocking {
        val asked = mutableListOf<String>()
        val pick = BrainChooser.firstReady(all) { _, u -> asked += u; u == "https://desk-vpn:7787" }
        assertEquals(Pick(desk, "https://desk-vpn:7787"), pick)
        assertEquals(listOf("https://desk-lan:7787", "https://desk-vpn:7787"), asked)
        val next = BrainChooser.firstReady(all) { b, _ -> b.id == "lap" }
        assertEquals("lap", next!!.brain.id)
        assertNull(BrainChooser.firstReady(all) { _, _ -> error("unreachable") })
    }

    @Test fun aBrainThatStopsAnsweringIsLeftAtOnceEvenMidTurn() {
        val m = BrainChooser.decide(all, Pick(desk, "https://desk-lan:7787"), currentOk = false, best = Pick(server, "https://srv:7787"), turnRunning = true)
        assertEquals(BrainChooser.Move.To(Pick(server, "https://srv:7787")), m)
    }

    @Test fun aHigherBrainThatComesBackTakesOverOnlyBetweenTurns() {
        val onServer = Pick(server, "https://srv:7787")
        val deskBack = Pick(desk, "https://desk-lan:7787")
        assertEquals(BrainChooser.Move.Stay, BrainChooser.decide(all, onServer, true, deskBack, turnRunning = true))
        assertEquals(BrainChooser.Move.To(deskBack), BrainChooser.decide(all, onServer, true, deskBack, turnRunning = false))
    }

    @Test fun whenNoBrainAnswersThePhoneSaysSo() {
        assertEquals(BrainChooser.Move.NoneAvailable, BrainChooser.decide(all, Pick(desk, "https://desk-lan:7787"), false, null, false))
        assertEquals(BrainChooser.Move.NoneAvailable, BrainChooser.decide(all, null, false, null, false))
        assertEquals(BrainChooser.Move.To(Pick(laptop, "https://lap:7787")), BrainChooser.decide(all, null, false, Pick(laptop, "https://lap:7787"), false))
    }

    // ---- pinned TLS against a real HTTPS server with its own self-signed certificate ----

    private class Tls(val context: SSLContext, val fp: String)

    private fun selfSigned(): Tls {
        val kpg = KeyPairGenerator.getInstance("EC"); kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(X500Name("CN=agent-node"), BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), X500Name("CN=agent-node"), kp.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(holder)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()); ks.load(null, null)
        ks.setKeyEntry("node", kp.private, CharArray(0), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(ks, CharArray(0))
        val ctx = SSLContext.getInstance("TLS"); ctx.init(kmf.keyManagers, null, null)
        return Tls(ctx, Pairing.fingerprint(cert.encoded))
    }

    private fun httpsServer(tls: Tls) = MockWebServer().apply { useHttps(tls.context.socketFactory, false) }

    @Test fun thePinnedCertificateIsAcceptedAndHealthIsRead() {
        val tls = selfSigned()
        httpsServer(tls).use { srv ->
            srv.enqueue(MockResponse().setBody("""{"id":"n1","name":"Desk","version":"0.2.40","roles":["brain"],"ready":true,"busy":false,"meshId":"m"}"""))
            srv.start()
            val api = RemoteApi(Pinning.client(OkHttpClient(), Pinning.PinnedTrust({ listOf(tls.fp) })))
            val h = api.health("https://127.0.0.1:${srv.port}")
            assertNotNull(h); assertTrue(h!!.ready); assertEquals("Desk", h.name)
        }
    }

    @Test fun anyOtherCertificateIsRefused() {
        val tls = selfSigned()
        httpsServer(tls).use { srv ->
            srv.enqueue(MockResponse().setBody("{}")); srv.start()
            val client = Pinning.client(OkHttpClient(), Pinning.PinnedTrust({ listOf(fp) })) // not this server's
            try {
                client.newCall(okhttp3.Request.Builder().url("https://127.0.0.1:${srv.port}/v1/health").build()).execute().close()
                fail("a certificate that isn't pinned must not connect")
            } catch (e: javax.net.ssl.SSLException) { /* refused */ }
            assertNull(RemoteApi(client).health("https://127.0.0.1:${srv.port}"))
        }
    }

    @Test fun theShortCodePathTrustsOnFirstUseAndRemembersTheCertificate() {
        val tls = selfSigned()
        httpsServer(tls).use { srv ->
            srv.enqueue(MockResponse().setBody("""{"deviceId":"dev_9","pass":"p","meshId":"m","node":{"id":"n1","name":"Desk"},"brains":[]}"""))
            srv.start()
            val trust = Pinning.PinnedTrust({ emptyList() }, trustOnFirstUse = true)
            val body = RemoteApi(Pinning.client(OkHttpClient(), trust)).pair("https://127.0.0.1:${srv.port}", "ABCDEFGH", DeviceFacts("P", "phone", "0.14.0", 400, 800))
            assertEquals(tls.fp, trust.seen)
            val sent = JSONObject(srv.takeRequest().body.readUtf8())
            assertEquals("ABCDEFGH", sent.getString("code")); assertEquals("screen", sent.getJSONArray("wantRoles").getString(0))
            assertEquals("android", sent.getJSONObject("device").getString("platform"))
            val p = Pairing.parsePaired(body, BrainRef("n1", "Desk", listOf("https://127.0.0.1:${srv.port}"), trust.seen!!, 0))!!
            assertEquals(tls.fp, p.brains.single().fp)
        }
    }

    @Test fun aRefusedCodeSaysWhatToDo() {
        val tls = selfSigned()
        httpsServer(tls).use { srv ->
            srv.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad code"}""")); srv.start()
            try {
                RemoteApi(Pinning.client(OkHttpClient(), Pinning.PinnedTrust({ listOf(tls.fp) }))).pair("https://127.0.0.1:${srv.port}", "x", DeviceFacts("P", "phone", "1", 1, 1))
                fail("expected a refusal")
            } catch (e: com.past9.phoneaos.remote.PairException) {
                assertTrue(e.message!!.contains("make a new one")); assertTrue(!e.unreachable)
            }
        }
    }

    // ---- the RPC socket: hello first, calls answered, events heard ----

    @Test fun theRpcSocketSaysHelloThenCallsAndHearsDeviceCommands() = runBlocking {
        val tls = selfSigned()
        val frames = LinkedBlockingQueue<JSONObject>()
        httpsServer(tls).use { srv ->
            srv.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                // Answer the phone's close, so the server can shut down at once.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val f = JSONObject(text); frames.add(f)
                    if (f.getString("type") == "hello")
                        webSocket.send("""{"type":"event","name":"device.command","payload":{"id":"cmd_1","kind":"notify","title":"Hi","body":"Done"}}""")
                    if (f.getString("type") == "call")
                        webSocket.send(JSONObject().put("type", "result").put("id", f.getString("id")).put("ok", true).put("value", JSONObject().put("running", false)).toString())
                }
            }))
            srv.start()
            val opened = CountDownLatch(1)
            val events = LinkedBlockingQueue<Pair<String, Any?>>()
            val rpc = RpcClient(Pinning.client(OkHttpClient(), Pinning.PinnedTrust({ listOf(tls.fp) })), "https://127.0.0.1:${srv.port}", "the-pass",
                { JSONObject().put("device", DeviceFacts("P", "phone", "0.14.0", 400, 800).toJson()).put("tools", org.json.JSONArray()) },
                object : RpcClient.Listener {
                    override fun onOpen(rpc: RpcClient) { opened.countDown() }
                    override fun onEvent(rpc: RpcClient, name: String, payload: Any?) { events.add(name to payload) }
                    override fun onClosed(rpc: RpcClient, why: String) {}
                })
            rpc.connect()
            assertTrue(opened.await(10, TimeUnit.SECONDS))
            val hello = frames.poll(10, TimeUnit.SECONDS)!!
            assertEquals("hello", hello.getString("type")); assertEquals("phone", hello.getJSONObject("device").getString("kind"))
            val upgrade = srv.takeRequest()
            assertEquals("Bearer the-pass", upgrade.getHeader("Authorization")); assertEquals("/v1/rpc", upgrade.path)
            val ev = events.poll(10, TimeUnit.SECONDS)!!
            assertEquals("device.command", ev.first); assertEquals("notify", (ev.second as JSONObject).getString("kind"))
            val v = rpc.call("chat.state", listOf()) as JSONObject
            assertEquals(false, v.getBoolean("running"))
            val call = frames.poll(10, TimeUnit.SECONDS)!!
            assertEquals("chat.state", call.getString("method"))
            rpc.close()
        }
    }

    // ---- row ids and the QR camera decoder ----

    @Test fun rowIdsKeepTheChatInOrderAndMapBack() {
        val ids = RemoteIds()
        val a = ids.local("row-a", 1_700_000_000_000L)
        val b = ids.local("row-b", 1_700_000_000_000L) // same millisecond
        val c = ids.local("row-c", 1_700_000_000_001L)
        assertTrue(a != b); assertTrue(maxOf(a, b) < c)
        assertEquals(a, ids.local("row-a", 1_700_000_000_000L))
        assertEquals("row-b", ids.remote(b))
    }

    private fun qrLuminance(text: String, size: Int, stride: Int): ByteArray {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(stride * size) { i -> val x = i % stride; val y = i / stride; (if (x < size && m.get(x, y)) 0 else 255).toByte() }
    }

    @Test fun aQrCodeIsReadFromACameraFrame() {
        val text = "agent://pair?v=1&mesh=m&node=n&name=Desk&u=https%3A%2F%2F10.0.0.5%3A7787&fp=$fp&c=abc"
        assertEquals(text, QrDecode.decodeLuminance(qrLuminance(text, 400, 400), 400, 400, 400))
        assertEquals(text, QrDecode.decodeLuminance(qrLuminance(text, 400, 448), 400, 400, 448)) // padded rows, as cameras send them
        assertNull(QrDecode.decodeLuminance(ByteArray(100 * 100) { 120 }, 100, 100, 100))
        assertNotNull(Pairing.parseQr(text))
    }

    // ---- project work shown like a helper, pictures, images in lent-tool results ----

    private fun row(id: String, at: Long, kind: String, text: String, chat: String) =
        JSONObject().put("id", id).put("createdAt", at).put("kind", kind).put("text", text).put("meta", JSONObject().put("chat", chat).toString())

    @Test fun aProjectChatShowsAsAHelperWithTheProjectsIcon() {
        val ids = RemoteIds()
        val project = JSONObject().put("name", "Kitchen site").put("icon", JSONObject().put("glyph", "K").put("color", "ember")).put("chats", org.json.JSONArray().put("pc_k"))
        val now = 1_800_000_000_000L
        val rows = listOf(row("a", now - 5000, "user", "Fix the gallery", "pc_k"), row("b", now - 3000, "activity", "Opened the repo", "pc_k"))
        val working = com.past9.phoneaos.remote.RemoteRows.projectHelper(project, "pc_k", rows, running = true, ids = ids, now = now)!!
        val m = JSONObject(working.meta)
        assertEquals("helper", working.kind); assertEquals("Fix the gallery", working.text)
        assertEquals("Kitchen site", m.getString("label")); assertEquals("working", m.getString("state")); assertEquals("Opened the repo", m.getString("now"))
        assertEquals("ember", m.getString("projectColor")); assertEquals("pc_k", m.getString("projectChat"))
        val done = com.past9.phoneaos.remote.RemoteRows.projectHelper(project, "pc_k", rows + row("c", now - 1000, "agent", "Gallery fixed.", "pc_k"), running = false, ids = ids, now = now)!!
        assertEquals(working.id, done.id) // the same row, updated in place
        assertEquals("done", JSONObject(done.meta).getString("state")); assertEquals("Gallery fixed.", JSONObject(done.meta).getString("result"))
        assertNull(com.past9.phoneaos.remote.RemoteRows.projectHelper(project, "pc_k", rows, running = false, ids = ids, now = now + 4 * 86_400_000L)) // long finished
        assertNull(com.past9.phoneaos.remote.RemoteRows.projectHelper(project, "pc_k", emptyList(), running = true, ids = ids, now = now))
    }

    @Test fun picturesInChatRowsPointAtTheirCopiesOnThePhone() {
        val meta = JSONObject().put("image", "/c/shot.png").put("images", org.json.JSONArray().put("/c/a.jpg"))
            .put("attachments", org.json.JSONArray().put(JSONObject().put("path", "/c/b.png").put("mime", "image/png")).put(JSONObject().put("path", "/c/doc.pdf").put("mime", "application/pdf")))
        assertEquals(listOf("/c/shot.png", "/c/a.jpg", "/c/b.png"), com.past9.phoneaos.remote.RemoteRows.mediaPaths(meta))
        val out = com.past9.phoneaos.remote.RemoteRows.withLocalMedia(meta, mapOf("/c/shot.png" to "/p/1.png", "/c/a.jpg" to "/p/2.jpg", "/c/b.png" to "/p/3.png"))
        assertEquals("/p/1.png", out.getString("image"))
        assertEquals(listOf("/p/2.jpg", "/p/3.png"), (0 until out.getJSONArray("images").length()).map { out.getJSONArray("images").getString(it) })
    }

    @Test fun imagesFromALentToolTravelAsBase64Parts() {
        val f = java.io.File.createTempFile("shot", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        assertEquals("plain text", com.past9.phoneaos.remote.DeviceTools.withImages("plain text"))
        val parts = com.past9.phoneaos.remote.DeviceTools.withImages("Here is the page:" + "\n" + "[[image:" + f.absolutePath + "]]") as org.json.JSONArray
        assertEquals("Here is the page:", parts.getJSONObject(0).getString("text"))
        val img = parts.getJSONObject(1)
        assertEquals("image", img.getString("type")); assertEquals("image/png", img.getString("mime")); assertEquals("AQID", img.getString("data"))
    }
}
