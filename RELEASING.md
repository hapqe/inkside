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
