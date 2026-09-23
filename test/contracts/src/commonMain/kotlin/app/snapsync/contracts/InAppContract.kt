package app.snapsync.contracts

/**
 * A contract an app build can run in-app, on the [host] it is registered for (capability `port-contracts`). The
 * rig serves every registered entry at `POST /contract/<name>`, and lists the current host's at `GET /contract`,
 * which is what the `ios-contracts` job runs. [run] answers the body the rig returns: a recording, an outcome
 * table, or a [CONTRACT_REFUSED] refusal.
 */
class InAppContract(val name: String, val host: Host, val run: () -> String)

/**
 * The simulator app's entry for [contract] against [binding]: an [InAppContract] on [Host.IOS_SIM_APP] whose run
 * answers the outcome table, prefixed by a header naming the contract and host.
 *
 * [refusal] is the grant precondition (capability `port-contracts`, "An authorization the process cannot give
 * itself is a precondition of the run"). When it names a reason, the whole run is refused before any clause
 * executes, so a mis-granted launch reports a refusal rather than a table of outcomes that never ran.
 *
 * The contract-coverage gate reads calls to this function to learn which simulator-app bindings the CI job runs,
 * so the binding must be passed as a constructor call of a named class.
 */
fun <K : Enum<K>, T> simulatorAppContract(
    contract: Contract<K, T>,
    binding: Binding<K, T>,
    refusal: () -> String?,
): InAppContract = InAppContract(contract.name, Host.IOS_SIM_APP) {
    val refused = refusal()
    if (refused != null) {
        "$CONTRACT_REFUSED$refused\n"
    } else {
        "# contract: ${contract.name}\n# host: ${binding.host}\n" + run(contract, binding).table() + "\n"
    }
}
