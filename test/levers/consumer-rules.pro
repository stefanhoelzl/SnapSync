# kotlinx-rpc finds a service's descriptor on the JVM by NAME: it loads `<the interface's name>$$rpcServiceStub` and
# reads its companion through kotlin-reflect. So a minified build (the Android rig release) keeps the generated stubs,
# their metadata, and the NAME of every interface that has one — rename the interface and the lookup misses.
-keep class **$$rpcServiceStub { *; }
-keep class **$$rpcServiceStub$* { *; }
-if class **$$rpcServiceStub
-keepnames class <1>
-keepattributes RuntimeVisibleAnnotations,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }
