# R8 keep 规则（T009）。
#
# JNI 侧（librslib_bridge.so）按 Java 类名与方法名查找回调，R8 改名或裁剪会直接
# 抛 UnsatisfiedLinkError；NativeBridge 与它抛出的 RslibException 必须原样保留。
-keep class ink.inkdeck.rslib.NativeBridge { *; }
-keep class ink.inkdeck.rslib.RslibException { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# protobuf 动态消息只依赖 descriptor（assets 里的 anki_descriptors.bin），
# 不反射业务 Java 类；R8 的告警可忽略，避免误删 DescriptorProtos。
-dontwarn com.google.protobuf.**
-keep class com.google.protobuf.DescriptorProtos { *; }
-keep class com.google.protobuf.Descriptors { *; }
-keep class com.google.protobuf.DynamicMessage { *; }
