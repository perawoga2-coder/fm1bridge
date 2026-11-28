# 16 Track: start here

Read first: `.claude/skills/makerhip-working-rules/SKILL.md` and `DECISIONS.md` (in this repo, or the fm1burger repo).
The owner is Thai, a designer, not a coder, on an Android tablet: Thai, short, step by step.

- 16 Track Mixer Recorder, a Maker Hip app (Kotlin). Branch `ccr-94587bba-xqg4fh`; `main` only with the owner's word.
- Version in `build.gradle.kts` (`0.12`, code 12 at the time of writing). The workflow `track16.yml` builds every push
  and publishes a pre-release `v<version>` with `16track-v<version>-debug.apk`.
- **Key and name (0.12, 28 Nov):** the Maker Hip release key from Secrets (the same as Burger Studio's; SHA-256
  `F3:D4:07:4B:5E:5A:73:10:72:B9:92:43:4B:09:85:F8:C3:76:DB:F4:B7:6F:A7:5E:B6:44:5F:BE:AF:EA:44:90`); CI stops without
  it. Package `com.makerhip.track16.studio`, registered in the owner's Android Developer Console. The INBOX with Burger
  Studio 3.66+: `INBOX.md`. The old 16 Track (`com.makerhip.track16`, old public debug key) installs beside it; work moves
  by the menu's Backup everything (0.11) and Restore a backup.
- No test harness: compile on a plain JVM with kotlinc 1.9.24 and `org.robolectric:android-all` (as fm1burger's
  `tools/vacuum_check/README.md`) to check the code.
- Never: an older APK over a newer one; secrets in files or messages; the owner's private data out.
