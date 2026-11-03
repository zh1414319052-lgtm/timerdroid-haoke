package com.tomatodev.timerdroid;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LanguageResourcesTest {
    @Test public void wrappedResourcesCoverSimplifiedChineseAndEnglish() {
        Context base = InstrumentationRegistry.getTargetContext();
        for (String tag : new String[]{"zh-CN", "zh-TW", "zh-Hant-HK", "en-US", "ja-JP"}) {
            Context localized = LanguagePolicy.forLocale(base, LanguagePolicy.select(tag, "zh-CN"));
            assertEquals(tag.startsWith("zh") ? "多重计时器" : "TimerDroid", localized.getString(R.string.app_name));
            assertEquals(tag.startsWith("zh") ? "保存" : "Save", localized.getString(R.string.create_timer_save));
        }
    }
    @Test public void launcherResourcesHonorTheFirstLocaleBeforeProcessStartup() {
        Context base = InstrumentationRegistry.getTargetContext();
        for (String primary : new String[]{"en-US", "ja-JP", "de-DE", "fr-FR"}) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.setLocales(LocaleList.forLanguageTags(primary+",zh-CN"));
            Context host = base.createConfigurationContext(config);
            assertEquals("TimerDroid", host.getString(R.string.app_name));
            assertEquals("New Timer", host.getString(R.string.menu_new_timer));
        }
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocales(LocaleList.forLanguageTags("zh-TW,en-US"));
        assertEquals("多重计时器", base.createConfigurationContext(config).getString(R.string.app_name));
    }
}
