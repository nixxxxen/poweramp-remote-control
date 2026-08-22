package dev.powerampremote.phone;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Applies the selected presentation locale without changing process-default protocol behavior. */
final class PhoneLocale {
    private PhoneLocale() {
    }

    static Context wrap(Context base) {
        Locale locale = presentationLocale(base);
        Configuration configuration = new Configuration(
                base.getResources().getConfiguration()
        );
        configuration.setLocales(new LocaleList(locale));
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    static AppLanguage selected(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager localeManager = context.getSystemService(LocaleManager.class);
            if (localeManager != null) {
                LocaleList applicationLocales = localeManager.getApplicationLocales();
                if (!applicationLocales.isEmpty()) {
                    return AppLanguage.fromPlatformLocale(applicationLocales.get(0));
                }
                return AppLanguage.SYSTEM;
            }
        }
        return new AppLanguageStore(context).load();
    }

    static Locale presentationLocale(Context context) {
        return selected(context).resolve(systemLocales(context));
    }

    static boolean apply(Context context, AppLanguage language) {
        if (!new AppLanguageStore(context).save(language)) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager localeManager = context.getSystemService(LocaleManager.class);
            if (localeManager != null) {
                LocaleList applicationLocales = language == AppLanguage.SYSTEM
                        ? LocaleList.getEmptyLocaleList()
                        : new LocaleList(language.explicitLocale());
                localeManager.setApplicationLocales(applicationLocales);
            }
        }
        return true;
    }

    private static List<Locale> systemLocales(Context context) {
        LocaleList locales = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager localeManager = context.getSystemService(LocaleManager.class);
            if (localeManager != null) locales = localeManager.getSystemLocales();
        }
        if (locales == null || locales.isEmpty()) {
            locales = Resources.getSystem().getConfiguration().getLocales();
        }
        List<Locale> result = new ArrayList<>(locales.size());
        for (int index = 0; index < locales.size(); index++) {
            result.add(locales.get(index));
        }
        return result;
    }
}
