# 多重计时器 · TimerDroid Haoke

[下载安卓正式版 / Android download](https://haoke.org/software/720ccd10b30051dd)

浩科优化版 3.1.2-haoke.1 提供暖色界面、并行倒计时、快捷计时、独立暂停和加时、预设铃声、顺序流程、本地备份与签名版本通知。

Haoke edition 3.1.2-haoke.1 adds a warm UI, independent parallel timers, quick presets, pause and time extension, per-preset alarms, saved workflows, local backup and signed update notices.

- Upstream: [TimerDroid by Martin Hoffmann and contributors](https://github.com/hoffimar/timerdroid), base commit `e9fcb467f9bb64865ae59dc27c360ca938de9a70`.
- License: GNU GPL v3. Noto Sans SC is licensed under SIL OFL 1.1. The update components are adapted from the Haoke WPGen GPLv3 implementation.
- Build: JDK 21 and Android SDK 35. Run `./gradlew testReleaseUnitTest assembleRelease` from the project root. The release build is unsigned by default. Android SDK 18 is the declared minimum and target SDK is 26.
- EdDSA 0.3.0 source: [Maven Central source archive](https://repo.maven.apache.org/maven2/net/i2p/crypto/eddsa/0.3.0/eddsa-0.3.0-sources.jar). Android support-library versions are pinned in `app/build.gradle`.

## Upstream README

# TimerDroid

Start, manage and create your own timers. Perfect as kitchen timer. A widget is also available to start your timers.

Some timers are predefined, and you can create your own timers in several categories.

## Features

* Start as many parallel timers as you want
* Timers run in background
* 1x1 widget for your favorite timers
* Favorites
* Create your own timers and categorize them
* Create your own categories with icons
* You can also start a timer without saving it, just press the play button after entering a time
* To delete all timers (incl. the predefined ones), press the menu button on the start screen and choose the option to delete all
* Tablet-optimized version also available

---
[![Get it on F-Droid](get_it_on_f-droid.png?raw=true)](https://f-droid.org/repository/browse/?fdfilter=timerdroid&fdid=com.tomatodev.timerdroid)

[![Flattr this git repo](http://api.flattr.com/button/flattr-badge-large.png)](https://flattr.com/submit/auto?user_id=hoffimar&url=https://github.com/hoffimar/timerdroid&title=TimerDroid%20Android%20App&language=en_US&tags=github,android&category=software)
