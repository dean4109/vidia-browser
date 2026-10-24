# Vidia Browser

A streaming-friendly web browser for cars running **Android Automotive OS** (Google built-in),
installed through your own Google Play Console internal-testing track.

- **Start page of streaming tiles:** wide cards in each service's brand colour with its official logo
  (YouTube, Netflix, Hulu, Disney+, HBO Max, Plex, Prime Video, Peacock, Paramount+, Apple TV, Twitch).
- **Add more in two taps:** **Edit → + Add**, then pick from 37 more services (Crunchyroll, Pluto TV,
  Tubi, Sling, Fubo, ESPN, Spotify and more) and the name, address, logo and colour fill in.
  Or add any website yourself, with an optional logo image and your own card colour.
- **Streaming sites just work where they can:** they load their desktop versions automatically, and
  copy-protected (Widevine) video is allowed to play wherever the service supports web browsers.
- Back / forward / reload, an address bar that also searches, a Mobile/Desktop site toggle, and fullscreen video.
- Uses the car's built-in Android System WebView, so the app stays tiny.
- Not marked distraction-optimized, so the car only allows it while parked.

## Build your own copy

1. **Fork** this repository.
2. **Add your Play upload key** as repository secrets (Settings → Secrets and variables → Actions → Secrets):
   `ANDROID_KEYSTORE_BASE64` (your `.jks` file, base64-encoded), `ANDROID_STORE_PASSWORD`,
   `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS`.
3. **Pick your package name** and add it as the repository *variable* `APPLICATION_ID`
   (same page, Variables tab), e.g. `com.yourname.browser`. Without it, builds use `app.vidia.browser`.
4. **(Optional) Use your own Brandfetch client ID** for the logos: change `BRANDFETCH_CLIENT_ID` at the
   top of the script in `app/src/main/assets/home.html` (free at developers.brandfetch.com).
5. **Build:** Actions → **Build Vidia Browser (Play bundle)** → Run workflow, then download the
   `vidia-browser-aab` artifact.
6. **Publish to your car:** in Play Console, create an app with the same package name, add the
   **Android Automotive OS** form factor with a *dedicated* release track, then upload the `.aab`
   to **Testing → Internal testing** with the track set to *Android Automotive OS only*.
   Add yourself as a tester, open the join link with the car's Google account, and install it from
   the car's Play Store.

## Notes

- Each build's version code is the workflow run number, so every upload is accepted.
- Some services refuse to play video in any browser; that's their choice, not a bug.
- Logos are loaded live from [Brandfetch](https://brandfetch.com) and belong to their respective owners.
