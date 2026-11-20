---
title: 📱 On this phone, and Google Photos picked into it
kind: feature
area: library/playback
status: built (ADR 176); video, audio and photos checked on totum-api35; Show on a screen (a browser on the TV or PC opened on the phone's screen page) finished 2026-11-20; Google Photos tested only against a mock Google and waiting on Dewi's Google Cloud client; photo on a Chromecast or the Pi's targets not built
updated: 2026-11-20
---

# 📱 On this phone

Decision and reasoning: [ADR 176](../adr/0176-on-this-phone-is-a-source-behind-the-one-player.md). A Library tile
(**📱 On this phone**) opens a page with four tabs.

## What it does

- **🎞️ Videos and 🎵 Audio** on the phone (Movies, Music, Recordings, Downloads, DCIM videos, anything in MediaStore) are
  listed a hundred at a time, newest first, with search (by name; a song's artist and album too), sort (Newest, Oldest,
  A to Z, Longest, Largest) and a chip per folder. Tapping a row plays it through the one player: the queue, groups, skip
  silence, boost, speed, resume, stats, Listen and Watch, sleep fade and the phone's own casting all apply, because the
  item is a file handle exactly like a download. Songs the phone tags as music are music; audiobooks are audiobooks; a voice
  recording is talk and keeps skip silence and speed.
- **🖼️ Photos**: a grid and a viewer (swipe, pinch, double-tap, a ▶️ slideshow every 3, 5 or 10 s that keeps the screen on).
  **🖥️ Show on a screen** lists the browsers that have opened the phone's screen page (a TV's or a PC's) and mirrors the
  photo on view to the one you pick, slideshow included, until **⏹️ Stop showing** or the screen goes; with no screen open
  the viewer says how to get one.
- **☁️ Google Photos**: **➕ Pick from Google Photos** opens Google's own picking page in the browser; what you choose is
  copied onto the phone (⬇️ on the progress strip, Cancel, a result) and shows in the same grid and, for videos, plays like
  any file. Until the build has a Google client it says "⚙️ Google Photos needs one-off setup" and does nothing.
- **Permissions** are asked for on the page, by a button, never at launch: allowed, **partial** ("only the photos and videos
  you chose": ➕ Choose more), or refused (then ⚙️ Open Settings). Each tab is judged on its own kind.
- **Consistent with the rest of the app:** a phone row shows no follow mark, no Download and no Add to group; a song tagged
  as music looks like music everywhere.
- **Not sent to the Pi.** The Pi's speakers, TVs and browsers cannot reach a file only the phone holds, so a phone file
  always plays on the phone (a note on the page says so; Play on… is not offered for it and Send to… says why on the strip).

## Google Photos: what Dewi has to do once

Totum's other Google sign-ins cannot carry the Photos Picker scope (the TV device-code flow only allows email, openid,
profile, drive.file, drive.appdata and the two YouTube scopes; the Drive backup signs in to nothing), so it needs its own
OAuth client:

1. https://console.cloud.google.com, choose or create a project (say "Totum").
2. **APIs & Services → Library**, search **Google Photos Picker API** (not "Google Picker API") and **Enable**.
3. **OAuth consent screen / Google Auth Platform**: app name Totum, your email, audience **External**, add your own account as a
   **Test user**.
4. **Data access → Add or remove scopes**: `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`.
5. **Credentials → Create credentials → OAuth client ID → Desktop app**. Copy the Client ID and Client secret.
6. In `~/.gradle/gradle.properties`: `totum.photosClientId=…` and `totum.photosClientSecret=…`; rebuild. For the released
   APK add the GitHub Actions secrets `TOTUM_PHOTOS_CLIENT_ID` and `TOTUM_PHOTOS_CLIENT_SECRET` (the release step already
   passes them). Never commit them: the repo is public.
7. While the consent screen is in *Testing*, refresh tokens last 7 days, so Totum would ask you to sign in weekly. Publishing it
   (*In production*) stops that; an unverified app then shows Google's warning screen to whoever signs in.

The first real run (tap ➕ Pick, sign in in the browser, choose two photos and a video, come back) is the check nobody has done.

## Files

| Piece | Where |
|---|---|
| Ids, entries, the playable mapping, the picture rule | `core/domain/…/PhoneMedia.kt`, `Picture.kt`, `Playable.kt` (`toPlayableOrNull`), `Following.kt` |
| MediaStore query plan, row mapping, access state machine, paging browser, in-memory source | `core/data/…/data/phone/` (`MediaStorePlan.kt`, `PhoneMediaAccess.kt`, `PhoneMediaBrowser.kt`, `InMemoryPhoneMedia.kt`) |
| Google Photos copies | `core/data/…/data/phone/ImportedPhoneMedia.kt` |
| Google sign-in (PKCE + loopback), Picker API, the pick state machine | `core/data/…/data/phone/google/` (`GoogleOAuth.kt`, `PhotosPickerApi.kt`, `GooglePhotosPicker.kt`) |
| ContentResolver adapter, permission state, thumbnails, Android sign-in and browser | `app/…/phonemedia/` |
| Screens | `app/…/ui/library/` (`PhoneMediaScreen`, `PhoneMediaList`, `PhoneBrowse`, `PhotoGrid`, `PhotoViewer`, `PhotoScreens`, `GooglePhotosTab`, `PhoneMediaTile`) |
| Photo on a screen | `core/cast/…/SharedPictures.kt`, `CastStreamServer.sharePicture`, `screen.html` (`show`), `PlaybackController.showPictureOnScreen` / `clearScreen`, `PcScreens`, `app/…/phonemedia/ScreenPictures.kt` |
| No Pi for phone files | `TargetsRepository.take`, `SendToTarget.send`, `PlayOnSheetAction` |

## Tests

See [the coverage map](../tests/_index.md): `:core:domain` `PhoneMediaTest`; `:core:data` `MediaStorePlanTest`,
`PhoneMediaAccessTest`, `PhoneMediaBrowserTest`, `ImportedPhoneMediaTest`, `GoogleOAuthTest`, `GooglePhotosPickerTest`; `:app`
`TargetsRepositoryTest` (phone files stay home), `PhoneItemsAreShownTest`, `ScreenPicturesTest`; `:core:cast` `SharedPicturesTest`, `ScreenLinkTest` (a show command); instrumented `PhoneMediaPlaybackTest` (a pushed
video, a song, speed and place, 330 photos paged) and `PhoneMediaScreenTest` (the tabs, search, permissions in each state,
the viewer and slideshow, Google needing setup).

## Owed

- A photo on a Chromecast or the Pi's targets, and phone files to the Pi's targets: [todo](../todos/phone-media-follow-ups.md).
- Phone items in search results; removing a Google Photos copy from the viewer.
