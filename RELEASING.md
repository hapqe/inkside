# Releasing

1. Bump `versionName` / `versionCode` in `app/AndroidManifest.xml` and `version` in `host/package.json`
   (the host reports it as its version).
2. `cd host && npm test` — all checks pass.
3. Build the signed APK: `RELEASE=1 bash app/build.sh` → `app/build/Inkside.apk`.
4. Tag and publish: `gh release create vX.Y.Z app/build/Inkside.apk#Inkside-X.Y.Z.apk --prerelease --notes-file …`.

## The release key

Releases are signed with a key that is **not in this repository**. `app/build.sh` reads it from
`~/.config/inkside-release/keystore.env`:

```
INKSIDE_KEYSTORE=/path/to/inkside-release.jks
INKSIDE_KEY_ALIAS=inkside
INKSIDE_KEYSTORE_PASSWORD=…
```

Create one once with `keytool -genkeypair -keystore inkside-release.jks -alias inkside -keyalg RSA
-keysize 4096 -validity 36500`. **Back it up and keep it private**: an installed copy can only be
updated by an APK signed with the same key, so losing it means every user has to reinstall.

Development builds (`bash app/build.sh`, no `RELEASE=1`) use the debug key, so a release build
cannot be installed over a development one (or the other way round) without uninstalling first.

## IzzyOnDroid

Inkside is offered through the [IzzyOnDroid](https://apt.izzysoft.de/fdroid/) repo, which serves the
signed APK from GitHub Releases.

- Each release must attach a signed APK (`Inkside-X.Y.Z.apk`) with a higher `versionCode`.
  Publish it as a normal (not pre-release) release if the repo ignores pre-releases.
- Listing text and screenshots come from `fastlane/metadata/android/en-US/`. Add
  `changelogs/<versionCode>.txt` for each release.
- First listing: open an issue at <https://gitlab.com/IzzyOnDroid/repo/-/issues> ("New app")
  with the GitHub URL, the license (Apache-2.0), and a note that handwriting search uses ML Kit
  (proprietary, flagged by the repo as a non-free dependency).
- Once it is listed, replace "Listing requested" in the README with the repo link.
