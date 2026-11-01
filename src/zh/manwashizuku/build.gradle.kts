import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manwa Shizuku"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "漫蛙（新版）"
        lang = "zh"
        baseUrl = "https://manwadz.cc"
    }

    deeplink {
        host("manwadz.cc")
        host("mwmwbu.cc")
        path("/comic/..*")
    }
}
