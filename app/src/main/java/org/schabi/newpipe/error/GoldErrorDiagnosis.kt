package org.schabi.newpipe.error

import android.os.Parcelable
import androidx.annotation.StringRes
import com.google.android.exoplayer2.upstream.HttpDataSource
import java.io.IOException
import java.net.ConnectException
import java.net.HttpRetryException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.parcelize.Parcelize
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.exceptions.ExtractionException

/**
 * Actionable, structured diagnostics. Exception messages, URLs, cookies and
 * stack traces are intentionally not displayed or copied into the diagnosis.
 */
@Parcelize
data class GoldErrorDiagnosis(
    @StringRes val reasonRes: Int,
    @StringRes val adviceRes: Int,
    val technicalType: String,
    val code: String,
    val networkRelated: Boolean
) : Parcelable {
    companion object {
        private val httpCodePattern = Regex(
            """(?i)(?:\bHTTP(?:/\d(?:\.\d)?)?\s+|\bstatus(?:\s+code)?\s*[:=]?\s*)([1-5]\d\d)\b"""
        )

        fun from(throwable: Throwable?): GoldErrorDiagnosis? {
            if (throwable == null) {
                return null
            }
            val causes = mutableListOf<Throwable>()
            val seen = HashSet<Throwable>()
            var current: Throwable? = throwable
            while (current != null && causes.size < 12 && seen.add(current)) {
                causes.add(current)
                current = current.cause
            }
            for (cause in causes.asReversed()) {
                val httpCode = when (cause) {
                    is HttpDataSource.InvalidResponseCodeException -> cause.responseCode

                    is HttpRetryException -> cause.responseCode()

                    else -> cause.message?.let {
                        httpCodePattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
                    }
                }
                if (httpCode != null) {
                    return httpDiagnosis(httpCode, cause.javaClass.simpleName)
                }
                when (cause) {
                    is UnknownHostException -> return GoldErrorDiagnosis(
                        R.string.gold_error_dns,
                        R.string.gold_error_dns_advice,
                        cause.javaClass.simpleName,
                        "DNS",
                        true
                    )

                    is SocketTimeoutException -> return GoldErrorDiagnosis(
                        R.string.gold_error_timeout,
                        R.string.gold_error_timeout_advice,
                        cause.javaClass.simpleName,
                        "TIMEOUT",
                        true
                    )

                    is SSLException -> return GoldErrorDiagnosis(
                        R.string.gold_error_tls,
                        R.string.gold_error_tls_advice,
                        cause.javaClass.simpleName,
                        "TLS",
                        true
                    )

                    is ConnectException -> return GoldErrorDiagnosis(
                        R.string.gold_error_connect,
                        R.string.gold_error_connect_advice,
                        cause.javaClass.simpleName,
                        "CONNECT",
                        true
                    )

                    is SocketException -> return GoldErrorDiagnosis(
                        R.string.gold_error_socket,
                        R.string.gold_error_socket_advice,
                        cause.javaClass.simpleName,
                        "SOCKET",
                        true
                    )
                }
            }
            for (cause in causes.asReversed()) {
                when (cause) {
                    is ExtractionException -> return GoldErrorDiagnosis(
                        R.string.gold_error_extraction,
                        R.string.gold_error_extraction_advice,
                        cause.javaClass.simpleName,
                        "EXTRACTOR",
                        false
                    )

                    is IOException -> return GoldErrorDiagnosis(
                        R.string.gold_error_generic_network,
                        R.string.gold_error_generic_network_advice,
                        cause.javaClass.simpleName,
                        "NETWORK_UNKNOWN",
                        true
                    )
                }
            }
            return GoldErrorDiagnosis(
                R.string.gold_error_unknown,
                R.string.gold_error_unknown_advice,
                throwable.javaClass.simpleName,
                "UNKNOWN",
                false
            )
        }

        private fun httpDiagnosis(status: Int, type: String): GoldErrorDiagnosis {
            val (reason, advice) = when (status) {
                401, 403 -> R.string.gold_error_http_denied to R.string.gold_error_http_denied_advice
                404, 410 -> R.string.gold_error_http_missing to R.string.gold_error_http_missing_advice
                429 -> R.string.gold_error_http_limit to R.string.gold_error_http_limit_advice
                in 500..599 -> R.string.gold_error_http_server to R.string.gold_error_http_server_advice
                else -> R.string.gold_error_http_other to R.string.gold_error_http_other_advice
            }
            return GoldErrorDiagnosis(reason, advice, type, "HTTP " + status, true)
        }
    }
}
