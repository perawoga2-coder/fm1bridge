# Maker Hip INBOX: Burger Studio -> 16 Track

Burger Studio (the sender) keeps "boxes" of stems, one WAV per track, that 16 Track (the receiver) can SPLIT
into its tracks. 16 Track does not need to be open when a box is sent.

## Transport

A `ContentProvider` in Burger Studio:

- **Since 16 Track 0.12 and Burger Studio 3.66** (the Maker Hip release key; new names so the pair installs beside the old pair: `com.makerhip.burger.inbox`, `com.makerhip.permission.INBOX`, `com.makerhip.track16` before).
- **Authority:** `com.makerhip.burgerstudio.inbox`
- **Permission:** read and write are both protected by `com.makerhip.studio.permission.INBOX`, with
  `protectionLevel="signature"`. Both apps declare it and use it. They are signed with the same Maker Hip key,
  so no prompt is shown to the user and no other app can read the boxes.
- **Visibility:** 16 Track declares `<queries><provider android:authorities="com.makerhip.burgerstudio.inbox"/></queries>`.
  Burger Studio shows its "→ MIX" button only when the package `com.makerhip.track16.studio` is installed (it
  declares `<queries><package android:name="com.makerhip.track16.studio"/></queries>`).

## URIs

| URI | Operation | Result |
|---|---|---|
| `content://com.makerhip.burgerstudio.inbox/boxes` | query | one row per box, newest first |
| `content://com.makerhip.burgerstudio.inbox/boxes/<id>/tracks` | query | one row per track, in order |
| `content://com.makerhip.burgerstudio.inbox/boxes/<id>/tracks/<idx>` | openFile "r" | the track's WAV |
| `content://com.makerhip.burgerstudio.inbox/boxes/<id>` | delete | removes the box (16 Track asks the user first) |

### Box columns

| Column | Type | Meaning |
|---|---|---|
| `_id` | String | the box id |
| `title` | String | for example "My Song · Q2 Verse", or "My Song · whole song" |
| `kind` | String | `q` or `song` |
| `bpm` | Int | |
| `bars` | Int | its length in bars |
| `meter` | String | for example "4/4" |
| `rate` | Int | the sample rate of the WAVs (44100) |
| `created` | Long | System.currentTimeMillis() |
| `tracks` | Int | how many tracks |

### Track columns

| Column | Type | Meaning |
|---|---|---|
| `idx` | Int | 0-based |
| `name` | String | for example "A1 Kick" or "Bassline" |
| `color` | Int | ARGB |
| `gain` | Float | 0..1.2, the lane's volume (1.0 = unity) |
| `pan` | Float | -1..1 |

### WAV files

16-bit PCM, mono or stereo, at `rate`. Every file starts at bar 1, beat 1, and lasts the box's length. 16 Track
mixes stereo files to mono.

## 16 Track: SPLIT

- The INBOX button shows when Burger Studio is installed and has at least one box.
- Tap a box, then SPLIT. The choices are a new song (the default, named after the box) or this song, which
  replaces tracks 1..N.
- Each track's audio is resampled to the song's rate and written from sample 0. Its name, fader (`gain`) and pan
  are set, and the song gets the box's BPM.
- At most 16 tracks are imported. The rest are listed as left out.
- Afterwards 16 Track asks whether to remove the box from the inbox.
