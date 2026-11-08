/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses pasted or shared links without contacting a web server or inspecting protected streams.
 */
public final class LinkQueueLinks {
    private static final Pattern WEB_URL =
            Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE = Pattern.compile(
            "/serie/([^/]+)/staffel-(\\d+)/episode-(\\d+)(?:/|$)",
            Pattern.CASE_INSENSITIVE);
    private static final String[] EXTENSIONS =
            {".mp4", ".m4v", ".webm", ".mov", ".mp3", ".m4a", ".ogg", ".oga"};

    private LinkQueueLinks() {
    }

    public static List<String> extractLinks(final String input) {
        final LinkedHashSet<String> links = new LinkedHashSet<>();
        if (input == null || input.isEmpty()) {
            return new ArrayList<>();
        }
        final Matcher matcher = WEB_URL.matcher(input);
        while (matcher.find()) {
            String candidate = matcher.group();
            while (candidate.endsWith(".") || candidate.endsWith(",")
                    || candidate.endsWith(")") || candidate.endsWith("]")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            if (isSafeHttps(candidate)) {
                links.add(candidate);
            }
        }
        return new ArrayList<>(links);
    }

    public static boolean isSafeHttps(final String value) {
        try {
            final URI uri = new URI(value);
            final String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null || uri.getUserInfo() != null
                    || host.equalsIgnoreCase("localhost") || host.endsWith(".local")
                    || host.endsWith(".localhost")) {
                return false;
            }
            // The importer is only for web URLs, not internal addresses or local network files.
            if (host.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")
                    || host.contains(":")) {
                return false;
            }
            return true;
        } catch (final URISyntaxException | IllegalArgumentException ex) {
            return false;
        }
    }

    // Direct files can use Android DownloadManager; HTML pages and HLS playlists cannot.
    public static boolean isDirectMedia(final String value) {
        if (!isSafeHttps(value)) {
            return false;
        }
        try {
            final String path = new URI(value).getPath().toLowerCase(Locale.ROOT);
            for (final String extension : EXTENSIONS) {
                if (path.endsWith(extension)) {
                    return true;
                }
            }
            return false;
        } catch (final URISyntaxException ex) {
            return false;
        }
    }

    /**
     * Matches only the official VOE hostname and its subdomains.
     *
     * @param value candidate web address
     * @return whether this is a VOE HTTPS address
     */
    public static boolean isVoeUrl(final String value) {
        if (!isSafeHttps(value)) {
            return false;
        }
        try {
            final String host = new URI(value).getHost();
            return host != null && ("voe.sx".equalsIgnoreCase(host)
                    || host.toLowerCase(Locale.ROOT).endsWith(".voe.sx"));
        } catch (final URISyntaxException ex) {
            return false;
        }
    }

    public static String describe(final String value) {
        if (!isSafeHttps(value)) {
            return "Ungültiger Link";
        }
        try {
            final URI uri = new URI(value);
            final Matcher episode = EPISODE.matcher(uri.getPath());
            if (episode.find()) {
                final String show = episode.group(1).replace('-', ' ');
                return show + " • Staffel " + episode.group(2) + " • Folge "
                        + episode.group(3) + " (Webseite)";
            }
            if (isDirectMedia(value)) {
                return "Direkte Mediendatei";
            }
            return uri.getHost() + " (Webseite)";
        } catch (final URISyntaxException ex) {
            return "Ungültiger Link";
        }
    }
}
