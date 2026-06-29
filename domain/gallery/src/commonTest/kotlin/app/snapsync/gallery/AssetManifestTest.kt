package app.snapsync.gallery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AssetManifestTest {

    private fun livePhotoManifest() = AssetManifest(
        version = ASSET_MANIFEST_VERSION,
        assetId = "A",
        creationDate = "2026-06-27T10:30:00Z",
        resources = listOf(
            ManifestResource(ResourceRole.PRIMARY, "image/heic", "A-primary.heic", "IMG_0001.HEIC"),
            ManifestResource(ResourceRole.MOTION, "video/quicktime", "A-motion.mov", "IMG_0001.MOV"),
        ),
    )

    @Test
    fun manifest_object_name_is_assetid_dot_manifest_json() {
        assertEquals("A.manifest.json", manifestObjectName("A"))
    }

    @Test
    fun serializes_with_exactly_the_v1_fields() {
        val obj = Json.parseToJsonElement(livePhotoManifest().encodeToJson()).jsonObject
        assertEquals(setOf("version", "assetId", "creationDate", "resources"), obj.keys)
        val resource = obj["resources"]!!.jsonArray[0].jsonObject
        // exactly the four manifest-resource fields — no subtypes/location/flags/dimensions
        assertEquals(setOf("role", "contentType", "filename", "originalFilename"), resource.keys)
    }

    @Test
    fun resources_is_non_empty_and_roles_serialize_as_wire_tokens() {
        val resources = Json.parseToJsonElement(livePhotoManifest().encodeToJson())
            .jsonObject["resources"]!!.jsonArray
        assertTrue(resources.isNotEmpty())
        assertEquals(
            setOf("primary", "motion"),
            resources.mapTo(mutableSetOf()) { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun json_round_trips() {
        val original = livePhotoManifest()
        val decoded = assetManifestFromJson(original.encodeToJson())
        assertEquals(original.encodeToJson(), decoded.encodeToJson())
        assertEquals("A", decoded.assetId)
        assertEquals(ResourceRole.PRIMARY, decoded.resources[0].role)
        assertEquals(ResourceRole.MOTION, decoded.resources[1].role)
    }

    @Test
    fun roles_are_constrained_an_unknown_role_token_fails_to_parse() {
        val bad = """{"version":1,"assetId":"A","creationDate":"","resources":[
            {"role":"thumbnail","contentType":"image/jpeg","filename":"A-thumbnail.jpg","originalFilename":"x.jpg"}]}"""
        assertFailsWith<Exception> { assetManifestFromJson(bad) }
    }
}
