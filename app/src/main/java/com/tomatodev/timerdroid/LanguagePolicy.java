package com.tomatodev.timerdroid;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

/** One policy for activities, services, providers and widget contexts. */
public final class LanguagePolicy {
    private LanguagePolicy() {}

    public static Locale select(String... languageTags) {
        String first = languageTags == null || languageTags.length == 0
                || languageTags[0] == null ? "" : languageTags[0];
        String language = first.replace('_', '-').split("-", 2)[0];
        return "zh".equalsIgnoreCase(language) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
    }

    public static Locale systemLanguage() {
        Configuration system = Resources.getSystem().getConfiguration();
        Locale first = Build.VERSION.SDK_INT >= 24 && !system.getLocales().isEmpty()
                ? system.getLocales().get(0) : system.locale;
        return select(first == null ? "" : first.getLanguage());
    }

    public static Context wrap(Context context) {
        return forLocale(context, systemLanguage());
    }

    public static Context forLocale(Context context, Locale selected) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(selected);
        if (Build.VERSION.SDK_INT >= 24) config.setLocales(new LocaleList(selected));
        return context.createConfigurationContext(config);
    }

    public static void refresh(Resources resources) {
        Configuration config = new Configuration(resources.getConfiguration());
        Locale selected = systemLanguage();
        config.setLocale(selected);
        if (Build.VERSION.SDK_INT >= 24) config.setLocales(new LocaleList(selected));
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }
}
