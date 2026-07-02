package com.anezium.rokidrelay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AdbKeyGeneratorTest {
    @Test
    fun generatedPublicKeyUsesAdbRsaPublicKeyFormat() {
        val generated = AdbKeyGenerator.generate(comment = "relay-test@phone")
        val parts = generated.publicKey.split(" ")

        assertEquals(2, parts.size)
        assertEquals("relay-test@phone", parts[1])
        assertEquals(524, Base64.getDecoder().decode(parts[0]).size)
        assertTrue(generated.privateKeyPem.startsWith("-----BEGIN PRIVATE KEY-----"))
        assertTrue(generated.privateKeyPem.endsWith("-----END PRIVATE KEY-----\n"))
    }
}
