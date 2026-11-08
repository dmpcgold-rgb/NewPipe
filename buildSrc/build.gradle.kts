/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    `kotlin-dsl`
}

repositories {
    // Kotlin compiler artifacts are also on Maven Central. Prefer it to the plugin portal,
    // which can redirect large Kotlin artifacts to GitHub and intermittently return HTTP 504.
    mavenCentral()
    gradlePluginPortal()
}
