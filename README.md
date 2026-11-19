<div align="center">

# BetterTube

**A privacy-friendly YouTube client for Android, forked from [LibreTube](https://github.com/libre-tube/LibreTube).**<br>
BetterTube keeps everything LibreTube does and focuses on two things: audio-only mode that really saves data, and a calm, modern interface with its own typeface, a floating dock and fluid motion.

[![GPL-v3](assets/widgets/license-widget.svg)](https://www.gnu.org/licenses/gpl-3.0.en.html)

</div>

> [!IMPORTANT]
> BetterTube is an independent fork and is **not affiliated with or endorsed by the LibreTube team**.
> Please report BetterTube problems [here](../../issues), not to LibreTube.

<div align="center">
  <img src="assets/readme/bettertube-preview.png" width="90%" alt="BetterTube: Home with the floating dock, the video page, Subscriptions in light mode and Search">
  <br>
  <sub>Rendered from the app's layouts: Home, video page, Subscriptions (light) and Search. Coloured boxes stand in for thumbnails and channel pictures.</sub>
</div>

## What's different from LibreTube

| | LibreTube | BetterTube |
|---|---|---|
| **Switching to audio-only mid-video** | Keeps downloading the video stream in the background, wasting data and memory | Stops requesting video right away and only downloads audio |
| **Seeing what's downloaded** | Not possible in the app | A small data label in both players; tap it for an audio vs. video breakdown |
| **Opening a video** | Black screen with a spinner | The thumbnail you tapped stays in place and fades into the video |
| **Audio player** | Rows of buttons; play button that resizes and shifts its neighbours | Minimal: round fixed-size play button, Chapters and Video at hand, the rest in ⋮, blurred-artwork backdrop |
| **Navigation** | Full-width bottom bar | Floating, glassy, icons-only dock; content flows underneath |
| **Look and feel** | Material 3 Expressive defaults | Own design: Plus Jakarta Sans, flat cards on one 16dp grid, quiet secondary text |
| **Motion** | Default fades | Screens rise in on emphasized easing, lists ease in item by item, cards respond to touch |
| **Loading** | Spinners | Card-shaped placeholders that gently pulse |

### 🎧 Audio-only mode that actually saves data

Most YouTube videos are now streamed with a protocol called SABR, where the app tells YouTube's server which audio and video streams it wants.
In LibreTube, if you start a video and then switch to audio-only, the app keeps telling the server it still wants video. The server keeps sending it, and the app quietly downloads and stores video it never plays.

BetterTube tells the server to stop sending video as soon as video is turned off, and safely ignores any video data that was already on its way. Switching back to video works as before.

### 📊 See it for yourself: the data label

Both players show a small label with the data used so far for the current video: a 🎬 video icon on the video page (next to the view count) and a 🔊 speaker icon in the audio player (between the timestamps).
Tap it for an inline breakdown of **audio** vs. **video** data; tap again to hide it. After switching to audio-only, the breakdown confirms it, e.g. *"Audio only · no video data for 42 s"*, and the video number stops growing.

## Download

There are no official releases yet. Every push is built automatically by GitHub Actions:

1. Open the [latest successful build](../../actions/workflows/ci.yml?query=is%3Asuccess) and sign in to GitHub.
2. Under **Artifacts**, download **app** (a zip file).
3. Unzip it and install `app-debug.apk`. Android may ask you to allow installs from your browser or file manager.

> [!NOTE]
> - These are debug builds. They install as **BetterTube** alongside the official LibreTube app instead of replacing it.
> - All builds are signed with the same key, so a new build installs as an **update** and keeps your data. The build number is shown in Android's app info (e.g. version `32.1-ci.14`).
> - Builds from before 19 November 2026 used a different key each time: uninstall that version once (export a backup first), then install the new one.

## Changelog

### 19 November 2026: the BetterTube redesign

**Audio-only and data**
- Switching to audio-only mid-video now really stops video downloads (the SABR client stops requesting video and ignores video data already on its way)
- Data label in both players (🎬 / 🔊 icon + total); tap for a live audio vs. video breakdown

**Design**
- Plus Jakarta Sans typeface across the whole app, on the full Material 3 type scale
- Floating, glassy, icons-only bottom dock: translucent surface, soft sheen, light-catching rim and shadow; content scrolls underneath it
- Header (bold BetterTube wordmark) scrolls fully away with the content and returns when scrolling up
- New feed cards: large rounded thumbnails, full-width titles, one quiet line with a small avatar, channel, views and age
- Flat, outline-free chips; quiet empty states; one filled primary action per screen; everything on a 16dp grid
- Redesigned screens: Home, Subscriptions, Library, Search, Channel, Playlist, video page, comments, bottom sheets, dialogs and settings headings
- Slim round loading spinner, and pulsing card-shaped placeholders on Home and Subscriptions

**Motion**
- New screen transitions: screens rise into place on emphasized easing; going back mirrors it
- Lists ease in item by item; cards and rows give a gentle press response
- Opening a video: the tapped thumbnail (including DeArrow thumbnails) stays in place and fades into the first frame

**Players**
- Minimal audio player: no previous/next buttons, a round play button that never shifts its neighbours, Chapters and Video as the only quick actions; queue, speed, sleep timer, autoplay and sharing in ⋮; blurred-artwork backdrop (Android 12+)
- Video page: actions are Share, Audio, Save, Download (PiP and Screenshot removed; PiP still works when leaving the app); quiet full-width Comments row
- Video controls: soft gradients instead of a flat dark overlay; autoplay moved from the top-bar switch into the ⚙ menu; round play button
- Mini player is a floating rounded card above the dock (video and audio)

**Subscriptions and Library**
- Subscriptions: one header row with the channel list, a channel-group selector (incl. "Edit groups") and the filter
- Library: compact Watch history and Downloads shortcuts

**Fixes**
- Fixed a minimised player sometimes switching to another video when tapping the screen behind it
- Fixed the data label showing "audio only" while a video was playing

**Under the hood**
- App renamed to BetterTube
- Ships with a set of default settings, applied once on first start without overwriting existing ones
- Debug builds are signed with a shared key so new builds install as updates; CI uploads the APK for every push

## Everything else from LibreTube

BetterTube includes all of LibreTube's features:

- No ads or tracking
- Subscriptions and subscription groups
- Playlists, playlist bookmarks and watch/search history
- Downloads and background playback
- Optional accounts via [Piped](https://github.com/TeamPiped/Piped)
- [SponsorBlock](https://sponsor.ajay.app/), [Return YouTube Dislike](https://www.returnyoutubedislike.com/) and [DeArrow](https://dearrow.ajay.app/)

For questions about these features, see LibreTube's [FAQ](https://libre-tube.github.io/#faq).

## Building from source

Requirements: JDK 17 and the Android SDK (platform 36).

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Keeping up with LibreTube

```sh
git remote add upstream https://github.com/libre-tube/LibreTube.git
git fetch upstream master
git merge upstream/master
```

## Credits and license

BetterTube is built on [LibreTube](https://github.com/libre-tube/LibreTube) by Bnyro and the LibreTube contributors. Nearly all of the app is their work. If you find it useful, consider [supporting LibreTube](https://github.com/sponsors/Bnyro).

App icons by [XelXen](https://github.com/XelXen) and [Margot Albert-Heuzey](https://margotdesign.ovh).

[Plus Jakarta Sans](https://github.com/tokotype/PlusJakartaSans) by Tokotype, used under the SIL Open Font License 1.1 (see `app/src/main/assets/licenses/PlusJakartaSans-OFL.txt`).

Like LibreTube, BetterTube is licensed under the [GNU General Public License v3.0](LICENSE).
