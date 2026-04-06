# AppActor does not rely on reflection-heavy public APIs, so consumers do not
# need aggressive keep rules by default.
#
# Preserve Kotlin metadata and generic signatures so coroutine stack traces and
# public model types remain readable after minification.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
