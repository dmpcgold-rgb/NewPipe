package org.schabi.newpipe.error

import java.io.IOException
import java.net.HttpRetryException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.schabi.newpipe.R

class GoldErrorDiagnosisTest {
    @Test
    fun dnsCauseNestedInIOExceptionIsDetected() {
        val cause = IOException("wrapped", UnknownHostException("private.example"))
        val diagnosis = GoldErrorDiagnosis.from(cause)
        assertNotNull(diagnosis)
        assertEquals("DNS", diagnosis?.code)
        assertEquals(R.string.gold_error_dns, diagnosis?.reasonRes)
    }

    @Test
    fun timeoutShowsSpecificReason() {
        val diagnosis = GoldErrorDiagnosis.from(SocketTimeoutException())
        assertEquals("TIMEOUT", diagnosis?.code)
    }

    @Test
    fun httpRestrictionUsesStatusInsteadOfSensitiveMessage() {
        val diagnosis = GoldErrorDiagnosis.from(HttpRetryException("blocked token", 403))
        assertEquals("HTTP 403", diagnosis?.code)
        assertEquals(R.string.gold_error_http_denied, diagnosis?.reasonRes)
        assertEquals("HttpRetryException", diagnosis?.technicalType)
    }

    @Test
    fun unknownExceptionNeverDisplaysRawMessage() {
        val diagnosis = GoldErrorDiagnosis.from(IllegalStateException("secret text"))
        assertEquals("UNKNOWN", diagnosis?.code)
        assertEquals("IllegalStateException", diagnosis?.technicalType)
    }
}
