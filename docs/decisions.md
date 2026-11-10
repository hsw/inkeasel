# Decisions

Design decisions with their reasons and the options that were turned down, newest first. An entry marked
*planned* is decided but not implemented yet; the change that implements it drops the mark.

## 2026-11-10 — A press on a card only opens the viewer; it opens clean, and Down brings the card with Send

Supersedes "A press on a card sends and opens the viewer" (2026-11-09), and the viewer entry's credit line on
open. The user's decision after using it on the TV: you want to look before you send, and an upload on every
press costs a frame refresh of tens of seconds (a second press waits for the first). A press on a card (CENTER
or a tap) now opens the full-screen viewer and sends nothing. The viewer opens clean, "as in a museum": the
picture alone on black, no caption, counter, action row or hint, and nothing appears on Left/Right. Down or
CENTER (a tap) brings the picture's card over its lower part: the title and "n / N", the details panel's lines
and **Send to frame**, focused, so OK, OK sends; Up, Back or a tap hides it, and Back without it closes the
viewer. Left/Right keep stepping with the card shown, which then shows the new picture (it has one row of
buttons, so Left/Right have nothing in it to move to). The "No frame selected" toast is back for every send:
the press no longer has the viewer's note as its answer, and the card shows that note in place of its button.
Attribution: the Commons licences (CC BY, BY-SA) ask for credit "reasonable to the medium"; with the viewer
clean, the credit (artist, source, licence, image credit) is in the card one press away, like a museum label
beside the work, and in the details panel, so the credit line and its 5 s timer are gone.
The long press keeps opening the details panel for now; whether Down in the viewer makes it redundant is left
for the filters from a picture, which go into the same card.
Rejected: CENTER sending at once in the viewer (a press would again send without a look at the details);
hiding the card on Left/Right (browsing with the labels open is the point of keeping it).

## 2026-11-09 — The README is a user guide; the technical text lives in docs/ by subsystem

The README had grown to 720 lines that served three readers at once: a user (install, permissions), someone
changing the code (class names, prefs keys, the order of checks) and someone building or releasing it. It is
now a short user guide with no class names, prefs keys or code paths, and the rest is split by subsystem:
`docs/architecture.md`, `docs/catalog.md`, `docs/now-playing.md`, `docs/frames.md` and `docs/building.md`, next
to `docs/protocol.md` and this file. A behaviour change updates its subsystem's document in the same commit,
and the README when a user would notice it. `CLAUDE.md` keeps only the rules for agents and points at these
documents instead of repeating them.

Install leads with the download: each release also carries `inkeasel.apk`, a copy under a fixed name, so
`releases/latest/download/inkeasel.apk` always serves the newest APK, and a Downloader short code (1236481)
points at that address for typing on a TV remote; adb is the fallback. Granting notification access leads with
the in-app path, adb only where the TV has no screen for it.

Rejected: one `docs/development.md` (the same long page, moved); a documentation site (a build and a deploy for
a few pages); a Russian README (one set of texts to keep in step with the code).

## 2026-11-09 — The first signed release is 0.2.0, with a new release key

The release key is a PKCS12 keystore holding one RSA 4096 key (alias `inkeasel`, certificate `CN=InkEasel`,
valid 30 years), generated for this release and kept only in the maintainer's password manager and the
repository's four `INKEASEL_*` Actions secrets. PKCS12 keeps one password for the store and the key, so
`INKEASEL_KEY_PASSWORD` equals `INKEASEL_KEYSTORE_PASSWORD`. Every later release must be signed with it:
Android installs an update only over the same signer, so a lost key means users reinstall. Builds before
0.2.0 were debug-signed and cannot be updated in place to a release build.

Rejected: Play App Signing alone (the APK on GitHub releases needs its own key either way); a separate key
password (PKCS12 ignores it, with a keytool warning).

## 2026-11-09 — The Catalog's nudity filter is a setting with three values; the device can lock it
**Settings → Catalog → Nudity** chooses the filter: "Hide explicit works" (the default), "Hide all nudity"
or "Show all"; a press (OK or a tap) moves to the next value in that order, and the choice is stored in plain
preferences (`CatalogFilterStore`, like the poster switch). The levels are those the catalog marks (explicit:
genitals in focus, a sexual act or assault; nude: other nudity), from the release's `nsfw` field, the
built-in list and Wikidata's nude genre; the row says that some works may not be marked (the live sources
never are). The default hides only explicit works (user decision): a classic nude such as Botticelli's Venus
or Olympia is part of what a painting catalog shows, while "L'Origine du monde" on the first screen of a
family TV is not. The section is its own group, not a row of "Permissions and features", which holds only
permissions and feature switches.
The device locks the filter at "Hide all nudity" while Android TV's parental controls are on
(`TvInputManager.isParentalControlsEnabled()`; the service is missing on a device without
`android.software.live_tv`, so a phone or tablet reads it as off) or while the user may not change accounts
(`UserManager.DISALLOW_MODIFY_ACCOUNTS`, set for every restricted profile; also on some managed devices,
where a stricter filter does no harm). It is read at each start of the activity and when the settings open
(in a split screen the system settings change it while the app stays started), never stored or sent (the
system log gets the filter in force and the lock's kind when it changes), so a lock switched on while the
app was in the background applies on its return (the grid goes back to its top, an open Catalog viewer or
details panel closes); the row shows the reason and a press does nothing; the stored choice comes back once
the lock is off.
Not detected: Family Link supervision and Google TV kids profiles. Android has no public API for either on
API 26–36 (a supervised account sets no user restriction an app can read, and a kids profile is an ordinary
user to the app), so the default filter is what protects them.
Rejected: hiding all nudes by default (it takes much of the classic canon out of an art catalog for every
user, to cover a case the lock and the setting cover), a filter only in the export with no setting (no way to
see the works hidden, or to hide more), a fixed filter with no switch (the same), guessing a kids profile
from launcher packages or the account list (no stable signal, and reading the accounts needs a permission
this app has no other use for), storing the lock (a stale value would outlive the parental controls being
switched off), an app restriction for device admins and Google Play's Age Signals API as another lock source
(later, if asked for).

## 2026-11-09 — Each picture stores its nudity level; the Catalog's queries filter on it
Every catalog row has a `nudity` column (`picture` and `picture_stage`, INTEGER NOT NULL DEFAULT 0): the
`NudityLevel` ordinal, 0 none, 1 nude, 2 explicit. It is computed in Kotlin as a row is read
(`Nudity.levelOf`: the release's `nsfw` field, the built-in list, the nude genre; the strictest wins), for the
starter set and for the staged release rows alike, and `movePictures` copies it, so a newer release sets it
again both ways. Live rows (Cleveland, Commons) are 0: their sources give no such data; one that is a
Wikidata row stays hidden as its duplicate, so a filtered painting never shows through a live copy.
The list's queries take a bound, `nudity < :nudityBelow` (`NudityFilter`: 3 shows all, 2 hides explicit, 1
hides all nudes): the paging query, the count, and the viewer's place, count and neighbour ranks, so its
"n / N" counts the same list as the grid. The stored order holds every row whatever its level (the hard hide
of listed explicit rows in `CatalogRank.place` is gone), so another filter needs no write: the store starts a
new pager, the grid goes back to its top (the D-pad focus moves to the tab first, as Back does it, since the
focused card goes), and whatever shows a Catalog picture picked under the old bound goes, as it may be one the
new bound hides: an open Catalog viewer closes (it steps under the bound it opened with), so does a details
panel on a Catalog picture, and the Bluetooth panel keeps asking for the permission but no longer sends its
Catalog picture. My pictures is not touched. The grid's old cards stay until the new list's first page
arrives, and after a write to the catalog until Room's paging source, invalidated by it, re-pages the grid
(a short window, not for good), so the row and the `Picture` made from it carry the stored level: an old card
the bound in force hides shows empty at once, as a row still loading (`CatalogPicture.card`). Every way a
Catalog picture shows or is sent goes through one gate, `MainViewModel.allowed`: the level stored now
(`CatalogDao.nudity`, one indexed read off the main thread) against the bound in force when it returns, read
again if a write finished meanwhile; a picture gone from the database, or a failed read, keeps the level read
with it. A press (the viewer, then the send path), a long press (the details panel), "View full screen",
each Left/Right of the viewer (`ViewerNav`'s gate: the step's own query filters too, but an import may commit
after it) and "Send to frame" (the Bluetooth panel's later send too) open or send only after it; a hidden
picture opens and sends nothing, and only the log says so, as its card is about to go. An open still at the
gate (one at a time, `MainViewModel.whenAllowed`; "Send to frame" too) is dropped by a newer one and by
every action that changes what the screen is about: another tab (the tab's focus again is none), the details
panel dismissed, the settings, the Bluetooth panel, a new bound and Back. What an open depends on is taken
before the gate: whether it is a Catalog viewer comes from the picture (`Picture.local`), not the tab when
the read returns, and its list (the bound and the languages, or My pictures' loaded list) is read at the
press. A press's send path follows its viewer's open without a second read. A release import or the
built-in list can also raise the level of a picture already shown: after each write to the catalog
(`CatalogStore.writes`), and when the viewer finds no place for its picture, the viewer, the details panel and
the Bluetooth panel's picture go through the gate again and go as for a new bound. Back from a viewer opened
from the details panel brings them back for the picture it showed, at once: that picture passed the gate and
is under the re-check.
Rejected: trusting the level read with the row for an open (each round of review found another path where a
retained card's level let a filtered picture show; a gate per path would miss the next one), relying on the
grid having refreshed (a press in that window opened the viewer on the picture alone and sent it), Room's
invalidation tracker for the re-check (every write already goes through the store's one write lock, which
counts it), showing first and closing after the read (the picture would flash), reading the tab once the gate
returns (a tab switched meanwhile made a Catalog viewer one of My pictures, which a new bound or an import then
left open). When the
filter leaves no row of a catalog that has some, the tab says so and where to change the filter
(`CatalogView.FILTERED`: the list read to its end with no row), instead of "Loading" for good. The every-10th
live card may move up by the rows a filter
leaves out before it. The bound is the setting's (see "The Catalog's nudity filter is a setting with three
values"), hide explicit by default, so explicit works stay hidden as before.
Whether the catalog has any picture at all ("no pictures yet") is counted without the filter.
The import reads a release only when it changed, so a newer app version's longer list would not reach a
release already imported: each process start raises the stored Wikidata rows to the built-in list's level
(`CatalogDao.markListed`; it writes nothing when nothing is raised and never lowers a level). The order does
not depend on the level, so a process start computes no order: the starter import and each release or live
refresh still do. The index `(hidden, rank, nudity)` serves the order and the filter at once, so the counts
behind "n / N" and the paging count read only the index.
The schema goes to version 2 and the database is dropped and created again on the upgrade: the starter set
imports again and the release downloads again (see "The catalog database holds only data that can be
downloaded again"). The upgrade test found that `fallbackToDestructiveMigrationOnDowngrade()`, called after
`fallbackToDestructiveMigration()`, made Room 2.6 ask for a migration on an upgrade again; the first call
alone covers both directions.
Rejected: keeping the hard hide in the order (the "Show all" choice could not show them), filtering the loaded
pages in Kotlin (the paging positions and "n / N" would count hidden rows), an order per filter (a rerank
and a write of every row at each change), computing the level in the query from a table of listed ids (a
join on every page for a value that changes only with an import or a new app version), a Room migration that
adds the column (the stored rows would still need their levels, which only reading the release again gives).

## 2026-11-09 — The release marks each painting's nudity in an `nsfw` field
The export writes `"nsfw": "nude"` or `"explicit"` into a `core` row (left out when none), so the app's filter
does not depend only on the ids it has built in. The level is the strictest of three sources, the same
maximum the app computes (`Nudity.levelOf`): the list `tools/catalog-nsfw.txt`; Wikidata "depicts" (P180),
where a vulva (Q2192288, Q4112929), a penis (Q58) or sexual intercourse (Q5873) makes it `explicit` and
nudity (Q10791) or pubic hair (Q54795) makes it `nude`; and the nude genre (P136 Q40446), `nude`. "Depicts"
breast (Q9103) alone counts for nothing: the review of the thumbnails found it on many works that are not
nudes, a nursing Madonna among them, and since the rule is a maximum, the list could not undo such a mark; a
nude that only depicts a breast is left to the list. The starter cut also leaves out a row marked
`"nsfw": "explicit"`, besides the listed ids. The field appears in the release with its next regeneration;
until then the app's built-in list and the genre carry the level. The app reads a value it does not know (a
later release's new level) as `nude`: never less strict than a mark it cannot read.
A check of the published release against the genitals-or-sex rule found five rows. Three are listed explicit;
Salvador Dalí's "The Great Masturbator" (Q167536, 377th by fame) was not, as the vision pass saw only a photo
of it in a gallery: it is listed explicit now, so it leaves the starter set at once. Frans van Mieris's
"Brothel Scene" (Q17189254, its figures clothed) will be explicit after the regeneration; accepted, as a
sexual subject. So the rule stays: the review found no classic nude it would hide.
Rejected: counting "depicts" breast as nude (hides many works that are not nudes), demoting the genitals
concepts to `nude` (the check above found them only on works that are explicit or show a sexual subject),
letting the list lower a
level that "depicts" or the genre sets (an absent list entry cannot be told from "none", and the filter must
never be less strict than any source), writing P180 into the row for the app to decide (more data in every
row and two places to keep the concept ids).

## 2026-11-09 — Explicit paintings are hidden at once; the starter set leaves them out
The Catalog showed Courbet's "L'Origine du monde" twelfth by fame, on the first screen of a TV that a family
shares. No source gives an age rating, and Wikidata's genre (P136 nude, Q40446) and "depicts" (P180) mark
nudity unevenly and do not tell a classic nude from a work with genitals in focus or sex shown. So the
repository keeps a list, `tools/catalog-nsfw.txt`: one Wikidata id per line with a level, `explicit` (genitals
in focus, a sexual act or assault) or `nude` (other nudity), and the title as a comment. It was built from the
published release: the top 1,000 by fame, every nude-genre row and every row that depicts nudity, breasts,
pubic hair, genitals or sex (1,448 candidates); a vision model classified each thumbnail and the explicit and
borderline ones were checked by eye (10 explicit, 576 nude; one more explicit since, see "The release marks
each painting's nudity"). It is edited by hand.
For now only the `explicit` level is used, at once and on every install: the starter set leaves the listed
explicit ids out and reads on in fame order to fill its 1,000 rows (`write_starter`, so a full export and
`--starter-from` agree; three of today's top 1,000 went, the next three by fame came in), and the app holds
the same ids built in (`Nudity.EXPLICIT`; a unit test checks it against the file) and hides such a row in
`CatalogRank.place`: it takes no place in the order, so the every-10th live card stays where it was, and it
still hides a Cleveland or Commons copy of itself. The release, already published, still carries them, so
`CatalogStore` computes the order again once per process after the starter step (a database an older version
filled loses them before the list reads it; it writes nothing when nothing moves). The `nude` level, a
setting and an `nsfw` field in the release follow. (Superseded in the app by "Each picture stores its nudity
level", above: the level hides them under the default filter, and the order no longer leaves them out, so the
order is no longer computed again at each process start.)
Amends "The starter set is cut from the published release", below: the starter's rows are the release
`core`'s lines minus the listed explicit ids, cut at 1,000, no longer exactly its first lines.
Rejected: waiting for a regenerated release (a published one would keep showing them until then), the nude
genre or "depicts" alone (both miss works and mark classic nudes the same as explicit ones), hiding them only
in the query (the order and the viewer's place and count would still count them).

## 2026-11-09 — The details panel shows the picture large, without the frame preview
The details panel showed the picture above a preview drawn as the chosen frame would show it, each in half of
the right side, so a portrait painting got half the height twice. The user found the frame preview less
useful than a large picture, and the fit with white bars is now what an upload does and the full-screen
viewer shows the picture whole anyway. The preview is gone: its rendering (`EpdImage.previewOf`, `preview`,
`FrameFormat.previewPixels`), the full-size download behind it (`Picture.fullBytes`), its states and texts
(`PictureDetails.preview`, `previewFailure`, six strings in both tables, the "Original" caption with them).
The picture now takes the whole right side, the full height of the content area, fitted whole
(`ContentScale.Fit`) at its top, so a portrait uses all of the height; on a narrow window it sits under the
buttons at its own shape. It loads as the viewer does (`FittedPicture`, shared): the card's thumbnail at once,
then the rendition for that area (`ViewerRules.rendition`; on a TV the 960 px Commons step, the one the
viewer loads), since the 330 or 500 px thumbnail would be enlarged two to three times. That is one image
request per panel instead of the preview's 1920 px download and dithering. The TV margins and text sizes
are unchanged.
Supersedes the preview of "A long press on a card opens its details; a press still sends" and of "Uploads
fit the whole picture on white", below.
Rejected: keeping the preview under a smaller picture (the user's choice), the preview on demand behind a
button (one more control for what the viewer and the frame already show), the card's thumbnail alone at that
size (soft on a TV).

## 2026-11-09 — A press on a card sends and opens the viewer; a long press's release is swallowed
The user's decision after testing the release build on the TV: a press sent the picture and showed nothing of
it, so there was no way to see what had been sent. Now a press (CENTER or a tap) takes the same send path as
before (`BleAsk.onSend`) and opens the full-screen viewer on that picture, stepping through the tab's list as
from the details panel. Dialogs never stack: when the send path needs the Bluetooth panel, the panel opens
instead of the viewer (the picture is still sent once the permission is granted). With no frame chosen the
press sends nothing and the viewer opens with its action row up, whose note says no frame is chosen; that note
is the press's one answer, so the press leaves out the "No frame selected" toast (Send in the details panel and
in the viewer keep it). Bluetooth denied for good keeps its toast, which says what the viewer does not. Back
from a viewer opened by a press returns to the grid, as there was no panel to go back to; from a viewer opened
from the details panel it still returns to the panel. Send in the details panel still sends without opening
the viewer. tv-material's `Card.onClick` acts on the key's release, so no part of the press reaches the viewer
and a press sends once.
A long press opened the details panel while CENTER was still down (tv-material's `onLongClick` acts on the first
key repeat); the remaining repeats and the release went to the panel's focused **Send to frame**, which sent the
picture and closed the panel. The details panel and the viewer now open with a `HeldCenter` armed: the repeats
and the release of a CENTER press already handled are swallowed, until that release or a fresh press, which
goes through. The viewer uses the same rule for its own CENTER that shows the action row (in place of its
swallowed key-up). Reproduced on the TV emulator with a virtual remote holding CENTER for 2 s: the panel closed
through the send path before the change and stays open after it.
Found while checking Back: with the action row's button focused, Back in the viewer only moved the focus to the
picture (Compose spends an unhandled Back on moving the focus out of a focused child), so leaving took two
presses, against the earlier entry. The viewer now takes Back (and Escape) itself and closes on its release.
Supersedes the plain press of "A long press on a card opens its details; a press still sends" and "A
full-screen viewer, opened from the details panel" (its rejected "CENTER on a card opening the viewer" was the
viewer instead of the send; this is both), and the latter's Back to the panel for a viewer opened by a press.
Rejected: a press that opens the viewer only (one more press per upload, the reason the press sent at once),
keeping the press as send only (the user's test), the toast and the row together without a frame (two messages
for one press), the viewer behind the Bluetooth panel (two dialogs), a delay before the panel's first button
takes the focus (a held key repeats and is released whenever the user lets go), a flag of its own in each
dialog (one rule, tested once, for the panel and the viewer).

## 2026-11-09 — Catalog cards show the whole picture
A card showed its thumbnail cropped to the card's shape (`ContentScale.Crop`), so a portrait painting kept
only its middle. Now the picture is fitted whole into the card (`ContentScale.Fit`) on black, in the Catalog
and in My pictures: the cards keep their size and the frame's shape, so the grid stays regular and the D-pad
moves through it as before, and the title and credit keep their scrim at the bottom, over the picture or the
black around it. With uploads fitted too (above), the card shows what the frame gets, without the white.
A Commons thumbnail is asked for by width, and a portrait fitted into a landscape card needs much less of it
than the column: the card asks for the width the picture takes in the card (`CatalogPicture.thumbWidth`: the
column for a picture at least as wide as the card's shape, less for a narrower one, then 330 or 500 px as
before), so a portrait on a TV card (about 160 of 402 px) takes 330 px instead of 500, less than half the
pixels and still taller than the card. The viewer's stand-in and the details panel's picture are the same
thumbnail.
Rejected: keeping the crop (hides most of a portrait), cards in each picture's own shape (an uneven grid, and
a placeholder does not know its picture's shape), a blurred copy of the picture behind it (a second image per
card), a 250 px or smaller thumbnail for narrow pictures (the details panel shows the same one larger).

## 2026-11-09 — Uploads fit the whole picture on white
An upload scaled the picture to cover the panel and cut off what stuck out, so a portrait painting on a
landscape frame (the Mona Lisa on 1024 × 600) lost most of itself. The user's decision after testing the
release build on the TV: the whole picture, scaled up or down to fit inside the panel and centred, the rest
white (the paper of e-paper): bars at the sides of a picture narrower than the panel's shape, at the top and
bottom of a wider one, none for the same shape. The geometry is pure and tested (`FrameFormat.fit`, `centred`,
`sampleSize`). The bars are the panel's white ink (`FrameFormat.paper`: the ink nearest to white; BP has none
and gets its pink), filled plain: only the picture is dithered, so none of its error diffuses into the bars
and they stay clean. Memory: the decoder subsamples to the picture's place on the panel (a portrait original
needs only the panel's height), the scaled bitmap and its pixel array have the picture's size, not the
panel's, and only the ink indices cover the whole panel. The details panel's preview ran the same path and
showed the bars (until "The details panel shows the picture large", above, removed the preview). Every upload takes it, the now-playing poster included (a square cover gets side bars
instead of losing its top and bottom).
Supersedes the crop in "A long press on a card opens its details; a press still sends" (its preview).
Rejected: keeping the crop (the user's test), a crop for posters only (two rules on one path, and a cover's
text sits near its edges), dithering a panel-sized bitmap with the bars painted in (Floyd–Steinberg would
spread the picture's edge error into the bars as stray dots, and it costs a panel-sized ARGB copy), black bars
or the picture's mean colour (on e-paper the background is the paper).

## 2026-11-09 — English labels: plain `en` before `en-us`, `en-gb` and `en-ca`
For an English UI the language chain now reads `en`, then the regional variant, then `mul` (an `en-US` TV:
`en`, `en-us`, `mul`). Wikidata's English regional labels are few and often worse than `en`: on the release,
Watteau's `en-us` label is "Jean-Antoine" and Raja Ravi Varma's is a description. The variant still stands in
where `en` has no label, and its file is still fetched. Other languages keep their variant first (`pt-br`
before `pt`, `de-at` before `de`), where the variant is the user's own spelling.
Supersedes the order in "Catalog labels follow a language chain" for English only.
Rejected: dropping the English variants from the chain (a picture with only an `en-us` label would fall to
another language).

## 2026-11-09 — The catalog checks again each time the app comes back to the foreground
Found in the review: the store was woken only from `MainActivity.onCreate`, and on a TV the activity and
the process can stay alive for days, so a file or a live list that fell due, or a request that failed,
waited for a new activity. Now the activity's lifecycle wakes it at every `onStart`
(`CatalogStore.startOnEachStart`): the app's start, each return to the foreground and a re-created activity
(a new UI language). This stays cheap on the main thread and on the network: a start only wakes the store's
conflated channel, a pass with nothing due makes no request (each file and source keeps its own week, a day
after a failed import), and the starter set still imports only once per process into an empty database.
Rejected: `onResume` (it also runs after the permission dialog and the system's other short overlays, with no
new reason to check), a timer while the app is shown (the weekly rhythm does not need one; a TV left on the
app for days is the rare case), `ProcessLifecycleOwner` (a new dependency for the one screen that shows the
catalog).

## 2026-11-09 — A viewer step reads its neighbour, place and count in one statement
Found in the review: a step read the shown picture's rank, its neighbour, the neighbour's place and the
list's size in four queries, so an import or a live refresh that committed between them could make Right
show the same picture again, skip one, or show an "n / N" of another list. Now each step and the viewer's
opening is one SQL statement (`CatalogDao.next`, `previous`, `placed`): the neighbour with its place and the
list's size as subqueries, from the shown picture's rank as stored now (the rank it was read with if it is
gone or hidden). SQLite reads one statement from one state of the database, so a commit falls before or
after the step, never inside it.
Rejected: a Room transaction around the four reads (Room begins every transaction `IMMEDIATE` in WAL mode,
taking the write lock, so a key press would wait behind a release swap that can take seconds on a TV, and
it would hold up the next import).

## 2026-11-09 — Only a card's thumbnail is held; what a user asks for is always sent
Found in the review of the host hold (below): it held a host for every request on the one client, so after a
thumbnail storm on `upload.wikimedia.org` (`Retry-After: 11`, held up to a minute) **Send to frame** (the
full image, `UploadEngine`) and the viewer's image failed at once with a 429 the app made up, the server never
asked. The hold now applies only to requests tagged `Holdable`: the cards' thumbnails, tagged on Coil's
`ImageRequest`, which passes its tags to OkHttp. Every other request (the frame upload, the viewer, the
lists, the release files) is sent as before and gets the server's own answer, retried after a short
`Retry-After` like any other; its 429 or 503 still holds the host for the thumbnails, and a shorter later
pause never shortens a hold.
Rejected: waiting out what is left of the hold before a user's request (up to a minute with no feedback after
a press), holding every request but on the main thread (the upload and the viewer run off it), a client of
its own for the grid (the hold, the per-host limit and the disk cache would split between two clients).

## 2026-11-09 — The release sync downloads outside the write lock and waits a day after a failed import
Found in the review: the release sync ran inside the store's write lock, downloads included, with only a
read timeout, so a link trickling bytes could keep the pass (and every write after it) from ever finishing;
and a download that could not be imported (a broken upload, a core or labels file with no valid row, a full
disk) recorded nothing, so every app start downloaded it again, several MB on a TV. Now the sync fetches the
due files first, outside the lock, each call within 10 minutes (`CatalogSync.CALL_TIMEOUT_MIN`, a
`newBuilder()` of `Http.client`), then takes the lock only to import and record (reading the files' state
again under it). A changed file whose import failed is recorded as checked with `importFailed` and no new
ETag or hash, so it is due again a day later (`CatalogSyncPlan.RETRY_FAILED_IMPORT_MS`) and downloads in full
then, never answered 304 for content that was never stored; a failed request still records nothing and is
asked again at the next start. A labels file whose every line is bad is refused like a core with no valid row
(an empty one still empties its language), so a broken upload no longer wipes a language for a week. The
starter's labels of a chain language the database has none of are looked for at every pass (one query for a
language the starter set lacks), so a language that leaves the chain and comes back gets them again, also
offline; not for a language whose release file was imported (its hash recorded,
`CatalogSyncPlan.importedLabels`), even an empty one: its 304s and unchanged hashes would never replace starter
labels put back over it. Its state goes with its labels when it leaves the chain, so the way back still gets
them. The release's labels come only as `labels-<lang>` files, so the import no longer reads a file of
several languages (the tool's `labels-all`), and the reader no longer hashes what the sync already hashed
while downloading.
Rejected: the failed file weekly like a 404 (a re-upload would wait a week), a failure count with growing
waits (state for a rare case; a day is cheap), retrying the swap without the failing labels file (one bad
file then hides behind a partial import; the core waits a day instead).

## 2026-11-09 — The Catalog grid keys its cards by place
Found in the review: the paged grid keyed a card by its row id, and paging-compose keys a row not loaded yet
by its index, so the key of a focused placeholder changed when its page arrived, the grid replaced the card
and the D-pad focus was lost: after Back and Down at once on a list scrolled past the 360 rows kept loaded, or
with Down held past the prefetch. The grid now keys its cards by place (the count includes the rows not
loaded, so a place is stable), and a placeholder is the same card node (`PictureCard` with no picture), so a
focused card keeps the focus when its picture arrives. Checked on the TV emulator with release builds: 130 or
200 Down presses at once (to card 521 of 9,818), then Back and Down at once: before, the focus fell back to
the Catalog tab and the next Right switched tabs; now it stays on the first card, and Right goes to the second
(twice in a row); the held Down kept the focus on a card in both builds. The viewer steps from where its
picture is now (an import or a live refresh may have moved it since it was read), and a picture hidden as a
duplicate since the grid read it opens alone, with no place in the list.
Rejected: keeping the id keys and moving the focus by hand after each load (a race with the D-pad), item
keys of `rank` (a rerank moves them too).

## 2026-11-09 — A host that asks for a pause gets none; a refused thumbnail is asked again
Found in the acceptance check on the TV emulator: a fast D-pad scroll through the Catalog asks
`upload.wikimedia.org` for a thumbnail per card, and Wikimedia soon answers HTTP 429 with `Retry-After: 11`.
That is longer than a retry may wait, so the answer was final: 430 of 469 thumbnail requests of one scroll
were refused, and the cards where the scroll stopped stayed grey for good, because Coil does not ask again.
Now the retry interceptor holds a host (with its port) that answered 429 or 503 for the wait it asked for
(the first default wait without one, at most a minute): a request to it (only a card's thumbnail since the
entry "Only a card's thumbnail is held", above) before the hold ends waits for it
where it may wait (not on the main thread, at most the 10 s retry cap) and is otherwise answered by the app
itself with a 429 whose `Retry-After` is what is left, without being sent. A catalog card whose thumbnail was
refused with 429 or 503 asks again after that wait (at least 1 s, at most a minute, three times at most), as
long as it stays composed; a card scrolled away asks nothing. A longer scroll (240 rows instead of 160)
then sent 96 thumbnail requests, 28 of them refused, the app itself answered 97, and every card on screen
showed its thumbnail once the pause was over.
Rejected: waiting up to `Retry-After` inside the request (a scroll would fill the host's 4 slots with waiting
calls for 11 s), a longer retry cap (the same, for every call), loading thumbnails only once a scroll settles
(fewer requests, but a slow D-pad step through the list would still hit the limit and keep grey cards), a
retry on every failed thumbnail (a 404 or a broken file would be asked for again and again).

## 2026-11-09 — The Catalog keeps at most 360 rows loaded
Found in the acceptance check: the list's pager had no `maxSize`, so every page a scroll read stayed in
memory, and a scroll to the end would hold all of the about 9,800 rows the paging query was there to avoid.
The pager now keeps at most six pages (`CatalogStore.MAX_ROWS`, 360 rows, about 90 rows of four cards on a
TV); pages far from where the list is read are dropped and show as placeholders until scrolled back to,
when they are read again. Placeholders are on, so the grid keeps its length and the focus does not move.
Rejected: no bound (memory grows with the scroll on 1 to 2 GB TV boxes), a bound of a few pages (pages are
read again on every turn of direction; 360 rows cost little).

## 2026-11-09 — Few requests per host, a short retry on 429 and 503, a fixed image cache
The one HTTP client (`Http.client`: Coil, full images, artwork, the release sync and the live lists) sends at
most 4 requests to one host at a time (OkHttp's default is 5), so a fast scroll through Commons thumbnails
does not burst at `upload.wikimedia.org`. An answer of HTTP 429 or 503 to a GET or HEAD is asked again at
most twice (`RetryInterceptor`, rules in the pure `RetryPolicy`): after its `Retry-After` (seconds or an
HTTP date), or 1 s and then 2 s without one; an answer that asks for more than 10 s is final, so a server is
never asked earlier than it said and nothing waits long. The wait runs on OkHttp's thread in short slices
and stops at once when the call is cancelled (a cancelled coroutine, a call timeout); on the main thread
the answer is returned as it is, nothing waits. A retry starts again from the request's own address, so a
release file is asked from github.com again rather than from the signed address it redirected to. Coil's
disk cache is set to 150 MB in its own default folder (`cacheDir/image_cache`, kept on an upgrade), with one
image loader per process because Coil allows one disk cache per folder.
Rejected: retrying other codes or connection failures (OkHttp already retries those it can; a 4xx stays
wrong), waiting the 10 s cap when the server asked for longer (asks early), a retry per call site (three
paths to keep alike), Coil's default size (2 % of the free space, 10 to 250 MB: unknown on a TV).

## 2026-11-09 — The live sources are rows of the Catalog, every 10th card until the filters
The Cleveland museum's CC0 landscape paintings and Commons' featured landscapes are no longer tabs: their lists
are rows of the catalog database (`cma:<api id>` with the accession number, `commons:<file name>` with the
file name), shown in the one Catalog list. Until the filters give them a place, they take every 10th card,
Cleveland and Commons in turn, and follow the last Wikidata row once those run out; a live row that is a
Wikidata row (the same Commons file, or a Cleveland accession number equal to the release's P11110 value) is
hidden, so a painting shows once. A source is asked in the background at app start, after the release sync,
a week after its last whole list (the state is kept in `catalog_file` as `live-<source>`, so it goes with the
database); the request runs outside the store's write lock with a longer limit than a screen could wait for
(60 s per list request, Commons 3 pages within 120 s), and the answer replaces the source's rows in one
transaction that also redoes rank and dedupe. A failure, an empty answer or a list cut short that is smaller
than the stored one keeps the rows; a list cut short that is not smaller replaces them; both are asked again
at the next start. The Catalog has no stale note and no **Retry**: the starter set always shows, a source
that is down only lacks its newest rows, and failures go to the log. The old per-source list cache
(`files/catalog/`) is deleted at every process start (nothing to do once it is gone); My pictures keeps its
own load and its **Retry**. Commons rows build their renditions from the file name like the release's (the
list request no longer asks for a 1920 px thumbnail URL), and their author, whom the licence asks to name,
is their artist, shown on the card, in the details and in the viewer. Portrait paintings of the release stay
in the list until the "fits the frame" filter; the live sources keep their own landscape-only choice.
Rejected: live rows after all Wikidata rows (about 2,400 rows of cards down on a TV), keeping the tabs (a tab
per source does not scale and repeats paintings), a stale note or **Retry** in the Catalog, a table of its
own for the refresh state (a schema version for two rows), refreshing at every start (the lists change
slowly, and two sources' APIs would see each start).

## 2026-11-09 — The Catalog tab pages from the database; the viewer steps by rank
The Catalog is the first tab and the one the app starts on; My pictures follows (Paintings and
Landscapes stayed only until the live sources moved into the database, see above). It shows `CatalogStore`'s Paging 3 list
(`collectAsLazyPagingItems`, pages of 60 rows, placeholders on): a row not read yet is an empty card of the
same size that takes the D-pad focus and does nothing when pressed, so the grid has its full length at once
and the focus never jumps when a page arrives (the cards keyed by place since the entry "The Catalog grid
keys its cards by place", above). A row becomes a picture at the edge (`CatalogPicture.of`)
with a translatable `list_names_separator` for the artists and a 330 or 500 px thumbnail, the smallest
standard width that covers the card's column in pixels. The viewer opened from a Catalog picture steps
through the database, not through what the grid has loaded: the shown neighbour by rank, with its place
and the list's size (`COUNT` queries) for "n / N" (`CatalogSteps`); it shows the picture alone for the
moment its place is read, and a step pressed while one is read is dropped. My pictures keeps
stepping through its loaded list (`ListSteps`). The list's pager starts only once the starter step has
run: until then the tab says "Loading the catalog…", and its first query neither waits for the starter's
transaction nor holds it up; should the starter set fail to import and leave the database empty, the tab
says so instead of loading for ever. Back counts the placeholders as cards (`CatalogBack`), so it scrolls a
far-scrolled Catalog back to the top. The Catalog keeps its own grid state, so its place survives a visit
to another tab.
Rejected: the whole list in memory (about 9,600 rows on 2 GB TV boxes, and the paging query exists for
this), stepping the viewer through the loaded pages (it would stop where the grid stopped loading, and "N"
would be the loaded count), placeholders off (the grid grows under the focus while pages arrive), the cards
in a fixed 500 px thumbnail (twice the pixels a phone or a three-column window needs).

## 2026-11-09 — The starter set is one labels file per language
The first start imports the starter set before the Catalog can show anything, and measured on the TV
emulator (release build, fresh install) it took about 7 s warm and over a minute right after a cold boot:
it read the one starter labels file of every UI language (41,643 rows, 4.6 MB of text), parsed each row
with `org.json` to learn its language and kept about 2,000. The starter's labels are now cut as in the
release, one `labels-<lang>.jsonl.gz` per language (`--starter-from` writes them, 1.12 MB together, 68 KB
more than one file), and a start opens only the files of its chain, English and `mul`. The import also lost
its regular expressions (a matcher per id and language code; plain loops now), writes the starter's rows at
their final ranks when the table is empty (no rank pass afterwards), and the rank pass moves rows through
free ranks only when a new rank is taken (always since the review: the starter's rows need no pass, so the
check saved little). On the same emulator (five alternating fresh installs of each
release build), from the process start to the starter set in the database: 6.8–10.6 s before, 1.7–2.1 s
after; the import itself, the database's creation included, 1.0–1.3 s, about 0.5 s when nothing else
runs (the app's first frame and the TV launcher compete for the CPU). A debug build is several times
slower (5–9 s), as debuggable code runs unoptimised.
Rejected: a prebuilt SQLite file in the APK (Room's schema and identity hash in the export, as rejected
before), skipping other languages' rows of the one file by their first bytes without parsing (tried: still
inflates, hashes and decodes 4.6 MB, and depends on the key order), showing the pictures before their
labels (titles would flip from file names to labels), a background start before the activity (no
Application class for it; the import already runs off the main thread from `onCreate`).

## 2026-11-09 — Catalog pictures build their Commons URLs from the file name
A catalog row becomes a `Picture` by a pure mapping (`CatalogPicture`): id `wd:Q…`, title, artists and first
collection from the labels (the file name without its extension when there is no title label), the inception
year as the date, the Wikidata page (`https://www.wikidata.org/wiki/Q…`) as the web page, source "Wikidata".
Its image URLs are built from the Commons file name with no API request (`CommonsUrls`, on
`upload.wikimedia.org`, as MediaWiki names them): the MD5 hash path of the name with underscores, MediaWiki's
percent-encoding (so the URLs match the API's and share its caches), only the standard thumbnail widths;
a name over 160 bytes is `<W>px-thumbnail.<ext>`, a TIFF `lossy-page1-<W>px-<File>.jpg`, an SVG
`<W>px-<File>.png`. Commons does not scale a picture up, so a JPEG, PNG, WebP or GIF no wider than the
asked step is loaded as its original, a TIFF that narrow at the largest standard width it has (never the TIFF
itself), an SVG at any width. The card takes a 500 px thumbnail by default (330 or 500 by column width since
the entry "The Catalog tab pages from the database"), the frame the 1920 px step, the
viewer the step that covers the window (`ViewerRules`, for any picture with a Commons file name). The
licence link is the release's `licUrl`, else the Creative Commons deed for `CC0` and `CC BY`/`CC BY-SA`
short names (with a jurisdiction port before 4.0); any other licence gets no link rather than a guess. Where
the licence asks for attribution, the image's credit (the Commons author of the photo or scan, not the
painter) is shown in the details panel ("Image credit") and in the viewer's credit line ("image: …"). The
artists are joined with a separator the caller passes from a string resource, since they are labels in the
UI language.
Rejected: asking the Commons API for each picture's URLs (a request per card, and the export already has
the file name), any width that is not a standard one (refused by Wikimedia), the original for every narrow
file (a TIFF original is tens of megabytes and not decodable by Android), a deed URL for public-domain marks
and other licences (not a Creative Commons deed; a wrong link is worse than none).

## 2026-11-09 — The catalog syncs at app start, file by file, from the fixed release address
One process-wide `CatalogStore` owns the catalog database, a coroutine scope of its own (not a screen's) and
the one write lock every catalog write takes. The app's start (`MainActivity.onCreate`, also after a change of
the UI language, which re-creates it; each return to the foreground too since the entry "The catalog checks
again each time the app comes back", above) wakes it: once per process the starter set goes into an empty
database, and every start runs a pass that checks only the release files that are due (`CatalogSyncPlan`, pure): `core`
and `labels-<lang>` for the chain's languages, English and `mul`, each a week after its own last check, a
language new to the chain at once, a 404 again a week later. A pass with nothing due makes no request, so
passes run at every start rather than once per process: on a TV the notification listener keeps the process
alive for weeks. A check always starts at `https://github.com/hsw/inkeasel/releases/download/catalog/<file>`
(the signed address it redirects to expires and is never stored) with `If-None-Match`, streams a 200 to a
temporary file while hashing it, and skips the import when the SHA-256 is the one imported last (a re-upload
gets a new ETag even for the same bytes). The changed files go into one swap (`CatalogImport.release`), and
their ETag and hash are written in that transaction, so a failed import, a body cut short or no network leaves
both the list and the state as they were, and the files are asked for again at the next start (a failed import
a day later since the entry "The release sync downloads outside the write lock", above); one file's failure
does not hold back the others. A language that leaves the chain loses its labels at the next swap, and its
file's state goes in the same transaction, so a return downloads it again instead of getting a 304 for labels
no longer stored. A language that joins the chain gets the starter set's labels at once (offline too), looked
for at every pass. The UI language is the configuration's first locale: the per-app language where Android 13+
sets one, else the system's.
Rejected: WorkManager (a scheduler and a dependency for a check that only matters while the app is used;
periodic work would wake a TV for a list nobody looks at), `releases/latest` (it is the newest APK release),
recording the ETag as soon as the answer arrives (a 304 would then hide a failed import), checking once per
process only (weeks without a check on a TV), a check with a HEAD request first (a second request per file;
the conditional GET already costs nothing when the file is unchanged).

## 2026-11-09 — The starter set is cut from the published release
The APK's starter set (`app/src/main/assets/catalog/`: the first 1,000 rows of the release `core` that are
not explicit, since "Explicit paintings are hidden at once; the starter set leaves them out", and their
labels in the UI languages plus `mul`, 1.1 MB gzipped; one labels file per language since the entry "The
starter set is one labels file per language") is cut from the published release files by
`tools/catalog-export.py --starter-from`, through the same writer as a full export and with no Wikimedia
request, so its rows are byte for byte lines of the release `core` and the release import replaces them in
place. It is committed and cut again after each release upload; the build stays offline. All 75
default languages fit well under the 1.5 MB budget, so the default (`--starter-langs`: the Android UI locales
that Wikidata covers, plus `mul`) stays. AGP's asset merge decompresses `.gz` assets: the APK holds
`catalog/core.jsonl` and the labels files without `.gz` (deflated, about the same size), and the app opens
those names. The export also writes `cma` (Wikidata P11110, the Cleveland Museum of Art ID, which is the museum's
accession number), to hide live Cleveland rows that duplicate a Wikidata row, and `licUrl` (Commons
`LicenseUrl`, always https, left out when Commons gives none or not a plain URL) for the licence link. The
published release gets both only when it is exported again; until then the reader treats them as absent.
`--self-test` checks the row building and the starter cut offline; it is not part of CI.
Rejected: building the starter set in Gradle from a download (a build that needs the network), a separate
export run for the starter (a second snapshot that may disagree with the release), fewer languages (not
needed under the budget), committing it uncompressed (4.9 MB in the tree for the same APK size).

## 2026-11-09 — A release import is staged and swapped in one transaction
The starter set goes straight into the live tables, in one transaction and only into a database with no
Wikidata row (so a second run reads nothing), keeping labels of the language chain, English and `mul`. A
release import (`CatalogImport`) first clears the `_stage` tables, then streams `core` and each labels file
into them in batch transactions (one batch in memory), and only then swaps in one transaction: the staged
pictures replace the Wikidata rows by id (a row already there keeps its rank, so the starter's top 1,000 do
not move), rows absent from the new `core` are swept, its entity links replace all links, each imported
labels file replaces its language (each is a `labels-<lang>` file), labels outside
the chain are dropped, and rank and dedupe are computed again. A broken file (a truncated gzip, a failed
download) or a `core` with no valid row fails the whole import before the swap, so nothing visible changes
and the paging source, which reads only live tables, is invalidated once, by the swap; leftover staging is
cleared then and at the next start. In a `core` the first row of an id wins. Labels of entities no longer
referenced stay until their language's file is imported again: removing them by reference would lose labels
when a new `core` arrives after an unchanged labels file that is not imported again. No UPSERT (SQLite 3.18):
`INSERT OR REPLACE` keeps the rank by reading it from the row it replaces.
Rejected: per-batch upserts into the live tables (a list mixing two releases while it imports, no sweep of
absent rows, a paging invalidation per batch), staging the starter set too (it goes into an empty database,
where one transaction already is all or nothing).

## 2026-11-09 — The catalog database holds only data that can be downloaded again
The catalog database (`CatalogDb`, separate from the frame and feature preferences) stores the release's
rows, the live sources' rows, the labels of the stored languages and the state of the release files: nothing
the user made. So any other schema version, older or newer (a downgrade included), drops it and creates it
empty (Room's destructive fallback both ways), and the starter set imports and the files download again.
The order is stored, not computed when the list reads it: every row has a unique `rank` and a `hidden` flag,
set in the write transaction after each import or live refresh (`CatalogRank`, pure; only the rows that
change are written), so the paging query is `WHERE hidden = 0 ORDER BY rank` on one index and the viewer
steps by the neighbour ranks. (Since schema version 2 the query also keeps `nudity < :nudityBelow`, and the
index is `(hidden, rank, nudity)`: "Each picture stores its nudity level", above.) Wikidata rows go in `core`
order; a live row that duplicates one (the same Commons file, or the same accession number) is hidden; the
other live rows take turns by source as every 10th card. Titles, artists and collections are resolved by the
query for the rows of a page only, over the language chain, then any stored language; artists come back as a
list and are joined only at the edge. The SQL keeps to what SQLite 3.18 (Android 8.0) has: no window
functions, no UPSERT.
Rejected: Room migrations for this database (code to keep for data that downloads again), ranking in the
paging query (`ROW_NUMBER` needs SQLite 3.25; a sort per page), joining the artists with a visible separator
in SQL.

## 2026-11-09 — Catalog labels follow a language chain; release rows are read leniently
The catalog's labels are looked up along a chain of Wikidata language codes built from the UI language's tag
(`LanguageChain`, pure): the regional or script variant first where Wikidata has one, then the language, then
`en`, then `mul` (Wikidata's language-independent labels); a label missing in all of them falls back to any
stored language. Android's legacy codes are mapped (`in`→`id`, `iw`→`he`, `ji`→`yi`, `fil`→`tl`). Variants:
`pt-br`, `en-gb`, `en-ca`, `en-us`, `de-at`, `de-ch`; `es-419` for Latin American regions (`es-US` included,
Android's Latin American Spanish); Chinese by script (`Hant`, or Taiwan, Hong Kong and Macau without one, is
Traditional: `zh-hant`, the regional codes with the user's own first, `zh`; else `zh-hans`, …, `zh`);
Serbian `sr-el` or `sr-ec`, then `sr`; Norwegian `nb`, `no` (Nynorsk `nn` first, then those two). The chain
names only codes the release can have a labels file for, since each one is a download and a weekly check.
The release's JSON Lines are read in batches (`JsonLinesReader`, gzip or plain; the sync hashes a file while
downloading it): a line that is not a valid row (no Wikidata id, no image, no size) is skipped and counted,
unknown keys and optional values of the wrong type are ignored, so a newer export never stops an older app;
a broken stream (a truncated gzip) fails the whole file.
Rejected: `<language>-<region>` for every region (a 404 per user each week for `ru-ru`, `fr-fr`, …), English
before the user's other variants of the same language, rejecting a whole file for one bad line.

## 2026-11-09 — Room and Paging, tested under Robolectric
The catalog database is Room (2.6.1, compiler through KSP) shown through Paging 3 (3.3.6). Both are pinned
below their newest lines because those pull a newer Kotlin standard library (Room 2.7+: 2.1, over the 2.0.21
compiler) or Compose foundation over the BOM (paging-compose 3.4+). The DAOs, the import and the paging
source are tested as JVM unit tests on an in-memory database under Robolectric (4.16.1, SDK 36), with
paging-testing, so they run in the same `testDebugUnitTest` as the pure tests, in CI and without an emulator.
Robolectric's SQLite is newer than the one on Android 8.0 (3.18), so the SQL stays within what 3.18 has.
Rejected: instrumented tests only (an emulator in every CI run and in every local test loop), no DAO tests
(the paging query, the neighbours and the staging swap are SQL), a hand-written fake DAO (tests the fake, not
the queries).

## 2026-11-09 — A full-screen viewer, opened from the details panel
**View full screen** (second in the details panel's action row; **Send to frame** keeps the first focus)
opens the picture letterboxed on black, fitted, never cropped, in a dialog that hides the system bars. A
plain press on a card still sends at once (and opens this viewer too since "A press on a card sends and opens
the viewer", above). The viewer replaces the details panel (dialogs never stack), and
Back brings that panel back for the picture then shown, not the grid: Back returns one step, and after
Left/Right the details of what is on screen are the useful ones. Left/Right go through the list of the tab it
was opened from and stop at its ends (no wrap). Any other key shows the action row with the credit line; its
CENTER only shows it (its release is swallowed, so it never also presses **Send to frame**); volume, Home and
Back stay the system's. With no frame chosen the row says so instead of a button. Back closes the viewer even
while the row is up, rather than hiding the row first: the row hides by itself, and two presses to leave would
cost more. The credit line (title; artist, source, licence) shows for 5 s on open, on every picture change
and with the row: the Commons licences (CC BY, BY-SA) ask for attribution wherever the picture is shown.
Touch: a swipe of 64 dp or more goes to the next or previous picture, a tap toggles the row. **Send to frame**
takes the card's path and keeps the viewer open; only the Bluetooth panel, when it is needed, replaces it.
The rendition is sized to the window in pixels (on a 4K TV whose UI runs at 1920 × 1080 a larger image could
not be drawn sharper) taken landscape, so a rotation loads and decodes nothing again (`ViewerRules`, pure):
Commons the smallest standard thumbnail width (960, 1280, 1920, 3840; Wikimedia refuses any other, 2560
included, with HTTP 400 or 429) that covers the fitted width or the original's, or the original as listed
when it is narrower than 1920 px; Cleveland `web` (about 893 px high) up to a Full HD window and `print` (up
to 3400 px wide) beyond, so a 2560 × 1600 tablet gets `print`; a local file as is. Coil decodes it to at most
the window's size, so a 10 000 px original costs one screen of bitmap memory. The card's thumbnail (in the
cache from the grid) stands in while it loads and stays if it fails, with the reason in the credit area. The
screen is not kept on (no `FLAG_KEEP_SCREEN_ON`): a still picture is static imagery, and Google's TV
guidance lets the screen saver or Ambient Mode start over it.
Rejected: CENTER on a card opening the viewer (one more press per upload), keeping the details panel under the
viewer (two dialogs and their focus), Back to the grid (skips the step the user came from), Cleveland `print`
on a Full HD screen (about 5 MB per picture for a modest gain over a 1.2× enlargement), a 2560 px Commons
width (refused), the display mode's size instead of the window's (decodes pixels the window cannot show),
pinch-zoom and a timed slideshow (left for later).

## 2026-11-08 — A long press on a card opens its details; a press still sends
A plain press (CENTER or a tap) keeps the one-press send (and opens the viewer since "A press on a card sends
and opens the viewer", above). A long press (tv-material `Card.onLongClick`, which
takes CENTER held; a long touch through `onTap(action, onLongPress)`) opens a full-screen details panel laid
out after Google's TV "content details" template: the title, the fields as label and value lines, **Send to
frame** first and focused, and the picture large on the right above a preview of it on the chosen frame
(the preview removed since "The details panel shows the picture large", above).
The preview runs the upload's own decode, crop (a fit with white bars since "Uploads fit the whole picture on
white", above) and Floyd–Steinberg onto the frame's inks
(`EpdImage.previewOf`) on the full image an upload would download, off the main thread, and is cancelled
with its download when the panel closes; without a frame, or with one that cannot be drawn on, the panel says
so instead. Send closes the panel and takes the card's path (`BleAsk.onSend`: a toast without a frame, the
Bluetooth panel without the permission), so dialogs never stack. Links are text: a TV launches no browser,
and a QR code would need an encoder (a dependency or several hundred lines) for little gain over a short
link (Commons gets its `curid` link).
No footer with the focused card's details under the grid for now (Google's "immersive list"): on a TV
(960 × 540 dp, 4 columns, one header row, 48 × 27 dp margins) two lines at 14 sp or more take about 56 dp,
which cuts the visible full rows from three to two; it would repeat the card's own two lines; and touch
screens have no focused card, so it would stand empty there. The details panel shows all of it on demand.
Rejected: CENTER opening the details and sending from there (one more press per upload), an overlay
inside the catalog instead of a dialog (focus would leak to the grid behind it), rendering the preview from
the card's thumbnail (Commons' is 500 px wide, smaller than the frame, so its dithering would not be the
frame's), a disabled placeholder button for the viewer (a control that does nothing).

## 2026-11-08 — A picture carries its metadata, named after the one-catalog schema
`Picture` keeps `title` and `credit` (the card's two lines) and gains what the sources tell: `artist` (all
creators as one text), `date` (as the source writes it), `collection`, `pageUrl`, `licence`, `licenceUrl`,
`width` and `height` of the original; `source` is the prefix of the id. A field a source does not give is
`null`, never a guess. The names follow the planned one-catalog schema, so the Room move maps fields instead
of redesigning them. Cleveland: the museum's name as the collection (data, not translated), the artwork page,
`share_license_status` (CC0 with its deed link), the `full` master's size. Commons: the short `curid` page
link (typable from a TV), `DateTimeOriginal` without the time of day, HTML in the metadata stripped and its
entities decoded, only `https:` links kept. Local files: the pixel size from the file header; no date (the file
time is when it was copied, not the picture's date). The mapping is pure (`SourceMapping.kt`) and tested
against trimmed real answers. `CatalogCache` writes format 2 with the new fields left out when `null`; a
format-1 file still reads with them `null` (the cache is only a fallback, and the next good load rewrites
it), a newer format reads as no cache. (`CatalogCache` went with the tabs on 2026-11-09: the live lists are
rows of the catalog database now.)
Rejected: dropping old caches on the format change (a source that is down would show nothing until it
recovers), a list of artists (the sources give one display text; the Room schema can split it), the Commons
file page URL as the link (long and percent-encoded, hard to type).

## 2026-11-08 — No poster on low-RAM devices with Android 10 or older
Android neither grants nor binds a notification listener on a low-RAM device (`isLowRamDevice()`) up to
Android 10 (`NotificationListenerService` reference), not even through adb, so the menu path, the adb command
and "Grant access" cannot work there. `ListenerAccessUi` has an unavailable state for it, checked before the
granted one: "The now-playing poster is not available on this device", the switch disabled and shown off,
and no way to the grant, so no disclosure. The stored wish (`FeatureStore.posterEnabled`, on by default) is
left untouched: with no listener bound nothing reports what plays, so `FrameController` schedules no poster
anyway, and nothing else has to keep a second flag in step. Low-RAM devices with Android 11+
may bind the listener (only the TV menu item is missing), so they keep the adb fallback.
Rejected: hiding the poster row (the user would not learn why there is no poster), forcing the stored wish
off on such devices (it would change stored data for what the system already prevents), the adb command
there (the system refuses that grant too).

## 2026-11-08 — Bluetooth is asked for in context, through one panel
The app no longer opens the system permission dialog in `onCreate`. Google asks to request a permission when
the user starts the feature and not all at startup (developer.android.com/training/permissions/requesting,
…/usage-notes). A start without the permission shows a panel over the catalog that says why ("InkEasel sends
pictures to your e-paper frame over Bluetooth. To find the frame, allow Nearby devices."), with **Find my
frame** focused and **Not now**; after a grant without a frame chosen it opens the frame settings. The
settings no longer open by themselves without the permission (they could only ask again), so the panel and
the settings never stack. "Not now", Back and any answer in the system dialog count as an answer: the panel
then never appears by itself again (prefs `permissions`, key `bleIntroDone`), only when the user wants the
feature: a pressed or shared picture without the permission opens it, and the settings keep "Allow". After a
denial it says what stops working before the dialog. Once the system no longer asks, there is no panel: the
app degrades in place, a pressed picture toasts why it is not sent and where the settings explain it, and
App info is offered only in the settings (and to a share, which opens them), following Google's advice not to
push users to the system settings. Below Android 12 the panel and the frame section name Location, which
the dialog asks for, and the panel adds that a Bluetooth search uses location and needs it on. The rules are
pure (`BleAsk`). With a frame chosen (the permission taken back later) the button says "Allow" and the pressed
picture is sent once granted.
Rejected: the system dialog at start (it covered the app before the user saw anything), the panel inside the
settings (a pressed picture would have opened a full-screen dialog to explain one permission), showing the
first-run panel on every start until granted (nagging), a panel for the permanently denied state with an App
info button (pushing to the system settings), asking again at every start after a denial.

## 2026-11-08 — The frame protocol is described in the repository
Code comments cite `docs/protocol.md`: the BLE protocol (advertising, GATT, framing, chunking, acks, timing)
and the image formats, each statement checked against the code, with what was only seen on a real frame or
only in the vendor's app marked as such. It leaves out the vendor app's internals, firmware updates and real
device identifiers.
Rejected: comments that cite a document outside the repository (a reader cannot follow them), rewording every
comment to stand alone (the byte layouts would be scattered over many files).

## 2026-11-08 — Text to read at TV distance is at least 14 sp
Google asks for text readable at about 3 m and gives no minimum size. Everything the user must read
(settings descriptions, the poster's access state and menu path, the disclosure panel including its adb
command, why a frame cannot be chosen or is not verified, the privacy address) is `bodyMedium` (14 sp) or
larger; tv-material's `ListItem` puts its supporting text in `bodySmall` (12 sp), so those texts set
`bodyMedium` themselves. 12 sp stays for secondary metadata only: a card's credit (`labelMedium`, up from
11 sp `labelSmall`) and a frame's address and advertised properties. The search hint names location only
where a BLE scan needs it (before Android 12), which makes it one line on a TV.
Rejected: a custom typography scale (every component would need checking against it), 14 sp for the
metadata too (a frame's property line would wrap on phones), restructuring the settings for shorter text
(a separate item).

## 2026-11-08 — Back from the catalog content goes to the tabs first
Back with the focus on a card or a Retry button moves the focus to the selected tab and scrolls the grid to
its first row; only the next Back leaves the app. That is Google's TV navigation guidance for a top
navigation, and before it Back several rows down closed the app at once. Phones and tablets get the same:
a grid scrolled down by touch scrolls back up on Back, without moving the focus onto a tab (it was not in the
content). The rule is pure (`CatalogBack`); the settings dialog, its disclosure panel and the share flow keep
their own Back.
Rejected: Back leaving the app from anywhere (the old behaviour), scrolling to the top without moving the
focus (the focused card would scroll out of view and the tabs would still be rows away).

## 2026-11-08 — Search runs locally first, then on live Wikidata (*planned*)
Search matches titles, aliases and artist names in every language stored on the device at once, whatever the
UI language ("Джоконда", "La Gioconda" and "Mona Lisa" find the same picture). It uses an FTS4 table over our
own normalised text (case folding, no diacritics, token prefixes, one typo on long tokens) in the framework
SQLite, so no native SQLite library ships. When the local search finds little, or the query is in a language
whose labels the device did not download, it asks Wikidata's full-text search (paintings with an image), then
the sources' own search APIs.
Rejected: FTS5 or the `unicode61` tokenizer (not guaranteed in the SQLite of minSdk 26 devices), a bundled
SQLite (`.so` files and their 16 KB alignment checks), a search server of our own.

## 2026-11-08 — The catalog is exported from Wikidata and shipped as files, without a server
A script in the repository, run by hand or by a weekly CI job and never on devices, exports paintings with an
image and at least 3 Wikipedia articles (about 9,600). Their metadata is CC0 and their files are on Commons,
which hosts only free files, so there is no copyright or death-date filter. The script records each image's
licence, attribution flag and credit, and the app shows the credit where attribution is required. The
output is JSON Lines sorted by fame: a language-neutral core and one labels file per language (label,
aliases, Wikipedia article title), published gzipped as GitHub Release assets rather than committed weekly.
The device downloads the core, English and the UI language (about 1.2 MB), more languages on demand. The
release also carries one file with every language for offline search in all of them. The APK carries a
starter set, the top 1,000 with labels in every UI language (about 1.1 MB), which shows at once on first
launch while the full catalog downloads and imports in the background. The download replaces the starter rows
by Wikidata id in one transaction; the starter set stays if the download fails. A weekly update fetches only
changed files (ETag). The export is `tools/catalog-export.py`; it keeps each Query Service result on disk and
retries only the failed queries, and fails rather than write a partial catalog. Its files are the assets of
one GitHub release with the fixed tag `catalog`, replaced on each update, and the app reads
`https://github.com/hsw/inkeasel/releases/download/catalog/<file>`. Asset names are flat (`core.jsonl.gz`,
`labels-<lang>.jsonl.gz`, `labels-all.jsonl.gz`), since a release asset cannot sit in a directory.
Implemented (2026-11-09): the export, the starter set (one labels file per language), its import, the
staged release import and the weekly check per file; the device fetches `core` and the labels of its
language chain, English and `mul`. Still *planned*: the weekly CI job for the export, and the
all-languages file on the device, which waits for the search.
Rejected: one labels file with all languages on the device (3.8 MB gzip, but about 16 MB of text and an
estimated 30–40 MB in SQLite with the search index, too much for 8 GB TV boxes), querying Wikidata from
devices for the catalog, a backend of our own (not needed; the format lets a server serve it later),
committing the export weekly (repository growth), `releases/latest` for the catalog (it is the newest APK
release).

## 2026-11-08 — A multilingual app with no preferred language (*planned*)
English is the base. The app is translated into the languages that both Wikipedia/Wikidata and Android
support, and none gets preference: Russian is one translation among the others. Picture titles and artists
come from the catalog in the UI language, falling back to English, then to the original label. Translation
feedback goes to GitHub Issues.
Implemented so far (2026-11-09): catalog titles, artists and museums follow the UI language's chain, then
English, then any stored label. The translations beyond English and Russian are still *planned*.
Rejected: keeping English and Russian as the two first-class languages.

## 2026-11-08 — One "Catalog" section, sorted by fame, with filters and search
Every source (the Wikidata catalog and the live museum and Commons sources) is mapped to one schema in a local
Room database and shown as one Catalog section instead of a tab per source. The default order is fame: the
number of Wikipedia articles (Wikidata sitelinks), found for museum pictures through the museum's
external-ID property on Wikidata; pictures without a match rank by the source's own signal, after the matched
ones. The same painting from two sources is one entry, joined by its Wikidata id. Filters (museum, artist,
period, movement, genre, orientation, fits the frame) and search narrow the same Paging 3 list; "Landscapes"
becomes a genre filter. Navigation shrinks to Catalog, My pictures and Settings.
Implemented: the one section over Room and Paging, sorted by fame, the live sources as its rows (deduplicated,
interleaved for now: 2026-11-09) and the navigation; the filters and the search are still *planned*.
Rejected: a tab per source (does not scale past a few sources and repeats the same painting), sorting by
source order or at random by default.

## 2026-11-08 — What plays is logged only in debug builds
Now-playing titles, poster keys (an artwork URL or the SHA-1 of the artwork pixels, also as `onFrame`) and the
artwork addresses in a poster upload's error reach logcat only through `MediaLog`, which passes them as they
are in a debug build (`BuildConfig.DEBUG`, so `buildConfig` is on) and writes `<title>` / `<key>` in a release
build. Package names, session ids and the user's own picks (`default:` and `manual:` keys, catalog titles,
shared file names) are not what plays and stay. A redacted error keeps its class, its stack trace and the rest
of its message (e.g. `HTTP 404: <key>`). Logcat is readable over adb and lands in bug reports, and the
disclosure before notification access should not need a clause about the log, so it and `PRIVACY.md` drop it.
The cost: diagnosing a poster problem by film needs a debug build; a release log still shows every step.
Rejected: keeping the titles in release logs and disclosing them (the earlier choice; it widens what the user
has to accept for diagnosis only), dropping the log lines entirely (they are what a poster problem is debugged
with), a short hash of the key instead of a placeholder (it would still link log lines to one film).

## 2026-11-08 — No cleartext traffic on any Android version
The main manifest sets `android:usesCleartextTraffic="false"`. targetSdk 28+ makes that the default only from
Android 9; on Android 8.x (minSdk 26) cleartext was still allowed, and OkHttp follows a redirect from https to
http. With the attribute, the platform's policy (which OkHttp honours; Coil uses the same client) refuses every
`http://` connection, a redirect included, so `PRIVACY.md`'s "over https" holds on every supported version.
No catalog or artwork address is `http://` (the catalogs publish https, poster art uses only https URIs).
`ManifestTest` guards the attribute and that no overlay or network security config relaxes it.
Rejected: a network security config (one attribute says the same), an OkHttp interceptor that rejects
downgrade redirects (covers OkHttp only, not every client the platform policy covers).

## 2026-11-08 — "My pictures" stays in the app's external files folder
Shared and copied pictures live in `getExternalFilesDir()/pictures`: Android deletes it with the app, it needs
no storage permission, and `adb push` can put pictures there (the empty tab shows that path). The cost
is that on Android 9 and older, and on Android 10 for apps that opt out of scoped storage, any app with the
storage permission can read the folder. `PRIVACY.md` and the README say so instead of calling all stored data
private; everything else stays in internal storage.
Rejected: moving the folder into `filesDir` (private on every version, but no way left to copy pictures in
from a computer).

## 2026-11-08 — The listener follows media sessions whenever the access is granted
`NowPlayingListener` reads every media session (title, state, artwork; artwork bitmaps cached as PNGs) as
long as notification access is granted, also while the poster is switched off. `FrameController` needs that:
switching the poster on acts on what plays at once (`onPosterSwitched` arms the start of a film that is
already playing, whose PLAYING is not reported again), and switching it off reverts a poster even after a
process restart. `PRIVACY.md` and the README say exactly this (reading while the access is granted, the
artwork cache, the `onFrame` key that may be an artwork URL, the BLE rescan during an upload, the hold
service), and that revoking the access is what stops the reading; the disclosure panel before the grant says
it too. Titles and artwork keys reach the logcat lines only in debug builds (see "What plays is logged only
in debug builds" above).
Rejected: gating the listener on the switch (the poster would come up only on the next transition into
PLAYING after switching it on, and a lost revert would wait for one).

## 2026-11-08 — No backup and no device transfer of app data
`android:allowBackup="false"` stops backup only up to Android 11; from Android 12 a device-to-device transfer
ignores it. `android:dataExtractionRules` (`res/xml/data_extraction_rules.xml`) therefore excludes every
domain for both cloud backup and device transfer, so "nothing goes into a backup or a transfer" in
`PRIVACY.md` holds on every version. Each domain is listed on its own (`root`, `file`, `database`,
`sharedpref`, `external` and the four `device_*` ones): the backup agent walks the files, databases and
shared preferences as trees of their own and matches an exclusion by the exact path of what it visits, so an
exclusion of `root` (the app's data folder itself) never matches inside them and left the preferences and the
catalog files in a transfer. `ManifestTest` guards the manifest and every domain in both sections.
Rejected: allowing the transfer and saying so (it would carry the chosen frame and cached artwork to a new
device to save one frame choice, at the cost of an exception in the policy).

## 2026-11-08 — TV overscan margins by window width, lint in CI
The catalog keeps the Android TV overscan margins, 48 dp at the sides and 27 dp at the top and bottom, in the
widest `CatalogLayout` bucket (840 dp and up); narrower windows keep 24 dp top and bottom. The bucket is chosen
by width only, so a landscape tablet gets the TV margins too, which costs it 3 dp. The settings dialog already
pads 48 dp at the sides and 32 dp at the top and bottom, so it keeps its own vertical padding. A focused row
there grows by tv-material's default focus scale and its highlight reaches into the margin while it has focus;
that is the library's focus indication, not content, and stays. CI runs `lintDebug` with the build, so a new
lint error fails it; warnings are only reported.
Rejected: detecting a TV (`UiModeManager`, the leanback feature) for the margins (a second layout input next
to the width, for 3 dp on tablets), the 27 dp in the settings dialog as well (it would shrink a padding that
already clears the margin), a lint baseline (there are no errors to freeze).

## 2026-11-08 — TV banner with the app name, vector adaptive launcher icon
The TV banner keeps the original motif (a light frame line around the five e-paper colour stripes on dark
grey) and its black label now reads "InkEasel" instead of "Frame". It is a 320×180 PNG in `drawable-xhdpi`,
the size and density Android TV asks for; in the densityless `drawable` folder it was taken as mdpi and
scaled up twice on a TV. The text uses Pillow's bundled Aileron Regular (CC0), so no font licence comes
along. The launcher icon is an adaptive icon in `mipmap-anydpi` (minSdk 26, so it serves every API level)
with vector layers: a dark grey background colour, the same frame and stripes as the foreground, kept inside
the 66 dp safe zone so no launcher mask cuts the frame's corners, and a monochrome layer for themed icons in
which the stripes alternate full and half alpha. The old 192 px `mipmap-xhdpi/ic_launcher.png` is deleted:
nothing uses it once the adaptive icon exists. Lint now warns `IconMissingDensityFolder` for the single
banner density instead of `IconLocation` for a bitmap in `drawable`; it stays a warning, since Android TV
specifies the banner at xhdpi only.
Rejected: a 432 px bitmap foreground (the motif is plain rectangles, a vector is sharp at every size), banner
copies for other densities (not asked for by Android TV, more files to keep in step), a `roundIcon` (the
adaptive icon covers round masks).

## 2026-11-08 — Target API 36 with AGP 8.9.1
compileSdk and targetSdk are 36 (Play's target requirement). AGP goes from 8.7.3 to 8.9.1, the oldest
release that supports API 36; it still runs on Gradle 8.11.1 and Kotlin 2.0.21, so neither moves, and no
library changes. The release APK already meets the 16 KB page-size rule: Compose's only native library
(`libandroidx.graphics.path.so`) has 16 KB-aligned LOAD segments in all four ABIs and is stored 16 KB-aligned
(`zipalign -c -P 16`), so no Compose bump is needed. The newer lint brings warnings only (mostly `UseKtx`);
they are left for a separate cleanup. Android 16's changes were checked on an API 36 tablet emulator: the
app already draws edge to edge, Back (key and gesture) closes the disclosure panel, then the settings, then
leaves the app, and rotation or resizing does not recreate the activity (it declares no orientation or
resizability limits, which Android 16 would ignore on large screens anyway).
Rejected: AGP 8.10–8.13 or 9.x (newer than needed; 8.11+ also needs Gradle 8.13), keeping compileSdk 35
with `android.suppressUnsupportedCompileSdk` (an untested combination).

## 2026-11-08 — Release build: R8 shrinking without obfuscation
Release uses R8 (`isMinifyEnabled`, `isShrinkResources`, `proguard-android-optimize.txt`) with
`-dontobfuscate`. The code is public, so renaming protects nothing, while it would make crash traces from
GitHub APKs unreadable without a published `mapping.txt` and turn `Throwable.userText`'s class-name fallback
into meaningless names. R8 reported no warnings and no missing rules, and the release smoke tests (catalog,
image decoding, settings, disclosure, share, manual upload and poster on a real frame) needed none, so
`app/proguard-rules.pro` holds no keep rule: the libraries' consumer rules cover them. The release APK went
from 6.3 MB to 1.4 MB.
Rejected: obfuscation with `mapping.txt` uploaded by `release.yml` (more moving parts, no benefit for an open
project).

## 2026-11-08 — Privacy policy link as plain text
`PRIVACY.md` in the repository; the settings show its URL as text, in the last row of "Permissions and
features". Phones and tablets open it on a tap (`startActivity` in a try/catch, no `resolveActivity`, which
needs `<queries>` on Android 11+); TV only shows it, since a TV app must not launch a browser (TV-WB). The
row stays focusable on TV, with a click that does nothing: it is the last thing in the settings, and the
D-pad scrolls only to focusable rows, so a plain text there could not be brought into view.
Rejected: a QR code (needs a new dependency such as zxing), a WebView.

## 2026-11-08 — Prominent disclosure before notification access
Every way into the notification-access grant (the poster switch and the "Grant access" button) first shows an
in-app panel that says what the system grant covers (all notifications) and what the app actually reads (the
playing app's media-session title and artwork; notifications are ignored), and that only the poster leaves
the device, to the user's frame over Bluetooth. "Continue" is the affirmative action Play's User Data policy
requires; "Cancel" and Back change nothing. The text also says that artwork given as a web link is downloaded
from there, since that is a network request the user should not be surprised by, and that the reading goes on
as long as the access is granted, also with the poster switched off, with recent artwork kept in the cache;
with the poster off nothing of what plays leaves the device. It says nothing about the log: a release build
logs no title or artwork address (debug builds do, for development). Play asks the
disclosure to cover access the user would not expect, and reading with the poster off is that.
"Continue" from either entry point remembers the poster wish as pending (the access serves only the poster),
so "Grant access" now switches the poster on after the grant as the switch does; a press of the switch alone
no longer stores anything. The panel is inline in the settings, under the poster row, rather than a second
dialog: the settings are already a dialog, and an inline panel keeps one Back handler and one focus path
(focus moves to "Continue" when it opens and back to the element that opened it when it closes). Where no
system screen leads to the access (adb-only TVs) the panel shows the adb command instead of "Continue", and
nothing is stored. The other ways to the grant are hints, and they too appear only in the panel: the TV menu
path (as the way to go, or as the fallback when the list screen's rows are out of the D-pad's reach) and the
adb command. The menu path is repeated under the poster switch once "Continue" has made the wish pending, so
the user finds it again after the system screen. Rejected: a separate `AlertDialog` (nested dialogs on TV,
focus return harder), showing the disclosure only once (the grant may be asked for again after a revoke, and
the disclosure belongs right before each request).

## 2026-11-08 — Uploads: poster in-process, manual uploads in a foreground service
Automatic uploads (now-playing poster, revert to the default picture) run in the app process without a
foreground service: while notification access is granted the system keeps `NowPlayingListener` bound, which
keeps the process alive for a 10–30 s BLE transfer. Android TV forbids foreground services once the user
has left the app (the poster always runs while a player is in front), and the service's notification is
hidden on Android 13+ anyway. One in-process engine keeps the single queue and its rules for both kinds.
When the listener is not connected (access granted but not yet bound, or the binding lost while a controller
timer fires) the engine still takes the request, and a payload-free "hold" start of `UploadService` keeps the
process in the foreground until that work is done. Manual uploads and shares stay on the short
`connectedDevice` foreground service, which may outlive the app for a few seconds. The listener's binding is
read from a flag it sets first on connect and clears first on disconnect; the controller's uploader
(`AutoUploader`, pure, so the hold fallback and a refused hold start are unit-tested) submits every request
before it starts the hold, so a refused start (a background start on Android 12+) is only logged and the
engine still uploads and reports the request once. When the listener unbinds (`onListenerDisconnected`, or
`onDestroy` without it) after clearing its flag, `AutoUploader.listenerLost` makes every unheld request still
queued or in flight held work and starts the hold for it; `AutoUploader` decides and submits under one lock,
so a request is either promoted or decided after the flag was cleared. The engine is the only reporter:
`UploadService.onDestroy` reports nothing, and a process death takes the controller with it. A request
replaced in the queue is reported at once, from inside the `submit` that replaced it, so the controller's
`onUploadResult` re-enters under its own lock (a revert that replaces a queued poster may arm a second revert
timer, which then finds the default on the frame and does nothing). The report rules are a pure
`UploadReports` that the controller tests use as they are; why a request was dropped (`REPLACED` or `STALE`)
only chooses the log line, but stays explicit for the tests. A failed draw wait (`DrawGate`) lets the upload go
ahead without it rather than leave the queue stuck. `UploadService`'s stop logic (stay while held work is
left, `stopSelfResult(lastStartId)`) is Android glue checked on an emulator, not by unit tests.
Rejected:
- expedited WorkManager — on Android 11 and older (the Shield) it runs as a foreground service with a
  notification anyway, has quotas and would split the queue rules;
- no foreground service on TV only — two upload paths for no gain;
- `CompanionDeviceManager` — unclear support on Android TV (`companion_device_setup`), changes frame pairing;
  kept as a backlog idea;
- `shortService` for manual uploads — Android 14+ only; may be revisited if the Play foreground-service
  declaration becomes a problem.

## 2026-11-08 — `UploadService` is not exported in release builds
Any installed app could otherwise make InkEasel upload a file or URL of its choosing to the user's frame. The
service is exported only by a debug manifest overlay, for the documented adb trigger, and it accepts only
manual uploads (no automatic-upload extras), so even the debug export cannot touch now-playing work.
A JVM test guards the manifests. The overlay repeats `foregroundServiceType`, because lint's
`ForegroundServiceType` check reads the overlay's `<service>` element on its own.
Started without the Bluetooth permissions, which only the debug adb trigger can do, the service toasts and
stops.
Accepted residual: `ShareActivity` stays exported (it is the share target) and uploads a shared picture
without asking again, so an app that may start activities (one in the foreground, or exempt from Android's
background-launch limits) can still send an image of its choosing to the frame, through the visible share
path and with a `content://` grant of its own. A share is a user action by design, the sender has to be in
front to start it, and the picture also lands in "My pictures", where the user sees it. Rejected: a
confirmation step before every share (one more press for every real share), checking the referrer (it is
set by the sender and proves nothing).
Rejected: keeping it exported behind a custom signature permission (adb's shell cannot hold it), or a
runtime check of the caller (a `Service` start carries no caller identity).

## 2026-11-08 — `bluetooth_le` stays required
BLE is the app's only transport, so the feature stays `required="true"`; lint's `UnsupportedTvHardware` is
suppressed in the manifest because Bluetooth is not on Google's list of features TVs may lack.
`ACCESS_COARSE_LOCATION` is declared up to Android 11 next to the fine one.
