@file:OptIn(ShowsInvite::class)

package app.snapsync.journeys

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.format.EnvelopeRead
import app.snapsync.format.Hmac
import app.snapsync.format.InviteLinkText
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.SealedMetaFormat
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ShowsInvite
import app.snapsync.ui.state.UiState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// A photo's name as an encrypted event's members read it (decision record `changes/seal-file-names`): the service
// holds it only sealed for that one photo, so a journey reading the union or the request log opens it with the key the
// joined screen's invite carries — as the event page and every member do — and a plain event's names read as served.

/** The key the joined screen's invite carries — `null` for a plain event, or off the joined screen. */
internal fun inviteKey(ui: UiState): EventKey? {
    val invite = (ui.layer as? Layer.Joined)?.inviteUrl?.raw ?: return null
    return (InviteLinkText.parse(invite) as? InviteLinkVerdict.Invite)?.link?.key
}

/** The (role, name) pairs a union [asset] of [event] is made of, each name read with [key]. */
internal fun namesOf(key: EventKey?, event: String, asset: JsonObject): Set<Pair<String, String?>> {
    val device = asset.getValue("deviceId").jsonPrimitive.content
    val id = asset.getValue("assetId").jsonPrimitive.content
    return asset["resources"]?.jsonArray.orEmpty().mapTo(mutableSetOf()) {
        val resource = it.jsonObject
        val role = resource.getValue("role").jsonPrimitive.content
        role to nameServed(key, event, device, id, role, resource.getValue("filename").jsonPrimitive.content)
    }
}

/**
 * The name an upload to [url] carries — `/events/<event>/files/devices/<device>/<asset>/<role>?filename=<name>` — read
 * with [key]; `null` for any other URL, or a sealed name the key does not open.
 */
internal fun nameUploaded(key: EventKey?, url: String): String? {
    val parts = UPLOAD.find(url)?.groupValues ?: return null
    val served = URLDecoder.decode(parts[SERVED], Charsets.UTF_8)
    return nameServed(key, parts[EVENT], parts[DEVICE], parts[ASSET], parts[ROLE], served)
}

/**
 * [served], the name [device]'s [asset] as its [role] is served under in [event]: opened with [key] for that one photo
 * when the event is encrypted, as served when it is plain — `null` for a sealed name the key does not open.
 */
@OptIn(KeyMaterial::class)
internal fun nameServed(
    key: EventKey?,
    event: String,
    device: String,
    asset: String,
    role: String,
    served: String,
): String? {
    val bytes = key?.bytes() ?: return served
    val read = SealedMetaFormat.decode(served) as? EnvelopeRead.Read ?: return null
    val binding = FileBinding(
        EventId.parse(event)!!,
        DeviceId.parse(device)!!,
        AssetId.parse(asset)!!,
        ResourceRoleText.parse(role)!!,
    )
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(SealedMetaFormat.fileNameKeyOf(bytes, binding, JdkHmac), "AES"),
        GCMParameterSpec(SealedMetaFormat.TAG_LENGTH * Byte.SIZE_BITS, read.nonce),
    )
    val plaintext = runCatching { cipher.doFinal(read.sealed) }.getOrNull() ?: return null
    return SealedMetaFormat.recordOf(plaintext)?.name
}

private object JdkHmac : Hmac {
    override fun mac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
}

private val UPLOAD = Regex("/events/([^/]+)/files/devices/([^/]+)/([^/]+)/([^/?]+)\\?(?:.*&)?filename=([^&]+)")

// The groups of an [UPLOAD] match.
private const val EVENT = 1
private const val DEVICE = 2
private const val ASSET = 3
private const val ROLE = 4
private const val SERVED = 5
