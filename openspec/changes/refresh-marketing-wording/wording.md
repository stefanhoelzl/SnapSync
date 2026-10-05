# Wording — the settled copy deck

Every string below was settled in review, item by item. Apply it verbatim; a change of wording is a change to this file first.

## Writing principles

- **Short:** One idea per sentence; cut every word that doesn't carry meaning.
- **Talk to one reader:** Address the single user as “you”: “straight into your gallery”, not “each phone's” or “everyone's”.
- **Gain first:** Lead with what you get (“one scan, all the photos”), then what you're spared (“no account”), never a list of absences alone.
- **No repetition:** Don't stack the same word (“everyone's photos in everyone's library”); name each thing once.
- **Only what SnapSync does:** Never mention group chats, shared albums or other apps; describe what SnapSync makes better, not what others do badly.
- **Words:** say “photo(s)”, “gallery” (every surface, both platforms), “family and friends”, “just join”; the host **creates** an event. Inside the app “members” is fine; marketing says “group”.

## Glossary

| Say | Meaning | Don't say |
|---|---|---|
| family and friends | who SnapSync is for | users, guests, strangers, people |
| event | what the host creates; has a name and dates | album, room, session |
| create / create or join | what the host does to an event | start, host, set up |
| group | the people in an event | members, participants |
| join / just join | scanning the QR code or tapping the link | sign up, register, scan once, invite code |
| photo / photos | what people take and get | snaps, shots, pics, pictures |
| gallery | where photos land, on every surface and both platforms | library, Photos library, camera roll, cloud, shared album |
| arrive / land | what photos do | sync (as a verb), upload, download, transfer, send |
| full quality | the size photos arrive in | original resolution, lossless, HD |
| private by design | the privacy promise | secure, encrypted, safe |
| a day out · a party · a holiday | the examples, in this order | wedding, festival, conference, reunion |

## Foundation

- **F1 Positioning:** For family and friends sharing a day out, a party or a holiday: just join, and every photo lands in your gallery.
- **F2 MISSION opening** (replaces the first two sentences of MISSION in `openspec/config.yaml`; the rest of MISSION stays): SnapSync lets family and friends — small groups who know each other — easily share the photos they take during an event, so a member's gallery only receives photos from people they know. Joining needs no account, and a member stays anonymous: no name, email or phone number is ever asked for. Events are short-lived — from a spontaneous afternoon to a few weeks: a day out, a party, a holiday — and creating or joining one takes seconds. Each has a host-declared date range …
- **F3 Tagline:** Every photo, in your gallery.
- **F4 Support line:** Just join an event, and your family and friends' photos arrive in your gallery.
- **Pillars:**
  - P1 **Just join** — No account; every photo arrives on its own.
  - P2 **Every photo, in your gallery** — Next to your own, at full quality.
  - P3 **Private by design** — Only your group, only photos from the event.

## Store listing (both stores unless noted)

- **Name (App Store name + Play title):** SnapSync Photos
- **App Store subtitle:** Every photo, in your gallery
- **App Store promotional text:** Create or join an event, and your family and friends' photos arrive in your gallery.
- **App Store keywords:** `family,friends,group,share,sharing,shared,album,event,party,holiday,vacation,trip,birthday,pictures`
- **Google Play short description:** Every photo, in your gallery. Create or join an event with family and friends.
- The per-store `{{gallery}}` word is dropped: the copy says “gallery” on both stores.

### Description

```text
Every photo, in your gallery.

Create an event for a day out, a party or a holiday. Your family and friends just join, and from then on every photo anyone takes arrives in your gallery, while you're still together.

Just join
No account, no sign-up, no password. Scan the QR code or tap the link, and you're in. Even someone who joins late gets every photo from the event.

Every photo, in your gallery
The group's photos land in the gallery you already use, next to your own, exactly as they were taken. Only photos taken during the event are shared; screenshots and photos saved from chats stay out.

For a day out, a party or a holiday
Quick enough for a spontaneous afternoon, long enough for a two-week holiday.
```

### Store screenshots, in order

1. The graphic, portrait (`reference/store-frame-1.png`): headline F3, support line F4.
2. `create` capture — headline: **Create an event**
3. `joining` capture — headline: **Your family and friends join**
4. `in_sync` capture — headline: **Photos arrive on their own**

## Landing page (reuses the items above; no copy of its own except the privacy block)

- **Hero:** the Google Play header — the graphic's composition with headline F3 and support line F4 as real text over it — then the store buttons
- **Screenshots:** the three captures, captioned with the store headlines above; the old screenshots heading and the separate “How it works” steps are removed
- **Sections:** the description's three sections (Just join · Every photo, in your gallery · For a day out, a party or a holiday)
- **Privacy block:** heading “Private by design” · line “Only your group, only photos from the event.” · points: **No account** — No email, no password, no profile. · **Anonymous** — No name, email or phone number is ever asked for. · **Only event photos** — Screenshots and photos saved from chats stay out.

## Join page (an event link opened without the app)

- **Fallback title:** Event photos (unchanged; the event's name replaces it)
- **Line under the title:** Join with SnapSync, and every photo arrives in your gallery. Or download them all here.
- **Dead or expired link:** title “Invalid or expired link” (unchanged) · body “This link could not be opened. If the event is still on, ask anyone in it for a new link.”

## App strings

226 user-visible strings were reviewed; these change, all others stay. Kept on purpose: In sync / Syncing / Synchronization… · event-album wording · invite as a verb · Waiting for {n} of {m} members.

| ID | Where | Today | New | Source |
|---|---|---|---|---|
| S002 | Create screen — eyebrow | HOST AN EVENT | **(remove)** | `commonMain/kotlin/app/snapsync/ui/components/AppEventHero.kt:46` |
| S003 | Create screen — title | Start an event | **Create an event** | `commonMain/kotlin/app/snapsync/ui/CreateEventScreen.kt:88` |
| S004 | Create screen — subtitle | Everyone's photos, one shared place. | **Every photo, in your gallery.** | `commonMain/kotlin/app/snapsync/ui/CreateEventScreen.kt:89` |
| S006 | Create screen — placeholder | Event name | **e.g. Anna's birthday** | `commonMain/kotlin/app/snapsync/ui/CreateEventScreen.kt:102` |
| S008 | Create screen — body (picker note) | Only photos taken during this window are shared — the range every guest starts from. An event can last up to {maxDays} days. | **Only photos taken between these dates are shared. An event can last up to {maxDays} days.** | `commonMain/kotlin/app/snapsync/ui/CreateEventScreen.kt:113` |
| S018 | Create screen — hint | Or scan a QR code in the Camera app to join one. | **To join an event instead, scan its QR code with your camera.** | `commonMain/kotlin/app/snapsync/ui/CreateEventScreen.kt:180` |
| S021 | Create screen – errors — error line (replaces scan hint) | Couldn't reach the server. | **Couldn't connect. Check your connection and try again.** | `commonMain/kotlin/app/snapsync/presentation/StatusContainerHost.kt:1208` |
| S022 | Invalid-link notice (Create error line / Join banner / Joined banner) — error (transient, ~4 s) | That QR code wasn't valid. | **That QR code isn't a SnapSync event.** | `commonMain/kotlin/app/snapsync/presentation/StatusContainerHost.kt:926` |
| S023 | Creating screen — eyebrow | HOST AN EVENT | **(remove)** | `commonMain/kotlin/app/snapsync/ui/components/AppEventHero.kt:46` |
| S024 | Creating screen — title | Start an event | **Create an event** | `commonMain/kotlin/app/snapsync/ui/CreateEventScreen.kt:212` |
| S025 | Creating screen — subtitle | Everyone's photos, one shared place. | **Every photo, in your gallery.** | `commonMain/kotlin/app/snapsync/ui/CreateEventScreen.kt:213` |
| S038 | Calendar/range picker — wheel caption | From | **Starts** | `commonMain/kotlin/app/snapsync/ui/components/AppEventRangePicker.kt:190` |
| S039 | Calendar/range picker — wheel caption | Until | **Ends** | `commonMain/kotlin/app/snapsync/ui/components/AppEventRangePicker.kt:199` |
| S040 | Calendar/range picker — a11y label | From hour / Until hour | **Start hour / End hour** | `commonMain/kotlin/app/snapsync/ui/components/AppSettlingWheels.kt:154` |
| S041 | Calendar/range picker — a11y label | From minute / Until minute | **Start minute / End minute** | `commonMain/kotlin/app/snapsync/ui/components/AppSettlingWheels.kt:161` |
| S098 | Share-range dialog — chip | Whole event | **The whole event** | `commonMain/kotlin/app/snapsync/ui/components/AppShareRangeRow.kt:119` |
| S101 | Share-range dialog — button (primary) | OK | **Save** | `commonMain/kotlin/app/snapsync/ui/components/AppDateTimeField.kt:187` |
| S046 | Join screen – loading — subtitle | Everyone's photos, one shared place. | **Every photo, in your gallery.** | `commonMain/kotlin/app/snapsync/ui/components/AppJoinGate.kt:55` |
| S050 | Join screen – ready — subtitle | Everyone's photos, one shared place. | **Every photo, in your gallery.** | `commonMain/kotlin/app/snapsync/ui/JoinReadySurface.kt:60` |
| S053 | Join screen – ready — body (disabled-Join reason) | Turn on sharing or receiving — a membership that does neither does nothing. | **Turn on sharing or receiving. With both off, joining does nothing.** | `commonMain/kotlin/app/snapsync/ui/JoinReadySurface.kt:84` |
| S059 | Join access explanation sheet — point body | The photos you take show up for everyone in the event. | **Photos you take during the event arrive in everyone's gallery.** | `commonMain/kotlin/app/snapsync/ui/JoinReadySurface.kt:107` |
| S060 | Join access explanation sheet — point title | SnapSync needs your photo library | **SnapSync needs access to your photos** | `commonMain/kotlin/app/snapsync/ui/JoinReadySurface.kt:112` |
| S061 | Join access explanation sheet — point body | To share yours, and to save the photos other members send you. | **To share yours, and to save the group's photos to your gallery.** | `commonMain/kotlin/app/snapsync/ui/JoinReadySurface.kt:113` |
| S091 | Join screen – ready (album note) — toggle description | Photos you receive are collected in an album named after the event. Your own photos stay in your camera folder. | **Photos you receive are collected in an album named after the event. Your own photos stay where they are.** | `commonMain/kotlin/app/snapsync/ui/JoinReadySurface.kt:136` |
| S102 | Join screen – errors — error title | Invalid invite | **Event not found** | `commonMain/kotlin/app/snapsync/ui/JoinFlowScreens.kt:74` |
| S103 | Join screen – errors — error body | This invite is invalid or the event no longer exists. | **This link is invalid, or the event no longer exists.** | `commonMain/kotlin/app/snapsync/ui/JoinFlowScreens.kt:75` |
| S117 | Join screen – errors — error body | It has reached the number of devices it can hold, so there is no room to join. | **No more members can join it.** | `commonMain/kotlin/app/snapsync/ui/JoinFlowScreens.kt:109` |
| S082 | Participation sections (Join – ready + Event settings) — body | Screenshots, screen recordings, GIFs and pictures saved from chat apps are never shared. | **Screenshots, screen recordings, GIFs and photos saved from chat apps are never shared.** | `commonMain/kotlin/app/snapsync/ui/ParticipationSections.kt:115` |
| S084 | Participation sections (Join – ready + Event settings) — toggle description | Photos others share arrive in your library automatically. | **Photos others share arrive in your gallery on their own.** | `commonMain/kotlin/app/snapsync/ui/ParticipationSections.kt:60` |
| S088 | Participation sections (Join – ready + Event settings) — toggle description | Photos are sent and received on any network. | **Photos are shared and received on any network.** | `commonMain/kotlin/app/snapsync/ui/ParticipationSections.kt:82` |
| S089 | Participation sections (Join – ready + Event settings) — toggle description | Photos are sent and received only on Wi-Fi. | **Photos are shared and received only on Wi-Fi.** | `commonMain/kotlin/app/snapsync/ui/ParticipationSections.kt:84` |
| S159 | Status line (Joined screen) — a11y label (up arrow) | uploading | **sharing** | `commonMain/kotlin/app/snapsync/ui/components/AppStatusLine.kt:217` |
| S160 | Status line (Joined screen) — a11y label (down arrow) | downloading | **receiving** | `commonMain/kotlin/app/snapsync/ui/components/AppStatusLine.kt:218` |
| S163 | Status line (Joined screen) — status pill (tappable) | Turn on full access in Settings | **Allow photo access in Settings** | `commonMain/kotlin/app/snapsync/ui/components/AppStatusLine.kt:152` |
| S166 | Event settings (reconfigure) — eyebrow | YOU'RE INVITED | **(remove)** | `commonMain/kotlin/app/snapsync/ui/components/AppEventHero.kt:28` |
| S171 | Event settings (reconfigure) — toggle description (album note) | Photos you receive are collected in an album named after the event, including the ones already received. Your own photos stay in your camera folder. | **Photos you receive are collected in an album named after the event, including the ones already received. Your own photos stay where they are.** | `commonMain/kotlin/app/snapsync/ui/ReconfigureScreen.kt:213` |
| S172 | Event settings (reconfigure) — toggle description (album note) | Photos are collected in an album named after the event, including the ones already synced. | **Photos are collected in an album named after the event, including the ones you already have.** | `commonMain/kotlin/app/snapsync/ui/ReconfigureScreen.kt:215` |
| S173 | Event settings (reconfigure) — body (standing hint) | Sharing less stops listing those photos to the event — anyone who already received them keeps them. Photos you've received stay. | **Photos you stop sharing won't reach anyone new; whoever already has them keeps them. Photos you've received stay.** | `commonMain/kotlin/app/snapsync/ui/ReconfigureScreen.kt:236` |
| S174 | Event settings (reconfigure) — body (disabled-Save reason) | Turn on sharing or receiving — a membership that does neither does nothing. | **Turn on sharing or receiving. With both off, joining does nothing.** | `commonMain/kotlin/app/snapsync/ui/ReconfigureScreen.kt:241` |
| S178 | Leave dialog — body | You'll stop sharing and receiving photos. Photos already in your library stay. | **You'll stop sharing and receiving photos. Photos already in your gallery stay.** | `commonMain/kotlin/app/snapsync/ui/StatusScreen.kt:204` |
| S200 | Switch events dialog — error title | Invite not found | **Event not found** | `commonMain/kotlin/app/snapsync/ui/ReconfigureScreen.kt:167` |
| S201 | Switch events dialog — error body | This invite is invalid or the event no longer exists. | **This link is invalid, or the event no longer exists.** | `commonMain/kotlin/app/snapsync/ui/ReconfigureScreen.kt:168` |
| S220 | iOS permission prompt — usage description (NSPhotoLibraryUsageDescription) | SnapSync syncs your photos with the events you join — sharing yours and saving the event's photos to your library. | **SnapSync shares the photos you take during an event with its members and saves theirs to your gallery.** | `iosApp/iosApp/Info.plist:54` |
| S221 | iOS system (extension name) — bundle display name (CFBundleDisplayName) | SnapSync Backup | **SnapSync PhotoKit Extension** | `iosApp/BackgroundUploadExtension/Info.plist:12` |
