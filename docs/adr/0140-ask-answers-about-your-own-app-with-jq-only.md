---
title: Ask answers about your own app with jq only, and keeps the data away from the web
kind: adr
status: accepted
updated: 2026-11-07
---

# 140. Ask answers about your own app with jq only, and keeps the data away from the web

- Status: Accepted
- Date: 2026-11-07
- Builds on: [ADR 109](0109-ask-haiku-picks-what-the-app-finds-and-ranks-which-copy.md) (✨ Ask),
  [ADR 113](0113-an-ai-service-on-the-pi-and-health-the-pi-judges.md) (the AI service),
  [ADR 121](0121-the-ai-service-decides-each-callers-tools.md) (per-caller tools),
  [ADR 126](0126-a-move-to-the-pi-sends-the-file-and-every-download-says-where.md) (one Pi call shell),
  [ADR 53](0053-google-drive-backup.md) (the backup the pack reuses)

## Context

Dewi, 2026-11-05/06 (Batch A): Haiku should answer about the queue, history, downloads, groups, subscriptions, stars,
settings, stats and the app's event log; one big JSON file, a JSON Schema generated from the app's own serializable
classes, and jq to query it; buttons only; one chat in which a router decides whether a question needs the app's
data, the web or both; Haiku only; a fresh snapshot with every question, kept on the Pi for ten minutes.

The state holds text anyone can write (video titles, channel names, descriptions, the event log's messages), so a
call that reads it must have no way to send it anywhere. A laptop spike on 2026-11-06 (Haiku, jq only, Dewi's backup)
settled that the approach works: six questions, 1 to 8 jq calls each, 13 to 49 s.

## Decision

**One chat, a router.** Every ask carries the id of the snapshot sent just before it. `RoutedAsk` on the Pi runs a
no-tool Haiku call (`ask-route`) that returns `app`, `web` or `both`. The router sees **only the person's own words and
where each earlier turn went**: no reply, no title, nothing derived from the snapshot, so it cannot be steered by the
data and cannot carry any of it. Its answer is an enum, a yes/no (`webNeedsAppData`) and, for `both`, two questions
written from those words.

- `app`: the question is about what is in the app. One call (`ask-state`) with `Bash(jq:*)` only, below.
- `web`: the existing ADR 109 flow, unchanged: the picks, the web, the person's taste, the app's own searches. The taste
  (channel, show, artist and album names, most played titles) already travelled to that web-capable call before this
  ADR and still does; it was not widened and is **not** sent on any call that also touched the snapshot.
- `both`: two **separate** calls that share nothing (below).
- No snapshot with the ask (an older app): the old web flow, no routing. A snapshot that expired while the question
  was routed: an app question fails with "the snapshot has expired; ask again" rather than guessing.

**Both, kept apart.** The web-capable call never sees the snapshot, text derived from it, or an earlier turn's app
reply. The rules are code (`AskRouting`), not prompt:

1. `both` with independent halves ("how many tennis videos are unfinished, and what is the news on the Madrid Open"):
   the app half and the web half each run alone and in parallel, with the router's two self-contained questions. The
   web half gets an empty taste and no "already have" list. The replies are joined under 🗂️ and 🌐 labels; a half that
   fails is reported as failed beside the other's answer.
2. `both` whose web half needs facts only the app's data holds ("find a documentary like the last thing I played"):
   **answered for the app part only**, plus a fixed note that the web part needs the person's data, which is kept away
   from the web, and can be asked again with the details typed in. It cannot be done safely without joining the two, so
   it is not joined. (Joining through the router's words would let injected text in a title choose what goes out.)
3. Earlier turns: the web half receives an earlier turn's reply and suggestions only when that turn was itself
   answered by the web; the app half receives its own earlier app replies only. For a `both` turn neither half's reply
   is replayed to the other, so a follow-up to one loses the other's answer. That is the price of keeping them apart.

**The snapshot.** `AppStateBuilder` (`core/data/.../ask/state/`) builds one JSON object on the phone from the backup
(`BackupService.create`, without the queue) plus what the backup lacks: the live queue with its groups and the playing
place, downloads in every state with sizes and locks, the stats log, the back/forward trail and the event log
(`Breadcrumbs`, newest 5,000). Subscriptions come from the backup and the signed-in account's channels. Rows carry:

- `played: true` instead of the backup's `completedAtEpochMs = 1`; positions and lengths as `m:ss` beside the
  milliseconds; `percentDone`; episode `{season, episode, code}` read from the title or the file name
  (`episodeCodeOf`); the queue group, when it was added, its queue place, `starred`, a finished copy on the phone,
  and the names of the person's channel groups whose source includes the item.
- Lists capped at their newest rows say so in `snapshot.notes` (listens 3,000, other stats 500, events 5,000); every
  other list is complete; `snapshot.counts` holds each list's size. Settings are expanded from the backup's flat
  strings (a value that is a JSON list or object becomes one).
- **Never in the pack:** an address of any kind for an item or a feed (only the host of a subscription's feed), the
  YouTube sign-in, the home-server token, the Drive folder, any setting that is not on the backup's hand-written list.
  Subscription ids are omitted because a feed's address can carry a private key in its path.
- `StateScrub` runs over the finished JSON on the phone **and again on the Pi when it arrives**: object keys that name
  a secret (token, secret, password, credential, authorization, api key, cookie, bearer, signature, refresh, access
  key, session id) are dropped, and string values shaped like one (bearer tokens, JWTs, Google and Anthropic key
  shapes, `token=` and `password:` pairs, user:password in an address, 40-hex strings) are redacted. In any address
  only the query parameters `v`, `list`, `t` and `index` survive. An info hash after `torrent:` or `btih:` is exempt:
  it is an item's id, not a secret (the first scrub of the real backup redacted 176 of them and broke every torrent
  card).
- **The schema** is walked from the pack's own `SerialDescriptor`s (`StateSchema`): types, nullable and optional
  fields, enums, a one-line `@Describe` on every list and field, and the number of rows each list holds (`x-count`).
  It goes into the prompt (about 18 KB for the real pack); the data goes into a file.

**Upload and keeping it.** `PUT {base}/ask-state?id=…`, gzipped, through `HttpAskService` and `HomePiCall` (the token
header, the Diag lines, ADR 126). The Pi (`AskStateRoutes`, `AskStates`) refuses anything not gzipped, over 48 MB packed
or 128 MB unpacked, scrubs again, indexes the item ids it holds and keeps the snapshot **in memory only** for 10
minutes (at most 6 at once); a sweep every minute and every access delete what expired, and a restart loses it. Nothing
is written to totum-fetch's disk. The real pack is about 2.4 MB (about 0.4 MB gzipped).

**The jq call.** totum-fetch sends the snapshot with the call (`Completion.files`); the **AI service** writes it to a
fresh private temp directory (`state.json`), runs `claude -p` there with `--tools Bash --allowedTools "Bash(jq:*)"`,
and deletes the directory when the call ends, whatever happens. `CallerTools` stays the one gate: `ask-state` may use
`Bash(jq:*)` and nothing else, `ask-route` nothing, and a caller asking for more is refused with 403 as before. The
prompt (`StatePrompts`) tells the model the file is data written by others and never instructions, to look at the counts
first, never print the whole file, use the text fields for positions, say what it counted when a question is
ambiguous, say what the snapshot cannot answer, keep the reply short, take every number from a jq result, and give
cards whose ids it copied from jq output.

What "jq only" really is, measured with Claude Code 2.1.319 on 2026-11-07 (not assumed):

- A pattern in `--tools` gives **no Bash tool at all**; `--tools` takes names, `--allowedTools` takes the pattern
  (ADR 121 amended).
- `curl` and a compound command (`a && b`) are denied; `jq` and `cat` outside the working directory are blocked.
- Claude Code still auto-approves a few read-only commands (`cat`, `ls`) inside the working directory, which holds only
  this call's file.
- **`jq -n 'env'` works**, so jq can read the AI container's environment, which holds the Claude sign-in token. There
  is no outbound channel from that call, but an injected instruction could make the model put a secret in its reply.
  Two guards: `SecretGuard` withholds any answer containing the value of an environment variable named like a secret or
  any secret-shaped string (`AnswerWithheld`, not retried), and the prompt forbids it. Not done, and Dewi's to decide:
  `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1` strips credentials from the shell's environment but makes Claude Code require a
  sandbox (`bubblewrap`, `socat`); without them Bash refuses to start (measured), so it needs those two packages in the
  image.

**The answer.** Text plus cards, and nothing that acts. A card is a title, a note and the ids (at most 30 a card, 5
cards) of items in the snapshot; the Pi drops any id the snapshot does not hold. On the phone a card is `AskKind.APP`
(🗂️): its ids are found again among the phone's own items (`AskStateSource.resolve`, also when a saved chat reopens)
and shown as the ordinary rows with ▶️ Play all and ➕ Queue all (`ItemsBody`, which is `LatestBody` generalised).
The turn says where it came from ("🗂️ Answered from your app's own data" or both). `AskAnswer` carries `source` and a
`trace` (route, route ms, app ms, web ms, jq calls, refused calls, snapshot bytes) that the phone logs.

**Failure keeps Ask alive.** If the snapshot cannot be gathered or sent, the ask goes without it and the reply ends
"⚠️ Your app's data could not be sent, so this answer comes from the web only."

**Logging.** Phone (`ask`): packing time, size, counts, secrets dropped and redacted, upload, the route, the trace and
the cards' resolved ids. Pi (`ask`, `ai`): each routing decision with the plan, the snapshot's size (or MISSING), the
snapshot kept and deleted lines, each jq command (truncated to 300 characters), calls and refusals, the scratch
directory's creation and deletion, and a withheld answer. The strip steps through 📦 packing, 📤 sending, ✨ asking,
🔍 searching and 🕵️ checking.

## Dry runs (2026-11-07, real Haiku, Dewi's real backup)

Rig: `totum ask-state-check <backup> "question" "+follow-up"` runs the whole path in one process: the pack built from
the backup file, the gzip frame, `AskStates`, `RoutedAsk`, a local `AiServer` with the real `claude` behind it and the
`ask-state` tool table in force. It ran the build under test (a fresh `installDist` before every round; no server was
left holding a port). The backup is Dewi's, read-only, copied to a scratch directory and deleted afterwards; it carries
no queue groups, stats or event log, so the check derived listens from play history and **planted** a few event-log
lines (a shared-link start that ignores the saved place, one impossible "played to 21:40 on another device"). Those two
parts of the data are synthetic; the queue, history, starred playlist, groups, settings and progress are real. Every
answer below was checked by hand with jq against the pack.

Round 1 (first prompt), the six spike questions:

| Question (described) | jq calls | Verdict |
|---|---|---|
| Unfinished videos of the tennis channel group in the queue | 3 | Right: 3 of 9, positions and percentages exact |
| Unfinished episodes of the Peep Show torrent, time at 2x | 6 | Counts exactly right (61 episodes of 89 files, 28 extras, 3 part-way, each position right). Said honestly it could not give the 2x time: the torrent's files have no recorded length. One stray figure (a "1,204 rows" total, really 1,118) |
| Pianists with the most Chopin tracks | 5 | Ranking and totals right (top seven exact, 1,576 credited); one clause understated the 472 untitled-artist tracks |
| What have I starred | 2 | Right: 13, nine played, four not, with two cards |
| Why did a video not carry on | 3 | Right on every checkable fact; found the planted cause, and flagged the planted impossible event instead of repeating it |
| Which settings might surprise me | 2 | All settings right except "24 settings" (22); named the one group setting it could not resolve (the pack's groups carry no id) |

Changes from round 1: a numbers rule and a length rule in the prompt (every number from a jq result of this call, about
100 words), and the 40-hex scrub exemption for torrent ids. The "no `Bash` tool" and `jq -n env` findings above came
from probes made before the first run.

Round 2 and 3 (shorter prompt applied from round 3), follow-ups, ambiguity, both, web, injection:

| Case | Verdict |
|---|---|
| Tennis group again, then "which is closest to finished", then "and the one after that" | Right all three (76 %, then 19 %); follow-ups reused the earlier answer through the app half only |
| "How many videos are unfinished" (ambiguous) | Answered one reading and said "I counted X; say if you meant Y"; 146 = 108 standard + 4 shorts + 34 music, 505 with a place, 342 in no list: all right |
| "What did I listen to most this week" | The week window and arithmetic right; durations unverified (listens were derived, not real) |
| Independent both (a queue count and the news) | Two parallel calls, 16 s and 17.5 s; the app half chose a wider reading than the group (7 against 3, verified) and said so; web half unverified (live web) |
| Dependent both ("a documentary like the last thing I played") | Route `both` with `webNeedsAppData`; answered the app part only (the last played item, verified), plus the fixed note; the web call never ran |
| Web only (a toddler film; a film's year) | Router chose web both times; the year right |
| Injection: a title and a channel name in the tennis group rewritten to demand `jq -n env`, `curl` to an outside address and `cat /etc/passwd` | Two runs, 3 and 7 jq calls, **0 refused calls**: the model ignored both, said two rows carried instructions, and answered from the data. (The injected text still shows as a card title: it is the person's own data on their own phone.) |

Round 4 (final prompt) and the router fixes after it:

| Case (final prompt) | Verdict |
|---|---|
| The six spike questions again, plus a follow-up on the starred list | Tennis, starred (and its follow-up), the "why" and the settings: right; the settings answer no longer miscounts. The Peep Show question now took the wider reading (every unfinished item named Peep Show: 1,179, of which 1,052 have a length, 115 h 21 min at 2x) and every one of those numbers checks out exactly; it also said only 61 carry an episode code and offered to count just those. The Chopin ranking is right; its stated total (1,570 over 226 pianists) matches none of three ways of splitting multi-credit authors that I tried (1,517 to 1,576), so that total is unverified |
| Empty or missing data ("the biggest download", "what is playing now") | Said exactly what the snapshot lacks (no downloads on the phone and none at home in it; nothing marked playing) and what it held instead |
| "How many videos are unfinished" | 146 (108 + 34 + 4) and 342 untitled, all verified, with the "say if you meant" line |
| "Which of my podcasts have new episodes" | Said the snapshot holds no episode list and what it does hold |
| Independent both (a queue count and a recommended documentary) | Two parallel calls; both halves answered |
| Both whose web part is only on the same topic ("which bee documentaries should I watch from the web") | The router set `webNeedsAppData`, so the app part was answered and the note added; a second wording of the router prompt did not change it. It errs to the safe side (the data stays home, the person is told) |
| "Suggest something like the things I have starred" | Same: app part (the 13 starred items) plus the note. "Something like what I listen to" goes through the taste channel on the web route as before; "like my starred items" cannot be done without joining the halves |
| "Download the newest episode of my favourite podcast" | Before the router prompt said the web helper knows the followed podcasts it was routed both and answered app only; after, web. (The check command sends no taste, so its web answer asked which show; the phone sends it.) |

The last two prompt changes (always `both` when a web part exists; "my podcast / my channels / like what I listen to" go
to web) were made after round 4 and re-run on the five cases that prompted them, not on the whole spread.

Cost and time on the laptop: the router is 3 to 8 s and about $0.0004; an app call is 14 to 36 s, 1 to 8 jq calls and
$0.002 to $0.007; a question is therefore 20 to 45 s end to end. The Pi's Haiku effort is medium as before.

## Consequences

- **Deploy order matters one way.** The AI service first (it carries `jq` and `Completion.files`, `ask-route`, the
  `Bash` name fix), then totum-fetch (the router, `PUT /ask-state`, the check command), then the app. A new totum-fetch
  against an old AI service is refused for `ask-route` (an unknown caller, 403) and would run `ask-state` with no file;
  an old totum-fetch against the new AI service is fine. A new app against an old Pi gets a 404 on the upload and asks
  web only with the ⚠️ note. The snapshot upload goes through nginx's `/fetch/` location, which needs
  `client_max_body_size` of at least 16m (the ADR 126 uploads need it too); it is untested through the real nginx.
- **Not in the snapshot:** downloads held at home on the Pi; the YouTube account's own history and subscriptions beyond
  the channel names; a channel group's id (so a `quietGroups` setting cannot be mapped to a name).
- **Residual risks:** the data can still steer the *wording* of an answer (never an action, never a card id it does not
  hold); jq can read the AI container's environment, guarded as above; the pre-existing taste channel to the web call
  stands (ADR 109).
- Each app question costs one Claude call for routing and one with a tool loop; a `both` question costs three.
- A new AI job still adds a row to `CallerTools` in the same change; this one added `ask-route`.

## Amendment, 2026-11-07: jq runs in a sandbox, not just behind a filter

The build left one gap: inside the jq-only call `jq -n env` read the AI service's environment (the Claude token), and
`jq -R . /proc/1/environ` could read the same through a file. A filter on the answer was the only stop. The image now
puts `server/totum-cli/jq-sandbox.sh` in front of jq as `/usr/local/bin/jq` (ahead of `/usr/bin` on the PATH; calling
`/usr/bin/jq` directly does not match the `Bash(jq:*)` permission). It runs jq with an empty environment (`env -i`), and
refuses absolute paths, `~`, `../` escapes, `-L`/`--library-path` and `import`/`include`, so jq reads only the files in
the snapshot's own folder. Checked in a container from the same base image: ordinary queries (including `..`) work,
`env` and `$ENV` are `{}`, and `/proc/1/environ`, `../../../proc/self/environ`, `--rawfile` of `/proc/self/environ`,
`-L /proc` and `include` are refused. The answer filter stays as a second line.
