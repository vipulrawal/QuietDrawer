package com.example.service

import android.app.Notification
import android.app.Person
import android.net.Uri
import android.os.Build
import android.os.Bundle

/**
 * Extracts and validates durable deep-link metadata (`https://` URLs and safe contact URIs)
 * from Android [Notification] extras so QuietDrawer can still navigate into the originating
 * app's specific screen even after a full phone reboot destroys OS [android.app.PendingIntent] binders.
 */
object DeepLinkExtractor {

    private const val MAX_URL_LENGTH = 2_048
    private const val MAX_PERSON_URI_LENGTH = 512

    private val HTTPS_URL_REGEX = Regex("""https://[^\s<>"'`{}\[\]|\\^]+""", RegexOption.IGNORE_CASE)
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', ')', ']', '!', '?', '"', '\'')
    private val ALLOWED_PERSON_SCHEMES = setOf("mailto", "tel", "smsto", "sms")

    fun extractDeepLinkUrl(extras: Bundle?): String? {
        if (extras == null) return null

        val candidateTexts = buildList {
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let(::add)
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let(::add)
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let(::add)
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.let(::add)
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.let(::add)

            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
                line?.toString()?.let(::add)
            }

            extractMessagingBundleTexts(extras).forEach(::add)

            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let(::add)
        }

        for (text in candidateTexts) {
            val found = findFirstValidHttpsUrl(text)
            if (found != null) return found
        }
        return null
    }

    fun extractPersonUri(extras: Bundle?): String? {
        if (extras == null) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val messagingPerson = getParcelableCompat(
                extras = extras,
                key = Notification.EXTRA_MESSAGING_PERSON,
                clazz = Person::class.java,
            )
            messagingPerson?.uri?.let { normalizeValidPersonUri(it) }?.let { return it }

            val peopleList = getParcelableArrayListCompat(
                extras = extras,
                key = Notification.EXTRA_PEOPLE_LIST,
                clazz = Person::class.java,
            )
            peopleList?.forEach { person ->
                person?.uri?.let { normalizeValidPersonUri(it) }?.let { return it }
            }
        }

        @Suppress("DEPRECATION")
        val legacyPeople = extras.getStringArray(Notification.EXTRA_PEOPLE)
        legacyPeople?.forEach { rawUri ->
            normalizeValidPersonUri(rawUri)?.let { return it }
        }

        return null
    }

    fun isValidDeepLinkUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || url.length > MAX_URL_LENGTH) return false
        return try {
            val parsed = Uri.parse(url)
            parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun isValidPersonUri(uriString: String?): Boolean {
        return normalizeValidPersonUri(uriString) != null
    }

    private fun findFirstValidHttpsUrl(text: String): String? {
        if (text.isBlank()) return null
        val match = HTTPS_URL_REGEX.find(text) ?: return null
        val cleaned = match.value.trimEnd(*TRAILING_PUNCTUATION)
        return cleaned.takeIf { isValidDeepLinkUrl(it) }
    }

    private fun normalizeValidPersonUri(rawUri: String?): String? {
        val trimmed = rawUri?.trim()?.takeIf { it.isNotBlank() && it.length <= MAX_PERSON_URI_LENGTH }
            ?: return null
        return try {
            val parsed = Uri.parse(trimmed)
            val scheme = parsed.scheme?.lowercase() ?: return null
            if (scheme !in ALLOWED_PERSON_SCHEMES) return null
            if (parsed.schemeSpecificPart.isNullOrBlank()) return null
            trimmed
        } catch (_: Exception) {
            null
        }
    }

    private fun extractMessagingBundleTexts(extras: Bundle): List<String> {
        val bundles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.mapNotNull { it as? Bundle }
                ?.toTypedArray()
        } ?: return emptyList()

        return buildList {
            for (bundle in bundles) {
                bundle.getCharSequence("text")?.toString()?.let(::add)
            }
        }
    }

    private fun <T> getParcelableCompat(extras: Bundle, key: String, clazz: Class<T>): T? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(key, clazz)
            } else {
                @Suppress("DEPRECATION")
                clazz.cast(extras.getParcelable(key))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> getParcelableArrayListCompat(
        extras: Bundle,
        key: String,
        clazz: Class<T>,
    ): List<T?>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelableArrayList(key, clazz)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelableArrayList<android.os.Parcelable>(key)
                    ?.map { if (clazz.isInstance(it)) clazz.cast(it) else null }
            }
        } catch (_: Exception) {
            null
        }
    }
}
