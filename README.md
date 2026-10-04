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
- [Testing](#testing)
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

One system property, listed under **Server → Server Manager → System Properties**:

| Property | Default | Meaning |
|---|---|---|
| `plugin.messagemoderation.enabled` | `true` | Takes effect immediately. Switching it off removes `urn:xmpp:message-moderate:1` from service discovery, so clients stop offering the action, and requests are left for core to refuse. |

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
5. **Announce** — broadcast the retraction to the room.
6. **Answer** — an empty `result` to the moderator.

Every step that can fail comes before the announcement. A failure therefore leaves clients still
showing the message: wrong, but visibly so, and the moderator gets an error and can retry. The
alternative order could have clients deleting a message the server still serves to the next
joiner.

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
mvn test                                     # 24 unit tests, no server needed
python3 tools/live_check.py [--insecure]     # end to end, against a real server
```

The unit tests cover the wire contract and the trust boundary: request parsing (including hostile
ids and oversized reasons), the exact shape of the announcement and the tombstone, matching only
room-issued stanza-ids, `LIKE` escaping, and XXE refusal.

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

## Project layout

```
src/main/java/com/igniterealtime/openfire/plugin/moderation/
  ModerationPlugin.java       lifecycle; disco feature on every MUC service
  ModerationInterceptor.java  picks requests out of the IQ stream and consumes them
  ModerationRequest.java      parses and validates a request (the trust boundary)
  ModerationHandler.java      worker: authorize, find, tombstone, announce, answer
  Retraction.java             builds the announcement and the tombstone (pure, tested)
  ConversationLog.java        finds and rewrites the row in ofMucConversationLog
  RoomHistory.java            rewrites the join-history cache under core's lock
  ModerationProperties.java   the one system property
  ModerationException.java    an error condition to answer with
  Namespaces.java · LogSafe.java
src/main/resources/           plugin.xml, readme.html, i18n bundle
src/test/java/…               unit tests
tools/live_check.py           end-to-end check against a live server
deploy.sh                     build and deploy to the test server
```

## Status

**0.1.0.** Builds against Openfire 5.0.5 and 5.1.0, passes its unit tests, and loads cleanly on
Openfire 5.1.0. **Not yet verified end to end:** `tools/live_check.py` has not been run against a
live server, and moderation has not been tried from Conversations itself.

Known limitations:

- **Offline clients may keep a copy.** A client that downloaded the message and was offline when
  it was moderated learns of the moderation only if it fetches the retraction from MAM. Core logs
  the room's announcement with no nickname, and both history reload and Monitoring's MAM query
  skip such rows, so the retraction is probably invisible to a catching-up client. This is
  suspected from the code and still to be confirmed live. New joiners and MAM readers are not
  affected: they get the tombstone.
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
