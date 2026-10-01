# R8 keeps what the manifest names and everything reachable from it; the app reflects on nothing of its own.

# Ktor's debugger detector references JMX, which Android does not ship; the call is never reached there.
-dontwarn java.lang.management.**

# Line numbers survive into crash reports, so a stack trace retraced against the build's `r8-mapping-<build>` artifact
# (the `/bugsink` skill) names the line, not just an ambiguous method. The source file is renamed to one constant, so
# the attribute names nothing about the source tree; the mapping restores the real file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
