# Messaging — how SnapSync talks about itself

The living reference for every user-facing word: the store listings, the screenshots, the site, the join
page and the app. It derives from MISSION in `openspec/config.yaml` (who SnapSync is for and what it
promises); the decisions behind it are recorded in `openspec/changes/archive/2026-10-05-refresh-marketing-wording/`
(`wording.md` there holds the full settled copy). Change the wording here first, then the surfaces.

Where the shared strings live: `metadata/listing/<store-locale>.json` (both store listings) and
`metadata/screenshots/<store-locale>.json` (the screenshot headlines and the tagline, which the graphic reads, and
the site reads in English), one per language the app speaks (capability `localization`).

**Every language changes in the same PR.** English is the source. A change to `en-US.json` changes each translation
too, using the glossary's column for that language. Nothing checks this mechanically: the build only checks that
every language has the same keys (`docs/architecture.md`, "Localization").

## Positioning and pillars

- **F1 Positioning:** For family and friends sharing a day out, a party or a holiday: just join, and every photo lands in your gallery.
- **F3 Tagline:** Every photo, in your gallery.
- **F4 Support line:** Just join an event, and your family and friends' photos arrive in your gallery.
- **Pillars:**
  - P1 **Just join** — No account; every photo arrives on its own.
  - P2 **Every photo, in your gallery** — Next to your own, at full quality.
  - P3 **Private by design** — Only your group, only photos from the event.

## Writing principles

- **Short:** One idea per sentence; cut every word that doesn't carry meaning.
- **Talk to one reader:** Address the single user as “you”: “straight into your gallery”, not “each phone's” or “everyone's”.
- **Gain first:** Lead with what you get (“one scan, all the photos”), then what you're spared (“no account”), never a list of absences alone.
- **No repetition:** Don't stack the same word (“everyone's photos in everyone's library”); name each thing once.
- **Only what SnapSync does:** Never mention group chats, shared albums or other apps; describe what SnapSync makes better, not what others do badly.
- **Words:** say “photo(s)”, “gallery” (every surface, both platforms), “family and friends”, “just join”; the host **creates** an event. Inside the app “members” is fine; marketing says “group”.
- **German:** address the reader as “du”, as the app does. Use the glossary's German words. Where the app uses a word, the glossary takes the app's
  own (`ui/screens`' `values-de/strings.xml`), so the store page and the app speak alike. The tagline is “Alle Fotos, in deiner Galerie.” Keywords are German
  search terms, not a word-for-word translation of the English ones.

## Glossary

| Say | Deutsch | Meaning | Don't say |
|---|---|---|---|
| family and friends | Familie und Freunde | who SnapSync is for | users, guests, strangers, people |
| event | Event | what the host creates; has a name and dates | album, room, session |
| create / create or join | erstellen / erstellen oder beitreten | what the host does to an event | start, host, set up |
| group | Gruppe | the people in an event | members, participants |
| join / just join | beitreten / einfach beitreten | scanning the QR code or tapping the link | sign up, register, scan once, invite code |
| photo / photos | Foto / Fotos | what people take and get | snaps, shots, pics, pictures |
| gallery | Galerie | where photos land, on every surface and both platforms | library, Photos library, camera roll, cloud, shared album |
| arrive / land | ankommen / landen | what photos do | sync (as a verb), upload, download, transfer, send |
| full quality | volle Qualität | the size photos arrive in | original resolution, lossless, HD |
| private by design | von Grund auf privat | the privacy promise | secure, encrypted, safe |
| a day out · a party · a holiday | ein Ausflug · eine Party · ein Urlaub | the examples, in this order | wedding, festival, conference, reunion |
