# Security policy

## Reporting a vulnerability

Please report security issues privately through
[GitHub Security Advisories](https://github.com/Attc-Config-Technician/Attc-Code/security/advisories/new).
Do not open a public issue for a vulnerability.

Include:

- the Attc Code version and `versionCode`,
- the Android version and device,
- steps to reproduce, and any relevant logs with secrets removed.

You can expect an initial reply within seven days. If the report is valid, a fix
and a release will be prepared before the details are published.

## Supported versions

Security fixes are shipped in the latest release. Older APKs are not maintained;
update through GitHub Releases or the signed Attc Code F-Droid repository.

## Scope

Attc Code is a local Android app that runs a Linux userland inside its own
process. In scope:

- the Android app, foreground service, permissions and intents,
- the voice assistant, wake-word storage and microphone handling,
- the Shizuku bridge and the bundled `attc-ctl` helper,
- the F-Droid repository metadata and update channel.

Out of scope:

- the AI CLIs and packages you install inside Ubuntu — report those upstream,
- code you deliberately run inside the guest. proot is not a security boundary
  between the guest and the app; do not run untrusted code and expect isolation,
- vulnerabilities that require a rooted device or a modified system image.

## Secrets

Signing keys and repository passwords are never stored in this repository.
Release APKs are signed on a maintainer machine; the F-Droid repository index is
signed with a key kept outside the repository.
