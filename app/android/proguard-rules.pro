# R8 keeps what the manifest names and everything reachable from it; the app reflects on nothing of its own.

# Ktor's debugger detector references JMX, which Android does not ship; the call is never reached there.
-dontwarn java.lang.management.**
