// Cypher — root build (same pipeline shape as bitchat-android, trimmed to
// the single-transport mesh app).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
