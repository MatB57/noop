package com.noop.ui

/**
 * Call-site shorthand for [com.noop.i18n.Fr.tr] so a UI file can wrap an English string literal as
 * `tr("Recovery")` with no import. Returns the French rendering on a French device when one exists,
 * otherwise the string unchanged. Safe in and out of `@Composable` scope (it is a plain function).
 */
fun tr(en: String): String = com.noop.i18n.Fr.tr(en)
