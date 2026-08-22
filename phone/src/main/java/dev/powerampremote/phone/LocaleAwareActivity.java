package dev.powerampremote.phone;

import android.content.Context;
import android.os.Bundle;

import androidx.activity.ComponentActivity;

/** Small Activity base for the API 26–32 locale fallback and consistent Android 13 behavior. */
abstract class LocaleAwareActivity extends ComponentActivity {
    private String attachedLocaleTag;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(PhoneLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        attachedLocaleTag = getResources().getConfiguration()
                .getLocales().get(0).toLanguageTag();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String selectedTag = PhoneLocale.presentationLocale(this).toLanguageTag();
        if (!selectedTag.equals(attachedLocaleTag)) recreate();
    }
}
