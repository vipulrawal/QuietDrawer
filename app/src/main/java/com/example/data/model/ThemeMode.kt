package com.example.data.model

enum class ThemeMode(val storageKey: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode {
            return entries.firstOrNull { it.storageKey == key } ?: SYSTEM
        }
    }
}

enum class ArchiveGroupMode {
    BY_DATE,
    BY_APP,
}

enum class AppFilterTab {
    ALL,
    ALLOWED_IN_BAR,
    FILTERED_TO_INBOX,
}
