# Openfire Message Moderation — XEP-0425

Lets a **moderator** of an [Openfire](https://www.igniterealtime.org/projects/openfire/) group chat
room retract another occupant's message **for everyone**: live in every occupant's client, in the
room's archive (MAM), and in the join history new occupants receive. It implements
[XEP-0425: Moderated Message Retraction](https://xmpp.org/extensions/xep-0425.html), version 1
(`urn:xmpp:message-moderate:1`), which is what Conversations speaks.

Openfire 5.0.5 and later already do the groundwork in core: every MUC message carries an
XEP-0421 occupant-id and an XEP-0359 stanza-id issued by the room. But nothing in core, or in
the Monitoring plugin, answers a moderation request. As a result Conversations offers no
*Moderate* action on Openfire, and flags public rooms as non-compliant (it expects occupant-id,
MAM and moderation together). This plugin fills that gap. It needs no configuration and adds no
database table.

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Build](#build) · [Install](#install) · [Using it](#using-it)
- [Configuration](#configuration)
- [Security](#security)
- [How it works](#how-it-works)
- [The protocol, as implemented](#the-protocol-as-implemented)
- [Testing](#testing) · [Code standards](#code-standards)
- [Project layout](#project-layout)
- [Status](#status)
- [License](#license)

---

## Features

- **Retraction for everyone in the room.** Every occupant receives an XEP-0424 retraction from
  the room's bare JID that names the moderator. Conversations needs exactly that shape before it
  deletes the message.
- **The archive is cleaned, not just the screen.** The message's row in Openfire's conversation
  log becomes a *tombstone*. Clients that page through MAM get a "retracted" placeholder in its
  place, and archive search no longer finds the text.
- **New joiners don't see it either.** The same tombstone replaces the message in the room's
  join history.
- **Offline clients catch up.** A phone that had the message and was offline when it was
  moderated gets the retraction when it next joins the room, and removes its copy.
- **The whole message goes, not just its body.** The tombstone keeps only the envelope (sender,
  ids, occupant-id, timestamp). Attachments, HTML bodies, reply quotes and any other extension
  are dropped.
- **Authorization is the room's own.** Only an occupant whose current *role* is moderator may
  moderate. Room owners and admins are moderators by default. There is no separate permission
  list to maintain.
- **Advertised in service discovery**, so clients offer the action only where it works.
- **One switch, nothing else.** No admin page, no table, no schema install.

## Requirements

| | |
|---|---|
| Openfire | **5.0.5 or later**. Built against 5.0.5; every core API used is identical in 5.1.0. |
| Java | 17 to build (Openfire 5 runs on 17+). |
| Room logging | Needed for the archive to be moderated. Rooms without it still get the live retraction and the history rewrite. |
| Monitoring plugin | Needed for MAM, i.e. for clients to fetch the archive at all. Tested with 2.8.0. |

## Build

```bash
mvn package                           # target/messagemoderation.jar
mvn package -Dopenfire.version=5.1.0  # re-check against a newer Openfire
```

Unit tests run as part of the build. The plugin bundles no runtime dependency. Openfire, dom4j
and SLF4J are all `provided`.

## Install

Upload `target/messagemoderation.jar` from **Plugins → Upload Plugin** in the admin console, or
copy it into Openfire's `plugins/` directory. It is active as soon as it loads; there is no
restart and nothing to configure.

For the test server, `deploy.sh` does the build and the copy, then waits for the plugin's
start line in the log so a missed hot-reload doesn't go unnoticed:

```bash
echo 'ssh-password' > .ssh_pass && chmod 600 .ssh_pass
./deploy.sh                # build + deploy
./deploy.sh --no-build     # redeploy the jar already in target/
```

## Using it

In Conversations, long-press a message in a room where you are a moderator and choose
**Moderate**. Other clients that support XEP-0425 v1 (Gajim, Dino, Cheogram and others) offer
an equivalent action.

To make someone a moderator, give them the moderator role, or make them an owner or admin of
the room. A moderator can already kick the author; this lets them also remove what was said.

## Configuration

Two system properties, listed under **Server → Server Manager → System Properties**:

| Property | Default | Meaning |
|---|---|---|
| `plugin.messagemoderation.enabled` | `true` | Takes effect immediately. Switching it off removes `urn:xmpp:message-moderate:1` from service discovery, so clients stop offering the action, and requests are left for core to refuse. |
| `plugin.messagemoderation.replay.days` | `30` | How far back moderations are re-sent to an occupant when they join, counted from when the moderated message was sent. At most 100 per join. `0` switches the replay off. |

## Security

- **Fails closed.** A requester who is not in the room, or whose role is anything but moderator,
  gets `forbidden`. The check uses the role held *at the time of the request*, so a demoted owner
  is refused and a moderator who is not an owner is allowed.
- **Only the room's own ids count.** A message is matched by the stanza-id the *room* stamped on
  it (`by` = room JID). A client can put its own stanza-id on a message, but a forged one cannot
  make that message match an id the room issued. Requests are confined to the room they were
  sent to.
- **Hostile ids never reach SQL as syntax.** An id must match `[A-Za-z0-9._~:@+=/-]{1,128}`, and
  anything else is answered `item-not-found`. The value is bound as a parameter, with `LIKE`
  wildcards escaped, and every candidate row is confirmed by parsing it, not by the text match.
- **XXE-hardened.** Stored stanzas are parsed with DOCTYPE declarations disallowed.
- **Bounded.** Reasons are capped at 1000 characters. Requests are processed one at a time from
  a queue of 64, and a full queue answers `resource-constraint` rather than growing.
- **No content in the log.** A moderation is logged as who, which message id and which room —
  never the moderated text. Client-supplied values that are logged go through `LogSafe`, which
  folds control characters so they cannot forge log lines.

## How it works

### Catching the request

Openfire has no extension point for an IQ addressed to a **room**. `MultiUserChatService.addIQHandler`
handlers only see IQs addressed to the service itself, and core answers an IQ to `room@service` in
an unknown namespace with an error before any plugin code could step in. So the plugin uses a
`PacketInterceptor` on incoming IQs.

It doesn't reject the request. `IQRouter` answers every rejected IQ with an error of its own, so
the moderator would receive that error *and* this plugin's result. Instead the request is
**consumed in place**: rewritten into an IQ *result* to the bare room JID, with a fresh id, which
core's MUC service silently drops. `IQRouter` reads the stanza only after interceptors have run, so
what it routes is the consumed form. The moderator's single answer comes from the plugin.

### Doing the work

Requests are queued to one worker thread rather than handled on the connection's reader thread,
because the message may not be in the database yet. Core writes room messages to
`ofMucConversationLog` in batches through an `Archiver`. A moderator quick to react can name a
message that's still queued, so the worker waits (up to 10 s) for the archiver to catch up to
the moment the request arrived. A single worker also means two moderations in the same room
never interleave.

The steps, in order:

1. **Authorize** — the requester's current role in the room must be moderator.
2. **Find** — in the join history, and (if the room logs) in `ofMucConversationLog`, by the
   room-issued stanza-id. Not found anywhere → `item-not-found`. A subject change → `not-allowed`.
3. **Archive** — rewrite the row: `body = NULL`, `stanza = tombstone`. The row keeps its
   `messageID`, `logTime` and `nickname`, so MAM paging and ordering are unchanged. Monitoring
   builds MAM results from `stanza` and doesn't filter on `body`, so MAM serves the tombstone in
   the original's place.
4. **History** — replace the entry in core's `MUC History` cache, read-modify-write under
   `Cache.getLock(roomJID)`, the same lock core's `HistoryStrategy.addMessage` takes. A message
   arriving at the same moment is neither lost nor duplicated, and on a cluster every node sees
   the rewritten value.
5. **Announce** — broadcast the retraction to the room, and drop the room's cached replay list
   so the next joiner gets this moderation too.
6. **Answer** — an empty `result` to the moderator.

Every step that can fail comes before the announcement. A failure therefore leaves clients still
showing the message: wrong, but visibly so, and the moderator gets an error and can retry. The
alternative order could have clients deleting a message the server still serves to the next
joiner.

### Reaching clients that were offline

The announcement only reaches the occupants in the room at that moment. A phone that already had
the message and was offline isn't one of them, and catching up doesn't help it. Conversations acts
on a retraction **only** when it arrives as a live message: it checks the outer stanza for
`<retract/>`, which a MAM result never is. And when the room has MAM, it joins with
`maxstanzas='0'`, so it gets no join history either. Confirmed on the test server: an offline
Conversations client kept the message after a moderation.

So when anyone joins a room, `ModerationReplay` waits 2 s for the join to complete, then sends that
occupant, live, a retraction for every message moderated in the room in the last
`replay.days` days (most recent 100). The list is built from the tombstones already in the
archive. Each one holds the stanza-id, the `<moderated/>` element and the reason exactly as
announced, so there is no extra table, it survives restarts, and every cluster node agrees. A
client that never had the message ignores the retraction, so sending it to every joiner is
harmless.

Mobile clients rejoin on every reconnect, so the list is cached per room for 60 s and dropped as
soon as the room has a new moderation, and at most 1000 joins wait for a replay at once. A room
with logging off has no archive, and nothing is replayed in it.

### Service discovery

The plugin registers `urn:xmpp:message-moderate:1` with `addExtraFeature` on every MUC service.
Core lists a service's extra features on each of its rooms too, so one call per service covers
every room.

## The protocol, as implemented

**Request** (moderator → room), as Conversations sends it; `<reason>` is optional:

```xml
<iq type='set' to='ops@conference.example.org' id='m1'>
  <moderate xmlns='urn:xmpp:message-moderate:1' id='5f1c2e1a-8f0e-4c55-9b1d-2a7e6c1d0f3b'>
    <retract xmlns='urn:xmpp:message-retract:1'/>
    <reason>spam</reason>
  </moderate>
</iq>
```

**Announcement** (room → every occupant). It deliberately has **no `<body>`**: a fallback body is
only harmless in clients that recognise it as one, and any other client would show it as a new
chat line after every moderation.

```xml
<message type='groupchat' from='ops@conference.example.org' id='…'>
  <retract xmlns='urn:xmpp:message-retract:1' id='5f1c2e1a-8f0e-4c55-9b1d-2a7e6c1d0f3b'>
    <moderated xmlns='urn:xmpp:message-moderate:1' by='ops@conference.example.org/alice'>
      <occupant-id xmlns='urn:xmpp:occupant-id:0' id='…moderator…'/>
    </moderated>
    <reason>spam</reason>
  </retract>
</message>
```

**Tombstone** (what the archive and the join history hold afterwards). Only envelope elements
survive: anything in the `urn:xmpp:sid:0`, `urn:xmpp:occupant-id:0`, `urn:xmpp:delay` and
`muc#user` namespaces.

```xml
<message type='groupchat' from='ops@conference.example.org/bob' id='client-id-1'>
  <stanza-id xmlns='urn:xmpp:sid:0' id='5f1c2e1a-…' by='ops@conference.example.org'/>
  <occupant-id xmlns='urn:xmpp:occupant-id:0' id='…author…'/>
  <delay xmlns='urn:xmpp:delay' stamp='2026-10-04T19:59:00Z'/>
  <retracted xmlns='urn:xmpp:message-retract:1' stamp='2026-10-04T20:00:00Z'>
    <moderated xmlns='urn:xmpp:message-moderate:1' by='ops@conference.example.org/alice'>
      <occupant-id xmlns='urn:xmpp:occupant-id:0' id='…moderator…'/>
    </moderated>
    <reason>spam</reason>
  </retracted>
</message>
```

**Replay** (room → an occupant who just joined), rebuilt from a tombstone. It is the announcement
again, addressed to the joiner, with a fresh id and an XEP-0203 `<delay/>` dated when the
moderation happened:

```xml
<message type='groupchat' from='ops@conference.example.org' to='bob@example.org/phone' id='…'>
  <retract xmlns='urn:xmpp:message-retract:1' id='5f1c2e1a-8f0e-4c55-9b1d-2a7e6c1d0f3b'>
    <moderated xmlns='urn:xmpp:message-moderate:1' by='ops@conference.example.org/alice'>
      <occupant-id xmlns='urn:xmpp:occupant-id:0' id='…moderator…'/>
    </moderated>
    <reason>spam</reason>
  </retract>
  <delay xmlns='urn:xmpp:delay' from='ops@conference.example.org' stamp='2026-10-04T20:00:00Z'/>
</message>
```

**Errors**

| Condition | When |
|---|---|
| `forbidden` | Requester is not in the room, or is not a moderator. |
| `item-not-found` | No such room; no message with that stanza-id in history or archive; or an id the room cannot have issued. |
| `bad-request` | No `id` attribute. |
| `feature-not-implemented` | No `<retract/>` action in the request. |
| `not-allowed` | The id names a subject change. |
| `resource-constraint` | The request queue is full. |
| `internal-server-error` | Anything unexpected; details are in `openfire.log`. |

## Testing

```bash
mvn test                                     # 38 unit tests, no server needed
python3 tools/live_check.py [--insecure]     # end to end, against a real server
```

The unit tests cover the wire contract and the trust boundary: request parsing (including hostile
ids and oversized reasons), the exact shape of the announcement and the tombstone, matching only
room-issued stanza-ids, `LIKE` escaping, XXE refusal, log forging, rebuilding a replay from a
tombstone read back from the archive, and a start that fails part-way leaving nothing registered.

`tools/live_check.py` uses only the Python standard library. It logs in as a moderator and as an
ordinary participant, then checks, against a running Openfire:

1. disco advertises the feature, once;
2. a message gets a room-issued stanza-id and an occupant-id;
3. the participant is refused with `forbidden`;
4. an unknown id is refused with `item-not-found`;
5. the moderator gets a result, and both occupants receive the retraction;
6. MAM returns a tombstone in the message's place, without the text;
7. a later joiner gets the tombstone in the join history.

It reads `.test-accounts` (gitignored) from the project root:

```
server=xmpp.example.org
room=xep0425-test@conference.example.org
moderator_jid=alice@example.org
moderator_password=...
author_jid=bob@example.org
author_password=...
```

If the room doesn't exist, the script creates it with logging on and makes the moderator its
owner.

## Code standards

The code is held to the same standard as its sister plugins, Openfire-federation and
Openfire-SAMessage, re-derived for this codebase in
[docs/java-code-standards.md](docs/java-code-standards.md). That file gives each rule's status
(PASS, GAP or JUSTIFIED) and records the deliberate deviations, so they are not "fixed" later.

## Project layout

```
src/main/java/com/igniterealtime/openfire/plugin/moderation/
  ModerationPlugin.java       lifecycle; disco feature on every MUC service
  ModerationInterceptor.java  picks requests out of the IQ stream and consumes them
  ModerationRequest.java      parses and validates a request (the trust boundary)
  ModerationHandler.java      worker: authorize, find, tombstone, announce, answer
  ModerationReplay.java       re-sends recent moderations to occupants as they join
  Retraction.java             builds the announcement, tombstone and replay (pure, tested)
  ConversationLog.java        finds and rewrites the row in ofMucConversationLog
  RoomHistory.java            rewrites the join-history cache under core's lock
  ModerationProperties.java   the two system properties
  ModerationException.java    an error condition to answer with
  Namespaces.java · LogSafe.java
src/main/resources/           plugin.xml, readme.html, i18n bundle
src/test/java/…               unit tests
docs/java-code-standards.md   the coding standard, and where this code stands against it
tools/live_check.py           end-to-end check against a live server
deploy.sh                     build and deploy to the test server
```

## Status

**0.1.2.** Builds against Openfire 5.0.5 and 5.1.0 and passes its unit tests. 0.1.1 runs on
Openfire 5.1.0; 0.1.2 is **not yet deployed**.

- **0.1.2 is a standards pass** ([docs/java-code-standards.md](docs/java-code-standards.md)). It
  makes no protocol change and fixes two defects. A start that failed part-way left the
  interceptor registered until a restart. A malformed request whose answer failed to send was left
  with no answer at all.

- **Verified with Conversations on 5.1.0:** a moderator moderated a message in a public room; the
  archive row and the join history were both tombstoned, and occupants in the room saw the message
  removed.
- **Found in that test, fixed in 0.1.1:** a Conversations client that was offline at the time kept
  the message. The replay on join is meant to close that gap. It is deployed but **not yet
  confirmed** with an offline device.
- `tools/live_check.py` has not been run yet.

Known limitations:

- **Offline clients are reached on their next join, within the replay window.** A client that is
  offline longer than `replay.days` past the message, or that never rejoins the room, keeps its
  copy. In rooms with logging off there is no replay at all.
- **The retraction is not in MAM.** Core logs the room's announcement with no nickname, and
  Monitoring's MAM query skips such rows. That doesn't matter for Conversations, which ignores
  retractions from MAM anyway, but a client that does read them would rely on the replay instead.
- **Private non-anonymous rooms.** Conversations ignores retractions in members-only,
  non-anonymous rooms, so moderation shows no effect there in that client, whatever the server
  does.
- **New MUC services.** A MUC service created after the plugin loaded doesn't advertise the
  feature until the plugin is reloaded. Requests to it still work.
- **Version 1 only.** The deprecated version 0 (`urn:xmpp:message-moderate:0`, wrapped in
  XEP-0422 Fastening) isn't supported.
- **One internal dependency.** The join-history rewrite goes to core's `MUC History` cache
  directly, because `MUCRoomHistory` offers no way to replace a single entry. The cache name and
  value type are identical in 5.0.5 and 5.1.0. If a later release changes them, the plugin logs a
  warning and everything except the history rewrite still works.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
