/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

/** Keeps original music titles and adds only the requested prefix before a safe file extension. */
public final class BatchFilenameHelper {
    private static final String UNSAFE = "\\/:*?\"<>|";
    private static final int MAX_FILENAME_BYTES = 240;

    private BatchFilenameHelper() { }

    /**
     * Keeps the original title's capitalization, punctuation and spacing where file-safe.
     * Only invalid filesystem characters and excessively long names are normalized.
     *
     * @param prefix optional text before every music title (including numbered prefixes)
     * @param originalTitle original song title from the source
     * @param extension actual downloaded codec's extension, not a requested fake conversion
     * @return filename with original title and selected audio extension
     */
    @NonNull
    public static String audioFilename(@Nullable final String prefix,
                                       @Nullable final String originalTitle,
                                       @NonNull final String extension) {
        final String raw = (prefix == null ? "" : prefix)
                + (originalTitle == null ? "" : originalTitle);
        final StringBuilder normalized = new StringBuilder();
        final int allowedBytes = MAX_FILENAME_BYTES - extension.getBytes(
                StandardCharsets.UTF_8).length - 1;
        int usedBytes = 0;
        for (int i = 0; i < raw.length();) {
            final int codePoint = raw.codePointAt(i);
            i += Character.charCount(codePoint);
            final String part = Character.isISOControl(codePoint)
                    || UNSAFE.indexOf(codePoint) >= 0 ? "_" : new String(
                            Character.toChars(codePoint));
            final int bytes = part.getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + bytes > allowedBytes) {
                break;
            }
            normalized.append(part);
            usedBytes += bytes;
        }
        String base = normalized.toString();
        if (base.isBlank()) {
            base = "audio";
        }
        return base + "." + extension;
    }
}
