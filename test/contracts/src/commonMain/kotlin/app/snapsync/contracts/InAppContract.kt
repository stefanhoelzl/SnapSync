package app.snapsync.contracts

/**
 * A contract an app build can run in-app, on the [host] it is registered for (`docs/architecture.md`). The
 * rig serves every registered entry at `POST /contract/<name>`, and lists the current host's at `GET /contract`,
 * which is what the `ios-contracts` job runs. [run] answers the body the rig returns: a recording, an outcome
 * table, or a [CONTRACT_REFUSED] refusal. [run] receives the verb's query parameters — the values only the run can
 * supply, such as where the transfer fixture listens ([RunParameters]).
 */
class InAppContract(val name: String, val host: Host, val run: (params: Map<String, String>) -> String)

/**
 * A binding that needs a value only the run can supply — the loopback transfer fixture's address, which the
 * `ios-contracts` job picks per run (`docs/architecture.md`, "An adapter bound per compilation target is
 * real for the clauses it runs there"). [accept] answers `null` when the parameters suffice, or the reason the
 * run must be refused, so a run missing its fixture is refused whole rather than failing clause by clause.
 */
interface RunParameters {
    fun accept(params: Map<String, String>): String?
}

/**
 * The simulator app's entry for [contract] against [binding]: an [InAppContract] on [Host.IOS_SIM_APP] whose run
 * answers the outcome table, prefixed by a header naming the contract and host.
 *
 * [refusal] is the grant precondition (`docs/architecture.md`, "An authorization the process cannot give
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
): InAppContract = InAppContract(contract.name, Host.IOS_SIM_APP) { params ->
    val refused = refusal() ?: (binding as? RunParameters)?.accept(params)
    if (refused != null) {
        "$CONTRACT_REFUSED$refused\n"
    } else {
        "# contract: ${contract.name}\n# host: ${binding.host}\n" + run(contract, binding).table() + "\n"
    }
}
