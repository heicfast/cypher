package chat.cypher.android.ui.theme

enum class ThemePreference(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System");

    companion object {
        fun fromStorage(raw: String?): ThemePreference =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DARK
    }
}
