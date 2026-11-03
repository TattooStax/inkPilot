-keep class inkpilot.inference.NativeInference { *; }
-keep interface inkpilot.agent.DraftCancellation {
    public boolean isCancelled();
}
-keepclassmembers class * implements inkpilot.agent.DraftCancellation {
    public boolean isCancelled();
}
