---
title: "📱 On this phone" is one more source behind the one player, with Google Photos picked into it
kind: adr
status: accepted
updated: 2026-11-14
---

# 176. 📱 On this phone is one more source behind the one player, with Google Photos picked into it

- Status: Accepted
- Date: 2026-11-14
- Builds on: [ADR 154](0154-jellyfin-is-browsed-and-streamed-through-the-pi.md) (the other "browse a source, play it through
  the queue" tile), [ADR 70](0070-listen-and-watch-follow-the-picture-not-the-pillar.md) (Listen and Watch follow the
  picture), [ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md) (recorded exceptions)

## Context

Dewi, 2026-11-14: *"From our app I wanna be able to view ANY media on our phone please? Within the app? So we get the
benefits of our app?? … Including things like Google photos connectors etc."* Videos, music and recordings sit on the phone
and play in other apps without Totum's speed, silence skipping, boost, resume, stats, queue, groups and casting. Photos
have no home in Totum at all.

## Decision

### Video and audio: files on the phone are playable items, not a second player

- **MediaStore is read, in pages.** `MediaStorePhoneSource` queries Video, Audio and Images with
  `QUERY_ARG_LIMIT`/`QUERY_ARG_OFFSET`, **100 rows a page**, sorted newest, oldest, A to Z, longest or largest, with a
  folder filter (`bucket_display_name`) and a search over title, file name (and artist and album for songs) done by
  MediaStore itself, so a phone with thousands of files never holds more than the pages scrolled to. The query is built by
  one pure function (`PhoneMediaQuery.toPlan()`) and a row becomes an entry by one pure function (`PhoneMediaRow.toEntry`),
  both JVM-tested. Ringtones, notifications and alarms are left out of Audio.
- **An item is a `PlayableItem` with a file handle.** A video is `PlayHandle.LocalVideo(path)`, a song or recording is
  `PlayHandle.Podcast(localPath = path)`: the handles `routeNow` and `playedFromDisk` already route for a download, so the
  one `PlaybackController`, the mini and full players, skip silence, boost, speed, resume, stats, history, the queue,
  groups, sleep fade, Listen/Watch and (on the phone) casting treat it as they treat a downloaded file. Nothing was added to
  the player. `MediaStore.IS_MUSIC` makes a song `MediaContentKind.MUSIC` (plain, like music everywhere), `IS_AUDIOBOOK` an
  audiobook, anything else (a voice recording) is standard talk, so it keeps skip silence and speed.
- **The id carries the path.** `phone:<kind>:<mediaStoreId>.<base64url(path)>`. The menus turn a `MediaItem` back into
  something playable with `MediaItem.toPlayableOrNull()`, which has only the id to go on; a path in the id means Add to
  queue, Play next, Pins, history and a restored backup work for these items with no new column in any table. The price is
  that a file moved or renamed is a new item (its saved place does not follow it), and that the id, which appears in
  diagnostics, contains the file's name (base64, not plain, and no more than the title already logged beside it).
- **Thumbnails need no new field.** The thumbnail address is an ordinary https address on the reserved host
  `phone-media.totum.invalid`; a Coil fetcher (`PhoneThumbnailFetcher`, first in the shared loader) answers it from
  `ContentResolver.loadThumbnail` (or `ThumbnailUtils` for the Google Photos copies). Items stay plain data.
- **Rows are consistent with every other list.** `Shown.PHONE` (a phone glyph and the label "On this phone"; a song tagged
  as music is still `Shown.MUSIC`), no follow mark (`FollowedSources.nothingToFollow`), Listen and Watch offered by
  `Picture` (video yes, audio no), and the row's tap plays a phone item although it has no stream address.
- **Recorded exceptions (ADR 108).** A file on the phone has **no Download** (it is already here), **no Add to group**, and
  **no "📡 Play on…" or Send to…**: the Pi's speakers and TVs and a browser fetch from the Pi, and cannot reach a file only
  this phone holds. `TargetsRepository.take` refuses to redirect any batch containing one (it plays here, logged), the item
  menu does not offer Play on…, and Send to… says so on the progress strip. Casting from the phone itself (a Chromecast the
  phone drives) already serves a local file the way it serves a download and is unchanged. Serving the file from the phone
  to the Pi is the way to lift this and is owed (see Not built).

### Permissions: in context, never at launch

- The Library tile opens the page; the page shows the explainer and **🔓 Allow access**, and only that button raises the
  system dialog (so a video is never on screen under it: the `POST_NOTIFICATIONS` lesson in CLAUDE.md). It asks for
  `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_MEDIA_IMAGES` and `READ_MEDIA_VISUAL_USER_SELECTED` in one request.
- The state is a pure function (`PhoneMediaPermissions.access` / `gate`): each kind is **Full**, **Partial** (Android 14's
  "only the photos you chose": video and photos only, there is no partial audio) or **None**. Nothing granted and never
  asked shows the ask; nothing granted after asking shows **⚙️ Open Settings** (Android stops showing the dialog after two
  refusals, so that is the only thing that can help); partial shows "ℹ️ only the photos and videos you chose" with
  **➕ Choose more**. Each tab is judged on its own kind, so refusing audio leaves video usable. The page re-reads on resume,
  so coming back from Settings needs no tap.

### Photos

- A grid (`LazyVerticalGrid`, adaptive 104dp cells, the same paging, search, sort and folder chips) and a viewer: a pager
  with swipe, pinch, double-tap and a pan clamped to the picture, and a **slideshow** every 3, 5 or 10 seconds that keeps
  the screen awake, loads the next page when it nears the end and wraps at the last photo. The viewer reads the grid's own
  browser, so what you scrolled to is what you swipe through.
- Photos are not queue items (nothing plays), so they have no `PlayableItem`; they are `PhoneMediaEntry`s.
- **🖥️ Show on a screen (finished 2026-11-20).** The viewer lists the PC screens already present on the phone's own screen
  page (`PlaybackController.screens`, the same list the 🖥️ button on the player uses: a browser on the TV or PC that opened
  the phone's address and said hello). Choosing one **mirrors the viewer to it**: the photo on view, and each one swiped or
  slideshown to, is shown there until "⏹️ Stop showing" (or the screen goes away, which ends it by itself, logged).
  - *Reused:* the screen link and its page (`ScreenLink`, `screen.html`), the phone-served cast HTTP server
    (`CastStreamServer`, which gained `sharePicture` and a keyed `/p/<token>/picture.<ext>` route next to its `/c/<token>`
    sessions, ranges through the existing `LocalRanges`), the service's custom-command seam (`showPictureOnScreen` /
    `clearScreen` beside `playOnScreen`), and the progress strip (`operations.showing`, so a failure is visible).
  - *New:* a `show` command the page understands (a full-screen `<img>` over the video stage; `load` and `stop` take it down;
    a playing video is paused first, and the phone's own screen player is paused with it), `SharedPictures` (the latest 16
    shared files, one unguessable token each, only browser-showable types), and `AndroidScreenPictures`, which brings a photo
    down to a 1920 px JPEG in the app's cache (a 12 MP or HEIC original is neither quick nor showable over Wi-Fi), keeping
    32.
  - *Not covered:* the Pi's browser targets and Chromecast/LG targets cannot show a photo (the Pi's page and deck have no
    picture item, and a Chromecast needs an image `MediaItem` through `TotumCastConverter`); both would need a Pi deploy and
    a TV to prove. A TV is reached today through its own browser opening the phone's screen page.

### Google Photos: pick, then copy onto the phone

- **Google closed the Library API to anything an app did not create (2025). The way in is the Photos Picker API:** the app
  creates a session, sends the person to Google Photos' own page (`pickerUri`) to choose, polls the session until
  `mediaItemsSet`, lists what was chosen and downloads each item (`baseUrl=d` for a photo, `=dv` for a video, with the
  bearer token; the addresses live for 60 minutes). Totum **copies the picked items into its own storage**
  (`files/google-photos/`, with an index), so they are permanent, work offline, and appear in the same viewer and, for
  videos, play through the same player as a file on the phone (they are `phone:` items whose path is that copy). Importing
  runs as one tracked operation on the progress strip (⬇️ Importing from Google Photos, a count, Cancel, a result), the
  session is ended afterwards, an item picked twice is skipped (the key is a hash of Google's item id), and one item failing
  names itself without stopping the rest.
- **Nothing the existing sign-ins carry reaches it, so it needs its own OAuth client, and only Dewi can make that.**
  - The YouTube sign-in is the TV device-code flow. Google's limited-input-device flow is **only** allowed to carry
    `email`, `openid`, `profile`, `drive.appdata`, `drive.file`, `youtube` and `youtube.readonly`
    (developers.google.com/identity/protocols/oauth2/limited-input-device, read 2026-11-14), and `photospicker.mediaitems.readonly`
    is not among them, so the YouTube client cannot be asked for it.
  - The Drive backup (ADR 53) is the system file picker and signs in to nothing, so there is no Google credential to extend.
  - So Totum has a **Google Photos sign-in of its own: the installed-app flow with PKCE**: the system browser, a redirect to
    `http://127.0.0.1:<port>/` answered by a one-shot loopback listener (`LoopbackRedirect`, `state` checked), the code
    exchanged with the verifier, the tokens kept and refreshed (`GoogleOAuthClient`). No Google Play services dependency and
    no signing-certificate fingerprint to register. It needs a **Desktop app** OAuth client, whose id and secret are build
    values (`totum.photosClientId` / `totum.photosClientSecret`, or `TOTUM_PHOTOS_CLIENT_ID` / `TOTUM_PHOTOS_CLIENT_SECRET`,
    which `ci.yml`'s release build passes from secrets of the same names). Google treats a desktop client's secret as not
    secret, but the repo is public, so it is never committed.
  - **Until those two values exist the tab says "⚙️ Google Photos needs one-off setup" and calls nothing**
    (`PickerState.NeedsSetup`); no credentials were invented.
- **The steps only Dewi can do** (also in the feature doc):
  1. Open https://console.cloud.google.com and choose or create a project (for example "Totum").
  2. **APIs & Services → Library**, search for **Google Photos Picker API** (not "Google Picker API", a different product)
     and **Enable** it.
  3. **APIs & Services → OAuth consent screen** (Google Auth Platform): app name Totum, your email as support and developer
     contact, audience **External**, add your own Google account as a **Test user**.
  4. **Data access / Scopes → Add or remove scopes**: add `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`.
  5. **Credentials → Create credentials → OAuth client ID → Application type: Desktop app**, name it Totum; copy the **Client ID**
     and **Client secret**.
  6. Put them in `~/.gradle/gradle.properties` as `totum.photosClientId=…` and `totum.photosClientSecret=…`, rebuild, and
     (for the released APK) add the two GitHub Actions secrets `TOTUM_PHOTOS_CLIENT_ID` and `TOTUM_PHOTOS_CLIENT_SECRET`.
  7. **Watch the 7 days.** While the consent screen is in *Testing*, Google expires a refresh token after 7 days, so Totum
     would ask for sign-in weekly. Setting the publishing status to *In production* removes that; an unverified app then
     shows a "Google hasn't verified this app" screen to the person signing in, which is fine for one person but is Dewi's
     call (I could not confirm from the docs whether this scope counts as sensitive, which decides whether verification would
     ever be required).
- **Honesty about verification.** The sign-in, the picker calls and the import are tested against a mock Google (the token
  endpoint, the three picker calls, the downloads, expiry, cancellation, a 403 naming the likely cause, a refresh that fails).
  None of it has run against the real Google, because no client exists. The first real run is the check.

### Credentials never leave in a state dump

`google_photos_auth` (the access and refresh tokens, `SharedPrefsGoogleTokenStore`) is a credential file: it is in
`StateDump.CREDENTIAL_FILES`, so a diagnostics report or "send my state" never carries it. It was missing in the first build;
`StateDumpTest` ("credential files are left out whole") failed with `"google_photos_auth" is in the dump` before it was added.

## Not built, and why

- **A photo on a Chromecast or on the Pi's targets.** Needs an image `MediaItem` through `TotumCastConverter`, or a Pi deploy
  that teaches its deck and page a picture item; neither can be proved without a TV or a Pi, so the phone-served screen
  page above is what is built. Recorded as a todo with both designs.
- **Files on the phone to the Pi's speakers, TVs and browsers** (above): the phone would serve the file over the same
  `CastStreamServer`, with a keyed address the Pi fetches on the LAN.
- **Search results.** The one search seam returns a sealed `SearchHit` (podcast, video, music, torrent) matched exhaustively
  in many places; a phone hit is a new case in each, so it was not cheap. The page's own search box covers it.
- **Removing a Google Photos copy** from inside the viewer (the library supports it, `ImportedPhoneMedia.remove`; no button yet).
- **"Play" queues the pages loaded so far**, not every file on the phone: the queue gets the rows scrolled to, as it gets a
  Jellyfin listing's loaded page.

## Consequences

- The first time the page is opened the system shows one or two dialogs (audio, then photos and videos, with Android's
  limited-access choice); the page tells you in its own words what each refusal means.
- A phone file keeps its saved place only while its path is unchanged.
- Dewi's emulator and phone show real MediaStore content; the tests insert their own copyright-free files (a tone, colour
  bars from the repo's `clip.mp4`, solid-colour JPEGs) under `totum-phone-media-test*` folders and reuse them on later runs.
- Verified on `totum-api35`: the tile, the ask card and the real Android dialogs, a list of real MediaStore rows with
  thumbnails, playing a pushed video through the queue from its own file, and paging through 2,300 pushed photos.
