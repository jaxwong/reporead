package com.reporead.android.auth

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which stored text is a session save() could have written, and which Keystore failures mean it can never be read. */
class StoredSessionTest {
    // A 12-byte IV (0..11) and a 16-byte ciphertext of 0xff, as Android's Base64 NO_WRAP writes them: '+/' alphabet,
    // '=' padding, no line breaks.
    private val iv = "AAECAwQFBgcICQoL"
    private val tagOnly = "/////////////////////w=="

    @Test
    fun anEnvelopeSaveWritesIsReadWithoutTheKeystore() {
        val envelope = parseEnvelope("$iv:$tagOnly")!!
        assertArrayEquals(ByteArray(12) { it.toByte() }, envelope.iv)
        assertArrayEquals(ByteArray(16) { -1 }, envelope.ciphertext)
    }

    @Test
    fun textSaveCouldNotHaveWrittenIsNoEnvelope() {
        for ((case, stored) in
            listOf(
                "empty" to "",
                "one part" to iv,
                "three parts" to "$iv:$tagOnly:$tagOnly",
                "empty parts" to ":",
                "not Base64" to "$iv:not*base64",
                "line-wrapped Base64" to "$iv:$tagOnly\n",
                "11-byte IV" to "AAECAwQFBgcICQo=:$tagOnly",
                "16-byte IV" to "$tagOnly:$tagOnly",
                "15-byte ciphertext, shorter than the GCM tag" to "$iv:////////////////////",
            )) {
            assertNull(case, parseEnvelope(stored))
        }
    }

    @Test
    fun aKeyThatIsGoneOrCanNeverDecryptThisCiphertextIsPermanent() {
        for (failure in
            listOf(KeyPermanentlyInvalidatedException(), UnrecoverableKeyException(), AEADBadTagException())) {
            assertEquals(failure.javaClass.simpleName, StoredSession.PermanentlyInvalid, keystoreFailure(failure))
        }
    }

    @Test
    fun aKeyLoadFailureKeystoreSaysMayPassKeepsTheSession() {
        val failure = UnrecoverableKeyException("Failed to obtain information about key")
        val result = keystoreFailure(failure, keyLoadMayPass = true)
        assertSame(failure, (result as StoredSession.TemporarilyUnavailable).cause)
        assertEquals(StoredSession.PermanentlyInvalid, keystoreFailure(failure, keyLoadMayPass = false))
    }

    @Test
    fun anyOtherKeystoreFailureKeepsTheSessionWithItsCause() {
        for (failure in listOf(InvalidKeyException("Keystore operation failed"), IllegalBlockSizeException())) {
            val result = keystoreFailure(failure)
            assertTrue(failure.javaClass.simpleName, result is StoredSession.TemporarilyUnavailable)
            assertSame(failure, (result as StoredSession.TemporarilyUnavailable).cause)
        }
    }
}
