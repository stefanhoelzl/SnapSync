package app.snapsync.ports

import app.snapsync.model.PermissionStatus

import kotlinx.coroutines.flow.StateFlow

/**
 * The state port for permission: a level-triggered state holder whose current value is
 * always available synchronously, so consumers never have to guess while waiting for a
 * first emission. Every value is the whole truth; truth arrives here and nowhere else —
 * [PhotoAccessRequester] commands never carry results back.
 */
interface PhotoAccessStatusSource {
    val permission: StateFlow<PermissionStatus>
}
