package app.snapsync.domain.program

/**
 * A state the program can never reach — the one exception the core raises (`docs/patterns.md`, "Invalid program
 * state"). It is thrown only where something guarantees the state cannot happen, and [because] names that guarantee:
 * a contract, a type, a check just before. A literal, never interpolated, so it carries no value and groups as one
 * crash.
 *
 * It is **fatal**: nothing catches it, and the process ends with a crash report. If it is ever thrown, one of our
 * guarantees is wrong, and running on might not be safe. An [Error], so a catch of `Exception` cannot swallow it;
 * every catch of `Throwable` rethrows it (`CatchGateTest`).
 */
class InvalidProgramState(because: String) : Error(because)
