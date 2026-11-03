package com.tomatodev.timerdroid;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.assertEquals;

public class LanguagePolicyTest {
    @Test public void chineseVariantsAlwaysUseSimplifiedChinese() {
        for (String tag : new String[]{"zh", "zh-CN", "zh-TW", "zh-Hant-HK", "ZH_sg"}) {
            assertEquals(Locale.SIMPLIFIED_CHINESE, LanguagePolicy.select(tag, "en-US"));
        }
    }
    @Test public void laterChineseMustNotOverridePrimaryLanguage() {
        assertEquals(Locale.ENGLISH, LanguagePolicy.select("en-US", "zh-CN"));
        assertEquals(Locale.ENGLISH, LanguagePolicy.select("ja-JP", "zh-TW"));
        assertEquals(Locale.ENGLISH, LanguagePolicy.select("de-DE", "zh-CN"));
    }
    @Test public void missingLanguageFallsBackToEnglish() {
        assertEquals(Locale.ENGLISH, LanguagePolicy.select());
        assertEquals(Locale.ENGLISH, LanguagePolicy.select((String[]) null));
        assertEquals(Locale.ENGLISH, LanguagePolicy.select((String) null));
    }
}
