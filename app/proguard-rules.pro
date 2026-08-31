# Add project specific ProGuard rules here.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontwarn org.opencv.**
