# Python calls this callback by name through Chaquopy, which R8 can't see.
-keep interface io.github.jherobred.lodisave.SaveWorker$Sink { *; }
# ONNX Runtime's native code finds its Java classes and fields by name.
-keep class ai.onnxruntime.** { *; }
