#!/usr/bin/env python3
"""
live_check.py - end-to-end check of the Message Moderation plugin against a real Openfire.

Plays the two people a moderation involves, over plain XMPP (stdlib only, no slixmpp):

  moderator  an occupant with the moderator role (the room owner, by default)
  author     an ordinary participant whose message gets moderated

and checks every effect XEP-0425 promises, in the order a user would notice them:

  1. the room advertises urn:xmpp:message-moderate:1 (and occupant-id) in disco#info
  2. the author's message reaches the moderator with a room-issued stanza-id
  3. the author, a mere participant, is refused (forbidden)
  4. an id the room never issued is refused (item-not-found)
  5. the moderator's request gets a result, and BOTH occupants receive the retraction from the
     room's bare JID, naming the moderator - the exact shape Conversations acts on
  6. MAM returns a tombstone in the message's place, without the text
  7. a client joining afterwards gets a tombstone in the join history, without the text

Credentials come from .test-accounts in the project root (gitignored), one key=value per line:

  server=2501-xmpp.core.net        # host to connect to (port 5222, STARTTLS)
  room=xep0425-test@conference.example.org
  moderator_jid=alice@example.org
  moderator_password=...
  author_jid=bob@example.org
  author_password=...

The room is created if it does not exist, with logging enabled (needed for the MAM check), and
the moderator becomes its owner - which makes them a moderator. Run it twice in a row: the second
run uses the existing room, so the moderator must still be its owner or admin.

Usage: python3 tools/live_check.py [--accounts PATH] [--insecure]
  --insecure  skip TLS certificate verification (a test server with a self-signed cert)
"""

import argparse
import base64
import socket
import ssl
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

NS_CLIENT = "jabber:client"
NS_STREAM = "http://etherx.jabber.org/streams"
NS_TLS = "urn:ietf:params:xml:ns:xmpp-tls"
NS_SASL = "urn:ietf:params:xml:ns:xmpp-sasl"
NS_BIND = "urn:ietf:params:xml:ns:xmpp-bind"
NS_MUC = "http://jabber.org/protocol/muc"
NS_MUC_USER = "http://jabber.org/protocol/muc#user"
NS_MUC_OWNER = "http://jabber.org/protocol/muc#owner"
NS_DATA = "jabber:x:data"
NS_DISCO = "http://jabber.org/protocol/disco#info"
NS_SID = "urn:xmpp:sid:0"
NS_MAM = "urn:xmpp:mam:2"
NS_FORWARD = "urn:xmpp:forward:0"
NS_RSM = "http://jabber.org/protocol/rsm"
NS_MODERATE = "urn:xmpp:message-moderate:1"
NS_RETRACT = "urn:xmpp:message-retract:1"
NS_OCCUPANT_ID = "urn:xmpp:occupant-id:0"
NS_STANZAS = "urn:ietf:params:xml:ns:xmpp-stanzas"


def q(ns, name):
    return "{%s}%s" % (ns, name)


class Client:
    """Just enough of an XMPP client: STARTTLS, SASL PLAIN, bind, and a stanza queue."""

    def __init__(self, server, jid, password, insecure, label):
        self.label = label
        self.jid = jid
        self.domain = jid.split("@", 1)[1]
        self.sock = socket.create_connection((server, 5222), timeout=15)
        self.server = server
        self.insecure = insecure
        self.pending = []
        self._open_stream()
        self._starttls()
        self._auth(jid.split("@", 1)[0], password)
        self.full_jid = self._bind()

    # -- wire ---------------------------------------------------------------------------------

    def send(self, xml):
        self.sock.sendall(xml.encode("utf-8"))

    def _open_stream(self):
        self.parser = ET.XMLPullParser(events=("start", "end"))
        self.depth = 0
        self.send("<?xml version='1.0'?><stream:stream to=%s xmlns='jabber:client' "
                  "xmlns:stream='http://etherx.jabber.org/streams' version='1.0'>"
                  % quoteattr(self.domain))

    def _pump(self, timeout):
        """Reads whatever is available; queues every complete top-level element."""
        self.sock.settimeout(timeout)
        try:
            data = self.sock.recv(65536)
        except (socket.timeout, ssl.SSLWantReadError):
            return
        if not data:
            raise ConnectionError("%s: server closed the stream" % self.label)
        self.parser.feed(data)
        for event, elem in self.parser.read_events():
            if event == "start":
                self.depth += 1
            else:
                self.depth -= 1
                if self.depth == 1:
                    self.pending.append(elem)

    def wait_for(self, predicate, timeout=10, what="stanza"):
        deadline = time.time() + timeout
        while True:
            for i, elem in enumerate(self.pending):
                if predicate(elem):
                    return self.pending.pop(i)
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError("%s: no %s within %ss" % (self.label, what, timeout))
            self._pump(min(remaining, 1.0))

    def drain(self, seconds):
        """Collects everything that arrives for a while, e.g. a broadcast that may or may not come."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            self._pump(0.3)
        got, self.pending = self.pending, []
        return got

    def iq(self, type_, to, payload, timeout=20):
        iq_id = uuid.uuid4().hex[:12]
        self.send("<iq type='%s' id='%s'%s>%s</iq>"
                  % (type_, iq_id, " to=%s" % quoteattr(to) if to else "", payload))
        return self.wait_for(lambda e: e.tag == q(NS_CLIENT, "iq") and e.get("id") == iq_id,
                             timeout, "reply to iq " + iq_id)

    # -- session ------------------------------------------------------------------------------

    def _features(self):
        return self.wait_for(lambda e: e.tag == q(NS_STREAM, "features"), what="stream features")

    def _starttls(self):
        self._features()
        self.send("<starttls xmlns='%s'/>" % NS_TLS)
        self.wait_for(lambda e: e.tag == q(NS_TLS, "proceed"), what="TLS proceed")
        ctx = ssl.create_default_context()
        if self.insecure:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        self.sock = ctx.wrap_socket(self.sock, server_hostname=self.server)
        self._open_stream()

    def _auth(self, user, password):
        self._features()
        token = base64.b64encode(("\0%s\0%s" % (user, password)).encode()).decode()
        self.send("<auth xmlns='%s' mechanism='PLAIN'>%s</auth>" % (NS_SASL, token))
        reply = self.wait_for(lambda e: e.tag in (q(NS_SASL, "success"), q(NS_SASL, "failure")),
                              what="SASL outcome")
        if reply.tag != q(NS_SASL, "success"):
            raise PermissionError("%s: authentication failed for %s" % (self.label, self.jid))
        self._open_stream()

    def _bind(self):
        self._features()
        reply = self.iq("set", None, "<bind xmlns='%s'><resource>live-check-%s</resource></bind>"
                        % (NS_BIND, uuid.uuid4().hex[:6]))
        return reply.find("%s/%s" % (q(NS_BIND, "bind"), q(NS_BIND, "jid"))).text

    def close(self):
        try:
            self.send("<presence type='unavailable'/></stream:stream>")
            self.sock.close()
        except OSError:
            pass

    # -- MUC ----------------------------------------------------------------------------------

    def join(self, room, nick, history=0):
        self.send("<presence to=%s><x xmlns='%s'><history maxstanzas='%d'/></x></presence>"
                  % (quoteattr("%s/%s" % (room, nick)), NS_MUC, history))
        own = self.wait_for(
            lambda e: e.tag == q(NS_CLIENT, "presence") and e.get("from") == "%s/%s" % (room, nick)
            and any(s.get("code") == "110" for s in e.iter(q(NS_MUC_USER, "status")))
            or (e.tag == q(NS_CLIENT, "presence") and e.get("type") == "error"),
            what="self-presence in " + room)
        if own.get("type") == "error":
            raise RuntimeError("%s: could not join %s: %s" % (self.label, room, ET.tostring(own)))
        return any(s.get("code") == "201" for s in own.iter(q(NS_MUC_USER, "status")))

    def configure_new_room(self, room):
        """Unlocks a just-created room, with logging on so the archive can be checked."""
        form = ("<query xmlns='%s'><x xmlns='%s' type='submit'>"
                "<field var='FORM_TYPE'><value>http://jabber.org/protocol/muc#roomconfig</value></field>"
                "<field var='muc#roomconfig_enablelogging'><value>1</value></field>"
                "<field var='muc#roomconfig_publicroom'><value>1</value></field>"
                "<field var='muc#roomconfig_persistentroom'><value>1</value></field>"
                "</x></query>") % (NS_MUC_OWNER, NS_DATA)
        reply = self.iq("set", room, form)
        if reply.get("type") != "result":
            raise RuntimeError("%s: room configuration refused: %s" % (self.label, ET.tostring(reply)))

    def leave(self, room, nick):
        self.send("<presence type='unavailable' to=%s/>" % quoteattr("%s/%s" % (room, nick)))


# -- the checks ---------------------------------------------------------------------------------

class Report:
    def __init__(self):
        self.failures = 0

    def check(self, ok, what, detail=""):
        print("  %s  %s%s" % ("PASS" if ok else "FAIL", what, ("  -- " + detail) if detail and not ok else ""))
        if not ok:
            self.failures += 1
        return ok


def error_condition(iq):
    error = iq.find(q(NS_CLIENT, "error"))
    if error is None:
        return None
    for child in error:
        if child.tag.startswith("{%s}" % NS_STANZAS) and not child.tag.endswith("}text"):
            return child.tag.split("}", 1)[1]
    return "?"


def moderate(client, room, stanza_id):
    return client.iq("set", room,
                     "<moderate xmlns='%s' id=%s><retract xmlns='%s'/><reason>live check</reason></moderate>"
                     % (NS_MODERATE, quoteattr(stanza_id), NS_RETRACT))


def stanza_id_of(message, room):
    for sid in message.iter(q(NS_SID, "stanza-id")):
        if sid.get("by") == room:
            return sid.get("id")
    return None


def is_retraction(elem, room, stanza_id):
    if elem.tag != q(NS_CLIENT, "message") or elem.get("type") != "groupchat" or elem.get("from") != room:
        return False
    retract = elem.find(q(NS_RETRACT, "retract"))
    return retract is not None and retract.get("id") == stanza_id


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--accounts", default=str(Path(__file__).resolve().parent.parent / ".test-accounts"))
    ap.add_argument("--insecure", action="store_true")
    args = ap.parse_args()

    cfg = {}
    for line in Path(args.accounts).read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if "=" in line:
            k, v = line.split("=", 1)
            cfg[k.strip()] = v.strip()
    room = cfg["room"]
    report = Report()
    text = "moderate me %s" % uuid.uuid4().hex[:8]

    mod = Client(cfg["server"], cfg["moderator_jid"], cfg["moderator_password"], args.insecure, "moderator")
    author = Client(cfg["server"], cfg["author_jid"], cfg["author_password"], args.insecure, "author")
    late = None
    try:
        print("Connected: %s, %s" % (mod.full_jid, author.full_jid))
        if mod.join(room, "mod"):
            print("  (room did not exist: created and configured with logging on)")
            mod.configure_new_room(room)
        author.join(room, "author")
        mod.drain(1)
        author.drain(1)

        print("1. Service discovery")
        info = mod.iq("get", room, "<query xmlns='%s'/>" % NS_DISCO)
        features = {f.get("var") for f in info.iter(q(NS_DISCO, "feature"))}
        report.check(NS_MODERATE in features, "room advertises " + NS_MODERATE, sorted(features))
        report.check(NS_OCCUPANT_ID in features, "room advertises " + NS_OCCUPANT_ID)
        report.check(sum(1 for f in info.iter(q(NS_DISCO, "feature")) if f.get("var") == NS_MODERATE) == 1,
                     "feature is listed once, not duplicated")

        print("2. The author's message")
        author.send("<message type='groupchat' to=%s id='%s'><body>%s</body></message>"
                    % (quoteattr(room), uuid.uuid4().hex[:8], escape(text)))
        seen = mod.wait_for(lambda e: e.tag == q(NS_CLIENT, "message")
                            and e.findtext(q(NS_CLIENT, "body")) == text, what="the author's message")
        stanza_id = stanza_id_of(seen, room)
        report.check(stanza_id is not None, "message carries a stanza-id issued by the room")
        report.check(seen.find(q(NS_OCCUPANT_ID, "occupant-id")) is not None, "message carries an occupant-id")
        author.drain(1)
        if stanza_id is None:
            return 1

        print("3. A participant may not moderate")
        refused = moderate(author, room, stanza_id)
        report.check(refused.get("type") == "error" and error_condition(refused) == "forbidden",
                     "author's request is refused with forbidden", ET.tostring(refused).decode())
        report.check(not any(is_retraction(e, room, stanza_id) for e in mod.drain(1.5)),
                     "and nothing is retracted")

        print("4. An id the room never issued")
        missing = moderate(mod, room, str(uuid.uuid4()))
        report.check(missing.get("type") == "error" and error_condition(missing) == "item-not-found",
                     "refused with item-not-found", ET.tostring(missing).decode())

        print("5. The moderator moderates")
        started = time.time()
        result = moderate(mod, room, stanza_id)
        report.check(result.get("type") == "result", "request gets a result (%.1fs)" % (time.time() - started),
                     ET.tostring(result).decode())
        for client in (mod, author):
            got = client.wait_for(lambda e: is_retraction(e, room, stanza_id), 10,
                                  "retraction broadcast") if result.get("type") == "result" else None
            ok = got is not None
            report.check(ok, "%s receives the retraction from the bare room JID" % client.label)
            if ok:
                moderated = got.find("%s/%s" % (q(NS_RETRACT, "retract"), q(NS_MODERATE, "moderated")))
                report.check(moderated is not None and moderated.get("by") == "%s/mod" % room,
                             "%s sees who moderated" % client.label, ET.tostring(got).decode())
                report.check(got.find(q(NS_CLIENT, "body")) is None, "%s gets no fallback chat line" % client.label)

        print("6. The archive (MAM)")
        mam = "<query xmlns='%s' queryid='lc'><set xmlns='%s'><max>50</max><before/></set></query>" % (NS_MAM, NS_RSM)
        mod.send("<iq type='set' id='mam-lc' to=%s>%s</iq>" % (quoteattr(room), mam))
        mod.wait_for(lambda e: e.tag == q(NS_CLIENT, "iq") and e.get("id") == "mam-lc", 20, "MAM fin")
        results = [e for e in mod.drain(1) if e.find(q(NS_MAM, "result")) is not None]
        entry = next((r for r in results if r.find(q(NS_MAM, "result")).get("id") == stanza_id), None)
        if report.check(entry is not None, "MAM still has an entry under the moderated stanza-id",
                        "%d results, none with id %s" % (len(results), stanza_id)):
            archived = entry.find(".//" + q(NS_FORWARD, "forwarded") + "/" + q(NS_CLIENT, "message"))
            report.check(archived is not None and archived.find(q(NS_RETRACT, "retracted")) is not None,
                         "the archived entry is a tombstone", ET.tostring(entry).decode())
            report.check(text not in ET.tostring(entry).decode(), "the text is gone from the archive")

        print("7. Join history for someone arriving later")
        author.leave(room, "author")
        time.sleep(1)
        late = Client(cfg["server"], cfg["author_jid"], cfg["author_password"], args.insecure, "late joiner")
        late.send("<presence to=%s><x xmlns='%s'><history maxstanzas='50'/></x></presence>"
                  % (quoteattr("%s/late" % room), NS_MUC))
        history = late.drain(4)
        raw = b"".join(ET.tostring(e) for e in history).decode()
        report.check(text not in raw, "the text is not in the join history")
        report.check(any(stanza_id_of(e, room) == stanza_id and e.find(q(NS_RETRACT, "retracted")) is not None
                         for e in history), "the history holds the tombstone in its place")
    finally:
        for client in (mod, author, late):
            if client:
                client.close()

    print("\n%s" % ("ALL CHECKS PASSED" if report.failures == 0 else "%d CHECK(S) FAILED" % report.failures))
    return 0 if report.failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
