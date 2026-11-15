# Third-party components

Attc Code is GPL-3.0-or-later. It vendors the following source trees; their
licenses apply to those directories.

| Component | Location | License | Upstream |
|---|---|---|---|
| PRoot (Termux fork) | `native/proot/` | GPL-2.0-or-later | https://github.com/termux/proot |
| talloc | `native/talloc/` | LGPL-3.0-or-later | https://www.samba.org/ftp/talloc/ |
| libandroid-shmem | `native/libandroid-shmem/` | BSD-3-Clause | https://github.com/termux/libandroid-shmem |
| Shizuku API | `libs/shizuku/` | Apache-2.0 | https://github.com/RikkaApps/Shizuku-API |

The APK also installs third-party AI coding CLIs on first use. Those tools are
not part of this repository and keep their own licenses:

- opencode — https://github.com/sst/opencode
- Claude Code — Anthropic
- Codex — OpenAI
- Kimi Code — Moonshot AI
- Antigravity — Google

Ubuntu is a trademark of Canonical Ltd. All other names belong to their
respective owners. Attc Code is an independent project.
