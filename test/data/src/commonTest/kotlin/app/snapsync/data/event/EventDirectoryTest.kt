package app.snapsync.data.event

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.MetaCipher
import app.snapsync.data.crypto.MetaOpening
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.EventLookupFact
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.service.ServiceFault
import app.snapsync.format.Hmac
import app.snapsync.format.SealedMetaFormat
import app.snapsync.format.TimestampText
import app.snapsync.mock.fakeCrypto
import app.snapsync.ports.AesKey
import app.snapsync.ports.GcmNonce
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testEventId
import app.snapsync.wire.models.EventChange
import app.snapsync.wire.models.EventDetails
import app.snapsync.wire.runtime.WireJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import app.snapsync.wire.models.Event as WireEvent
import app.snapsync.wire.models.MemberCounts as WireCounts

private const val ID = "0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60"

/** The server's word about an event, and its rename: only "not found" is a witness, and nothing is invented. */
class EventDirectoryTest {

    private val transport = ScriptedTransport()
    private val gateway = BackendGateway(exchangeOver(transport), ScriptedCredential("T1"))
    private val cipher = MetaCipher(fakeCrypto())
    private val directory = EventDirectory(gateway, cipher)

    /** The plain read: no key, no name known from before. */
    private suspend fun look() = directory.lookup(testEventId(ID), key = null, lastKnown = null)

    private fun details(
        name: String? = "Lake weekend",
        sealedMeta: String? = null,
        startsAt: String = "2026-07-14T18:00:00Z",
        openUntil: String = "2026-07-28T18:00:00Z",
        completedAt: String? = null,
        keyId: String? = "k1",
    ) = EventDetails(
        eventId = ID,
        name = name,
        sealedMeta = sealedMeta,
        createdAt = "2026-07-01T10:00:00Z",
        startsAt = startsAt,
        endsAt = "2026-07-21T18:00:00Z",
        openUntil = openUntil,
        deletesAt = "2026-08-21T18:00:00Z",
        closedAt = null,
        completedAt = completedAt,
        keyId = keyId,
        members = WireCounts(active = 3, `final` = 1, waiting = 2),
    )

    private val maintenance = json(503, """{"error":"maintenance"}""", mapOf("Retry-After" to " 60 "))

    private fun answering(result: HttpResult) {
        transport.answer = { result }
    }

    private fun found(details: EventDetails) = json(200, WireJson.encodeToString(EventDetails.serializer(), details))

    @Test
    fun `a found event carries every fact a membership keeps`() = runTest {
        answering(found(details()))
        val found = assertIs<EventLookupFact.Found>(look())
        assertEquals(false, found.completed)
        with(found.event) {
            assertEquals(testEventId(ID), id)
            assertEquals("Lake weekend", name.text)
            assertEquals(TimestampText.parse("2026-07-14T18:00:00Z"), range.start.at)
            assertEquals(TimestampText.parse("2026-07-21T18:00:00Z"), range.end.at)
            assertEquals(OpenUntil(TimestampText.parse("2026-07-28T18:00:00Z")), openUntil)
            assertEquals(TimestampText.parse("2026-08-21T18:00:00Z"), deletesAt.at)
            assertEquals(Encryption.Encrypted(EventKeyId("k1")), encryption)
            assertEquals(MemberCounts(3, 1, 2), members)
        }
    }

    @Test
    fun `the server's word that an event completed rides with it`() = runTest {
        answering(found(details(completedAt = "2026-08-01T00:00:00Z", keyId = null)))
        val found = assertIs<EventLookupFact.Found>(look())
        assertEquals(true, found.completed)
        assertEquals(Encryption.Plain, found.event.encryption)
    }

    @Test
    fun `an unreadable open-until date costs only that date`() = runTest {
        answering(found(details(openUntil = "soon")))
        assertEquals(null, assertIs<EventLookupFact.Found>(look()).event.openUntil)
    }

    @Test
    fun `not found is the one witness, and every other answer is none, told by how the service failed`() = runTest {
        answering(ok(404))
        assertEquals(EventLookupFact.NotFound, look())
        for ((answer, fault) in listOf(
            text(502, "down") to ServiceFault.TROUBLE,
            text(400, "bad") to ServiceFault.UNREACHABLE,
            HttpResult.Unreachable("offline") to ServiceFault.UNREACHABLE,
            json(200, "<html/>") to ServiceFault.UNREACHABLE,
        )) {
            answering(answer)
            assertEquals(EventLookupFact.Unanswered(fault), look(), "$answer")
        }
    }

    @Test
    fun `a service being updated says when asking again is worth it`() = runTest {
        answering(maintenance)
        assertEquals(EventLookupFact.Unanswered(ServiceFault.UPDATING, 60), look())
        answering(json(503, """{"error":"maintenance"}""", mapOf("Retry-After" to "soon")))
        assertEquals(EventLookupFact.Unanswered(ServiceFault.UPDATING), look())
    }

    @Test
    fun `a found event this device cannot read is no answer, never one with an invented fact`() = runTest {
        // An answer the app does not understand is told as the service not reached.
        val unreadable = EventLookupFact.Unanswered(ServiceFault.UNREACHABLE)
        for (bad in listOf(
            details(name = "  "),
            details(startsAt = "someday"),
            details(startsAt = "2026-07-22T00:00:00Z"),
        )) {
            answering(found(bad))
            assertEquals(unreadable, look(), "$bad")
        }
        answering(found(details().copy(endsAt = "never")))
        assertEquals(unreadable, look())
        answering(found(details().copy(deletesAt = "never")))
        assertEquals(unreadable, look())
    }

    // ---- the rename ----

    private val rename = EventRename(gateway, cipher)

    private suspend fun renamedPlain(name: String) =
        rename.rename(testEventId(ID), app.snapsync.data.membership.name(name))

    private fun changed(name: String?, sealedMeta: String? = null, keyId: String? = null) = json(
        200,
        WireJson.encodeToString(
            WireEvent.serializer(),
            WireEvent(
                eventId = ID,
                name = name,
                sealedMeta = sealedMeta,
                keyId = keyId,
                createdAt = "2026-07-01T10:00:00Z",
                startsAt = "2026-07-14T18:00:00Z",
                endsAt = "2026-07-21T18:00:00Z",
                openUntil = "2026-07-21T18:00:00Z",
                deletesAt = "2026-08-21T18:00:00Z",
                closedAt = null,
                completedAt = null,
            ),
        ),
    )

    @Test
    fun `a rename keeps the name the server stored`() = runTest {
        answering(changed("Lake week"))
        assertEquals(
            RenameFact.Renamed(app.snapsync.data.membership.name("Lake week")),
            rename.rename(testEventId(ID), app.snapsync.data.membership.name(" Lake week ")),
        )
        val sent = WireJson.decodeFromString(EventChange.serializer(), transport.requests.last().body!!)
        assertEquals("Lake week", sent.name, "the name is sent as the domain holds it, trimmed")
    }

    @Test
    fun `a refused name is refused, the photos deleted is the event over, and every other answer is none`() = runTest {
        answering(text(400, "bad name"))
        assertEquals(RenameFact.Refused, renamedPlain("x"))
        answering(json(410, """{"error":"closed"}"""))
        assertEquals(RenameFact.EventOver, renamedPlain("x"))
        for ((answer, fault) in listOf(
            // A lone "not found" is an answer from a working service, told generically — and no witness of a deletion.
            text(404, "event not found") to null,
            text(502, "down") to ServiceFault.TROUBLE,
            maintenance to ServiceFault.UPDATING,
            HttpResult.Unreachable("offline") to ServiceFault.UNREACHABLE,
            changed("  ") to ServiceFault.UNREACHABLE,
            changed("x".repeat(101)) to ServiceFault.UNREACHABLE,
        )) {
            answering(answer)
            assertEquals(
                RenameFact.Unanswered(fault),
                rename.rename(testEventId(ID), app.snapsync.data.membership.name("x")),
                "$answer",
            )
        }
    }

    // ---- a sealed name (decision record `changes/seal-event-name`) ----

    private val key = keyOf(7)
    private val otherKey = keyOf(9)

    @OptIn(MakesEventKey::class)
    private fun keyOf(fill: Int) = EventKeyIds(fakeCrypto()).identify(EventKey.of(ByteArray(32) { fill.toByte() })!!)

    private fun sealed(name: String, extra: String = "") =
        cipher.seal(key, SealedMetaFormat.recordOf("""{"name":"$name"$extra}""".encodeToByteArray())!!)

    private fun sealedDetails(record: String = sealed("Ana's 30th"), completedAt: String? = null) =
        details(name = null, sealedMeta = record, keyId = key.id.value, completedAt = completedAt)

    @Test
    fun `a sealed name opens with the event's key`() = runTest {
        answering(found(sealedDetails()))
        val found = assertIs<EventLookupFact.Found>(directory.lookup(testEventId(ID), key, lastKnown = null))
        assertEquals("Ana's 30th", found.event.name.text)
        assertEquals(Encryption.Encrypted(key.id), found.event.encryption)
    }

    @Test
    fun `without the event's key a sealed name is the one last known, or withheld`() = runTest {
        answering(found(sealedDetails()))
        val known = app.snapsync.data.membership.name("Ana's 3oth")
        for (held in listOf(null, otherKey)) {
            val found = assertIs<EventLookupFact.Found>(directory.lookup(testEventId(ID), held, lastKnown = known))
            assertEquals(known, found.event.name, "a key of another id is no key for this event")
            assertEquals(
                EventLookupFact.Withheld(Encryption.Encrypted(key.id), completed = false),
                directory.lookup(testEventId(ID), held, lastKnown = null),
            )
        }
        answering(found(sealedDetails(completedAt = "2026-08-01T00:00:00Z")))
        assertEquals(
            EventLookupFact.Withheld(Encryption.Encrypted(key.id), completed = true),
            directory.lookup(testEventId(ID), key = null, lastKnown = null),
            "the server's word that its photos are deleted rides a withheld name too",
        )
    }

    @Test
    fun `a sealed name the event's own key does not open is no answer, whatever was known`() = runTest {
        val foreign = MetaCipher(fakeCrypto()).seal(otherKey, SealedMetaFormat.recordNamed("Not this event"))
        for (bad in listOf(foreign, sealed("   "), "AQ" + "A".repeat(40))) {
            answering(found(sealedDetails(record = bad)))
            assertEquals(
                EventLookupFact.Unanswered(ServiceFault.UNREACHABLE),
                directory.lookup(testEventId(ID), key, lastKnown = app.snapsync.data.membership.name("Known")),
                bad,
            )
        }
    }

    @Test
    fun `a sealed rename re-seals the record the server holds, keeping every key this build does not know`() =
        runTest {
            var stored = sealed("Ana's 3oth", extra = ""","future":{"kept":true}""")
            transport.answer = { request ->
                when (request.method) {
                    "GET" -> found(sealedDetails(record = stored))
                    else -> {
                        stored = WireJson.decodeFromString(EventChange.serializer(), request.body!!).sealedMeta!!
                        changed(name = null, sealedMeta = stored, keyId = key.id.value)
                    }
                }
            }
            assertEquals(
                RenameFact.Renamed(app.snapsync.data.membership.name("Ana's 30th")),
                rename.renameSealed(testEventId(ID), app.snapsync.data.membership.name("Ana's 30th"), key),
            )
            val sent = WireJson.decodeFromString(EventChange.serializer(), transport.requests.last().body!!)
            assertEquals(null, sent.name, "an encrypted event's new name never goes readable")
            val opened = assertIs<MetaOpening.Opened>(cipher.open(key, sent.sealedMeta!!)).record
            assertEquals(
                Json.parseToJsonElement("""{"name":"Ana's 30th","future":{"kept":true}}"""),
                Json.parseToJsonElement(SealedMetaFormat.plaintextOf(opened).decodeToString()),
            )
        }

    @Test
    fun `renaming a legacy encrypted event gives it its first sealed record`() = runTest {
        transport.answer = { request ->
            when (request.method) {
                "GET" -> found(details(name = "Legacy", keyId = key.id.value))
                else -> changed(
                    name = null,
                    sealedMeta = WireJson.decodeFromString(EventChange.serializer(), request.body!!).sealedMeta,
                    keyId = key.id.value,
                )
            }
        }
        assertEquals(
            RenameFact.Renamed(app.snapsync.data.membership.name("Legacy 2")),
            rename.renameSealed(testEventId(ID), app.snapsync.data.membership.name("Legacy 2"), key),
        )
    }

    @Test
    fun `a sealed rename whose current record does not open sends nothing`() = runTest {
        val foreign = MetaCipher(fakeCrypto()).seal(otherKey, SealedMetaFormat.recordNamed("Not this event"))
        answering(found(sealedDetails(record = foreign)))
        assertEquals(
            RenameFact.Unanswered(ServiceFault.UNREACHABLE),
            rename.renameSealed(testEventId(ID), app.snapsync.data.membership.name("x"), key),
        )
        assertEquals(listOf("GET"), transport.requests.map { it.method })
    }

    @Test
    fun `a sealed rename that cannot read the current record sends nothing, told by how the service failed`() =
        runTest {
            for ((answer, fault) in listOf(
                text(404, "event not found") to null,
                maintenance to ServiceFault.UPDATING,
                text(502, "down") to ServiceFault.TROUBLE,
                HttpResult.Unreachable("offline") to ServiceFault.UNREACHABLE,
            )) {
                transport.requests.clear()
                answering(answer)
                assertEquals(
                    RenameFact.Unanswered(fault),
                    rename.renameSealed(testEventId(ID), app.snapsync.data.membership.name("x"), key),
                    "$answer",
                )
                assertEquals(listOf("GET"), transport.requests.map { it.method })
            }
        }

    @Test
    fun `a sealed name that is not the format at all is no answer`() = runTest {
        answering(found(sealedDetails(record = "not an envelope!")))
        assertEquals(
            EventLookupFact.Unanswered(ServiceFault.UNREACHABLE),
            directory.lookup(testEventId(ID), key, lastKnown = null),
        )
    }

    @Test
    fun `a served event with neither a name nor a record is no answer, and a plain one's record is withheld`() =
        runTest {
            answering(found(details(name = null, sealedMeta = null, keyId = key.id.value)))
            assertEquals(EventLookupFact.Unanswered(ServiceFault.UNREACHABLE), look())
            // A plain event holds no record; one that did would open under no key.
            answering(found(details(name = null, sealedMeta = sealed("x"), keyId = null)))
            assertEquals(EventLookupFact.Withheld(Encryption.Plain, completed = false), look())
        }

    @Test
    fun `a sealed rename refused or unread on its first read, or of a row with no name at all`() = runTest {
        answering(text(400, "bad"))
        assertEquals(
            RenameFact.Unanswered(fault = null),
            rename.renameSealed(testEventId(ID), app.snapsync.data.membership.name("x"), key),
        )
        transport.answer = { request ->
            when (request.method) {
                "GET" -> found(details(name = null, sealedMeta = null, keyId = key.id.value))
                else -> text(400, "invalid name")
            }
        }
        assertEquals(
            RenameFact.Refused,
            rename.renameSealed(testEventId(ID), app.snapsync.data.membership.name("x"), key),
        )
    }

    @Test
    fun `a renamed event's echo with no readable name and no key is an answer the app does not understand`() =
        runTest {
            answering(changed(name = null, sealedMeta = sealed("x"), keyId = key.id.value))
            assertEquals(RenameFact.Unanswered(ServiceFault.UNREACHABLE), renamedPlain("x"))
        }

    @OptIn(KeyMaterial::class)
    @Test
    fun `a record that opens to no record with a name is damaged`() {
        val crypto = fakeCrypto()
        val hmac = object : Hmac {
            override fun mac(key: ByteArray, message: ByteArray): ByteArray = crypto.hmacSha256(key, message)
        }
        val nonce = ByteArray(SealedMetaFormat.NONCE_LENGTH)
        val sealed = crypto.aesGcmSeal(
            AesKey(SealedMetaFormat.metaKeyOf(key.key.bytes(), hmac)),
            GcmNonce(nonce),
            "[]".encodeToByteArray(),
        )
        assertIs<MetaOpening.Damaged>(MetaCipher(crypto).open(key, SealedMetaFormat.encode(nonce, sealed)))
    }
}
