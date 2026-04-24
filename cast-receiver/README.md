# Tyfloradio Cast receiver

This directory contains a custom Web Receiver for Tyfloradio.

## What it does

- Accepts Tyfloradio live loads from the Android sender.
- Forces the receiver to use the direct MP3 stream at `https://radio.tyflopodcast.net/`.
- Marks the stream as `LIVE`.
- Enables autoplay for radio handoff.

## Deployment

1. Host `index.html` and `receiver.js` on an HTTPS endpoint.
2. In Google Cast SDK Developer Console, create a `Custom Web Receiver`.
3. Point the receiver URL to the hosted `index.html`.
4. Copy the generated receiver app ID.
5. Set the Android build property before building the app:

```properties
TYFLO_CAST_APP_ID=YOUR_RECEIVER_APP_ID
```

You can set it in:

- project `gradle.properties`
- `%USERPROFILE%\\.gradle\\gradle.properties`
- or environment variable `TYFLO_CAST_APP_ID`

If the property is not set, the Android app falls back to the default Google media receiver.

## GitHub Pages

This repo includes a ready workflow for GitHub Pages in `.github/workflows/deploy-cast-receiver.yml`.

### Recommended setup

1. Push the repository to GitHub.
2. In the repository settings, enable `GitHub Pages` with `GitHub Actions` as the source.
3. Run the `Deploy Cast Receiver` workflow or push a change under `cast-receiver/`.
4. After deployment, use the published receiver URL:

```text
https://<github-user>.github.io/<repo-name>/
```

5. Register a `Custom Web Receiver` in Google Cast SDK Developer Console and point it to that URL.
6. Copy the generated receiver app ID and set `TYFLO_CAST_APP_ID` before building the Android app.

### Notes

- GitHub Pages serves the receiver from the repository root of the deployed site artifact, so `index.html` is the landing document.
- Relative asset paths are already configured for Pages.
- The workflow adds `.nojekyll`, so GitHub Pages will not rewrite static files.
