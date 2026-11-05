package app.snapsync.control

/**
 * This test verifies a requirement of `openspec/specs/<spec>/spec.md`: [requirement] is the title as written after
 * `### Requirement: `, and [scenario], when given, one `#### Scenario: ` title beneath it.
 *
 * The one link from the contract of record to the code — production code cites no spec. `:test:architecture`'s
 * `VerifiesGateTest` fails the build when the spec, the requirement or the scenario is not there, so a requirement
 * renamed by an OpenSpec change sends someone to every test that claimed it; `./gradlew
 * :test:architecture:verifiesReport` lists the requirements no test claims.
 *
 * On the class when every test in it verifies the requirement, on the function otherwise; repeated when a test
 * verifies several. Named arguments and plain string literals only: the gate reads SOURCE text (the journeys are on
 * no classpath it has), which is also why nothing is retained.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Repeatable
annotation class Verifies(val spec: String, val requirement: String, val scenario: String = "")
