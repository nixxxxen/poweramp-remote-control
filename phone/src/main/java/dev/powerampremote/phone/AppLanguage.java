package dev.powerampremote.phone;

import java.util.List;
import java.util.Locale;

/** Stable persisted choices and the pure ru/en presentation fallback policy. */
enum AppLanguage {
    SYSTEM("system", null),
    RUSSIAN("ru", Locale.forLanguageTag("ru")),
    ENGLISH("en", Locale.ENGLISH);

    private final String storageTag;
    private final Locale explicitLocale;

    AppLanguage(String storageTag, Locale explicitLocale) {
        this.storageTag = storageTag;
        this.explicitLocale = explicitLocale;
    }

    String storageTag() {
        return storageTag;
    }

    Locale explicitLocale() {
        return explicitLocale;
    }

    Locale resolve(List<Locale> systemLocales) {
        if (explicitLocale != null) return explicitLocale;
        Locale primary = systemLocales == null || systemLocales.isEmpty()
                ? null : systemLocales.get(0);
        return primary != null && "ru".equalsIgnoreCase(primary.getLanguage())
                ? RUSSIAN.explicitLocale : ENGLISH.explicitLocale;
    }

    static AppLanguage fromStorageTag(String tag) {
        if (RUSSIAN.storageTag.equals(tag)) return RUSSIAN;
        if (ENGLISH.storageTag.equals(tag)) return ENGLISH;
        return SYSTEM;
    }

    static AppLanguage fromPlatformLocale(Locale locale) {
        if (locale == null) return SYSTEM;
        if ("ru".equalsIgnoreCase(locale.getLanguage())) return RUSSIAN;
        if ("en".equalsIgnoreCase(locale.getLanguage())) return ENGLISH;
        return SYSTEM;
    }
}
