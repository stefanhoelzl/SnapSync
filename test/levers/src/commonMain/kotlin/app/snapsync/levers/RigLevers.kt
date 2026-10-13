package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.Serializable

/**
 * **The control channel itself** — what a host says of what it serves, before a caller pulls anything. Every host
 * serves it: it needs nothing.
 */
@Rpc
interface RigLevers {
    /**
     * What this host honours and refuses: every typed service's methods (`<service>.<method>`) and every string verb
     * not yet a typed service, each refused one with its reason. `GET /device` answers the same.
     */
    suspend fun advertise(): DeviceAdvertisement

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<RigLevers>(RigLevers::class, "rig", emptySet(), { serviceDescriptorOf() })
}

/** What a host honours and refuses, and anything it failed to classify. */
@Serializable
data class DeviceAdvertisement(
    val host: String,
    val honoured: List<String>,
    val refused: Map<String, String>,
    /** Vocabulary entries this host neither wires nor refuses. Non-empty answers `500` over `GET /device`. */
    val unclassified: List<String>,
    /** Verbs this host wires that the vocabulary does not name. Non-empty answers `500` over `GET /device`. */
    val outsideVocabulary: List<String>,
)
