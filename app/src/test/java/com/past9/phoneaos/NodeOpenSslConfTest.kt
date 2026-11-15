package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.SubKind
import com.past9.phoneaos.runtime.SubscriptionRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The bundled node is built against Termux's prefix, so its default OpenSSL config lives in
 * /data/data/com.termux/files/usr/etc/tls/openssl.cnf. Android answers that path with a permission
 * error, even when Termux is not installed, and node then exits before it runs anything. Every
 * place node starts gets its environment from [SubscriptionRuntime.env], so the config has to be
 * set there, and the file has to exist before node is started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeOpenSslConfTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Every kind of runtime hands node the app's own empty config, inside its private folder. */
    @Test fun everyKindPointsNodeAtAnEmptyConfigItOwns() {
        val expected = File(File(context.filesDir, "runtime"), "openssl.cnf").path
        SubKind.values().forEach { kind ->
            val path = SubscriptionRuntime(context, kind).env()["OPENSSL_CONF"]
            assertNotNull("${kind.name}: env() must set OPENSSL_CONF, or node dies on Termux's path", path)
            assertEquals("${kind.name}: the config must be ours, not Termux's", expected, path)
            val conf = File(path!!)
            assertTrue("${kind.name}: ${conf.path} must exist before node starts", conf.isFile)
            assertEquals("${kind.name}: an empty config means OpenSSL reads no directives", 0L, conf.length())
        }
    }

    /** A file that is already there is left alone: env() runs on every turn and must not rewrite it. */
    @Test fun anExistingConfigIsKept() {
        val rt = SubscriptionRuntime(context, SubKind.CLAUDE)
        val conf = File(rt.env().getValue("OPENSSL_CONF"))
        conf.writeText("# kept\n")
        assertEquals(conf.path, rt.env().getValue("OPENSSL_CONF"))
        assertEquals("# kept\n", conf.readText())
    }
}
