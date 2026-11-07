package com.dewijones92.totum.ui.settings

import androidx.annotation.StringRes
import com.dewijones92.totum.R

internal enum class SettingsSubPage {
    IMPORT_EXPORT,
    EXSURGE,
    DAILY_ALARMS,
    WORK_APPS,
    BEDTIME,
    DIAGNOSTICS,
    NOT_FOR_ME,
    KID_TV,
}

internal enum class SettingsRow(
    @StringRes val label: Int,
    @StringRes val section: Int,
    @StringRes val summary: Int? = null,
    val keywords: String = "",
    val page: SettingsSubPage? = null,
) {
    QUALITY_WIFI(
        R.string.settings_quality_wifi,
        R.string.settings_quality_section,
        keywords = "resolution hd 1080p 720p"
    ),
    QUALITY_CELLULAR(
        R.string.settings_quality_cellular,
        R.string.settings_quality_section,
        keywords = "resolution data 4g 5g cellular",
    ),
    DATA_LIMIT(
        R.string.settings_data_limit,
        R.string.settings_quality_section,
        R.string.settings_data_limit_summary,
        "mobile data budget cellular gigabyte megabyte metered sound only",
    ),
    FEED_HIDDEN(R.string.settings_feed_hidden, R.string.settings_feed_section, R.string.settings_feed_hidden_summary),
    REWIND(R.string.settings_rewind, R.string.settings_queue_section, R.string.settings_rewind_summary, "resume pause"),
    SILENT_TAIL(
        R.string.settings_silent_tail,
        R.string.settings_queue_section,
        R.string.settings_silent_tail_summary,
        "credits outro end",
    ),
    GAPLESS(R.string.settings_gapless, R.string.settings_queue_section, R.string.settings_gapless_summary, "crossfade"),
    EXPLORE(R.string.settings_explore, R.string.settings_queue_section, R.string.settings_explore_summary, "related"),
    EXPLORE_CAP(
        R.string.settings_explore_cap,
        R.string.settings_queue_section,
        R.string.settings_explore_cap_summary,
        "limit related",
    ),
    TIDY_QUEUE(
        R.string.settings_tidy_queue,
        R.string.settings_queue_section,
        R.string.settings_tidy_queue_summary,
        "clean up played old failed downloads weekly",
    ),
    TIDY_AGE(
        R.string.settings_tidy_age,
        R.string.settings_queue_section,
        keywords = "tidy clean up days older played",
    ),
    INBOX_ALL(
        R.string.settings_inbox_all,
        R.string.settings_inbox_section,
        R.string.settings_inbox_all_summary,
        "choose sources inbox",
    ),
    SILENCE(
        R.string.settings_silence_section,
        R.string.settings_silence_section,
        R.string.settings_silence_supporting,
        "pauses quiet gaps",
    ),
    SPEECH_BOOST(
        R.string.speech_boost_section,
        R.string.speech_boost_section,
        R.string.speech_boost_supporting,
        "volume loudness gate voice",
    ),
    AUTO_DOWNLOAD(
        R.string.settings_auto_download,
        R.string.settings_downloads_section,
        R.string.settings_auto_download_summary,
        "offline",
    ),
    AUTO_DOWNLOAD_WIFI(
        R.string.settings_auto_download_wifi,
        R.string.settings_downloads_section,
        R.string.settings_auto_download_wifi_summary,
        "mobile data cellular",
    ),
    COPY_HOME(
        R.string.settings_copy_home,
        R.string.settings_downloads_section,
        R.string.settings_copy_home_summary,
        "pi server",
    ),
    EXSURGE(
        R.string.exsurge_settings_row,
        R.string.exsurge_name,
        keywords = "stand up study walk sitting surgius loquax",
        page = SettingsSubPage.EXSURGE,
    ),
    EXSURGE_ENABLED(
        R.string.exsurge_settings_enabled,
        R.string.exsurge_name,
        R.string.exsurge_settings_enabled_summary,
        "exsurge on off",
        SettingsSubPage.EXSURGE,
    ),
    EXSURGE_HOURS(
        R.string.exsurge_settings_hours,
        R.string.exsurge_name,
        keywords = "exsurge from until days weekdays",
        page = SettingsSubPage.EXSURGE,
    ),
    EXSURGE_TIMING(
        R.string.exsurge_settings_timing,
        R.string.exsurge_name,
        keywords = "exsurge sitting limit break length steps snooze walking window",
        page = SettingsSubPage.EXSURGE,
    ),
    EXSURGE_BEHAVIOUR(
        R.string.exsurge_settings_behaviour,
        R.string.exsurge_name,
        keywords = "exsurge escalate quiet office voice volume pause playback over apps cue",
        page = SettingsSubPage.EXSURGE,
    ),
    EXSURGE_DESTINATION(
        R.string.exsurge_settings_destination,
        R.string.exsurge_name,
        keywords = "exsurge loquax package route",
        page = SettingsSubPage.EXSURGE,
    ),
    DAILY_ALARMS(
        R.string.dailyalarm_settings_row,
        R.string.dailyalarm_settings_row,
        keywords = "alarm reminder pickup morning",
        page = SettingsSubPage.DAILY_ALARMS,
    ),
    WORK_APPS(
        R.string.work_apps_name,
        R.string.work_apps_name,
        R.string.work_apps_intro,
        "slack outlook teams quiet notifications work hours snooze",
        SettingsSubPage.WORK_APPS,
    ),
    BEDTIME(
        R.string.bedtime_name,
        R.string.bedtime_name,
        R.string.bedtime_intro,
        "sleep timer night fade stop after item wake",
        SettingsSubPage.BEDTIME,
    ),
    NOT_FOR_ME(
        R.string.not_for_me_settings_row,
        R.string.not_for_me_settings_row,
        R.string.not_for_me_intro,
        "suppress hide never suggest explore ask dislike",
        SettingsSubPage.NOT_FOR_ME,
    ),
    KID_TV(
        R.string.kidtv_title,
        R.string.kidtv_title,
        R.string.kidtv_intro,
        "kid child toddler tv cast playlist loop lock",
        SettingsSubPage.KID_TV,
    ),
    YOUTUBE_ALERTS(
        R.string.settings_youtube_alerts,
        R.string.settings_subscriptions_section,
        R.string.settings_youtube_alerts_summary,
        "notification channel uploads",
    ),
    PODCAST_ALERTS(
        R.string.settings_podcast_alerts,
        R.string.settings_subscriptions_section,
        R.string.settings_podcast_alerts_summary,
        "notification episode",
    ),
    IMPORT_EXPORT(
        R.string.settings_import_export,
        R.string.settings_subscriptions_section,
        keywords = "opml backup file",
        page = SettingsSubPage.IMPORT_EXPORT,
    ),
    SKIP_CATEGORIES(
        R.string.settings_skip_section,
        R.string.settings_skip_section,
        R.string.settings_skip_supporting,
        "sponsorblock sponsor self promotion interaction intro outro preview filler music off-topic",
    ),
    VIEW_DIAGNOSTICS(
        R.string.diagnostics_view,
        R.string.settings_diagnostics_section,
        R.string.diagnostics_view_supporting,
        "log stalls timings",
        SettingsSubPage.DIAGNOSTICS,
    ),
    HOME_SERVER(
        R.string.settings_home_server,
        R.string.settings_diagnostics_section,
        keywords = "pi prowlarr sign in domain torrent",
    ),
    CHECK_CONTENT(
        R.string.settings_check_content,
        R.string.settings_diagnostics_section,
        R.string.settings_check_content_summary,
        "refresh new episodes uploads",
    ),
    SEND_DIAGNOSTICS(
        R.string.settings_diagnostics_send,
        R.string.settings_diagnostics_section,
        R.string.settings_diagnostics_summary,
        "report bug feedback screenshot",
    ),
    DRIVE_BACKUP(
        R.string.drive_backup_title,
        R.string.drive_backup_section,
        keywords = "google drive restore folder",
    ),
    MORNING_DIGEST(
        R.string.settings_morning_digest,
        R.string.settings_morning_section,
        R.string.settings_morning_digest_summary,
        "notification news groups summary daily",
    ),
    MORNING_FOR_YOU(
        R.string.settings_morning_for_you,
        R.string.settings_morning_section,
        R.string.settings_morning_for_you_summary,
        "recommendations suggestions picks playlist daily ai",
    ),
}
