# R8 keeps what the manifest names and everything reachable from it; the app reflects on nothing of its own, but it
# does read a few of its own class NAMES (below).

# Ktor's debugger detector references JMX, which Android does not ship; the call is never reached there.
-dontwarn java.lang.management.**

# Line numbers survive into crash reports, so a stack trace retraced against the build's `r8-mapping-<build>` artifact
# (the `/bugsink` skill) names the line, not just an ambiguous method. The source file is renamed to one constant, so
# the attribute names nothing about the source tree; the mapping restores the real file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# An entry point is named by its class (`EntryPoints.kt`: `this::class.simpleName`), and a tap by its intent's class
# (`OnTap.about`): that name is the run's `Trigger` — the log label and the crash report's `entry_point` tag — and the
# entry events a test waits on. Renamed or merged, they read `p13`. So their NAMES are kept; unused ones still go.
# `verifyEntryNames` (app/android/build.gradle.kts) holds every release's mapping to this.
-keepnames class * extends app.snapsync.scheduling.BackgroundEntryPoint
-keepnames class * extends app.snapsync.scheduling.GrantedEntryPoint
-keepnames class * extends app.snapsync.scheduling.InboundEntryPoint
-keepnames class * extends app.snapsync.scheduling.AnsweredEntryPoint
-keepnames class * implements app.snapsync.ui.state.UiIntent
# Each intent is NESTED in `UiIntent`, and Android's `simpleName` of a nested class reads its `InnerClass` annotation;
# R8's full mode drops that annotation unless the OUTER class is kept too (measured 2026-10-28: without this line, every
# intent read `UiIntent$ConfirmCreate`).
-keepnames class app.snapsync.ui.state.UiIntent
