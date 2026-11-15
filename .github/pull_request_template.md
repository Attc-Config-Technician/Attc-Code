## Summary

What changed, and why?

## Type

- [ ] Bug fix
- [ ] Feature
- [ ] Documentation
- [ ] Build or CI
- [ ] Refactor (no behaviour change)

## Verification

Describe the commands or device checks you ran. Include the Android version and device when the change affects runtime behaviour.

```text
# example
bash tools/test.sh
adb install -r dist/ATTC-CODE-5.17.apk
```

## Checklist

- [ ] The change is in English (the app UI may stay Turkish).
- [ ] No keystore, token, password or other secret is included.
- [ ] `CHANGELOG.md` is updated for user-visible changes.
- [ ] Documentation and screenshots still match the behaviour.
- [ ] The APK still builds with `tools/build-fdroid.sh` or the local Termux path.
