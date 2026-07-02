package com.anezium.rokidrelay.phone

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64

internal object AdbKeyGenerator {
    data class GeneratedKey(
        val privateKeyPem: String,
        val publicKey: String,
    )

    fun generate(comment: String = "rokid-relay@phone"): GeneratedKey {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(MODULUS_BITS, RSAKeyGenParameterSpec.F4))
        val pair = generator.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey
        return GeneratedKey(
            privateKeyPem = privateKeyPem(pair.private.encoded),
            publicKey = adbPublicKey(publicKey, comment),
        )
    }

    internal fun adbPublicKey(publicKey: RSAPublicKey, comment: String): String {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent.toLong()
        val buffer = ByteBuffer.allocate(ADB_PUBLIC_KEY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MODULUS_WORDS)
        buffer.putInt(n0inv(modulus).toInt())
        buffer.put(fixedLittleEndian(modulus, MODULUS_BYTES))
        buffer.put(fixedLittleEndian(BigInteger.ONE.shiftLeft(MODULUS_BITS * 2).mod(modulus), MODULUS_BYTES))
        buffer.putInt(exponent.toInt())
        val encoded = Base64.getEncoder().encodeToString(buffer.array())
        return "$encoded $comment"
    }

    private fun privateKeyPem(encoded: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    private fun n0inv(modulus: BigInteger): Long {
        val two32 = BigInteger.ONE.shiftLeft(32)
        val lowWord = modulus.and(two32.subtract(BigInteger.ONE))
        val inverse = lowWord.modInverse(two32)
        return two32.subtract(inverse).and(two32.subtract(BigInteger.ONE)).toLong()
    }

    private fun fixedLittleEndian(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        val unsigned = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= size) { "value does not fit $size bytes" }
        val out = ByteArray(size)
        unsigned.indices.forEach { index ->
            out[index] = unsigned[unsigned.size - 1 - index]
        }
        return out
    }

    private const val MODULUS_BITS = 2048
    private const val MODULUS_BYTES = MODULUS_BITS / 8
    private const val MODULUS_WORDS = MODULUS_BYTES / 4
    private const val ADB_PUBLIC_KEY_BYTES = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4
}
