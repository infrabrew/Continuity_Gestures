# Preserve Kotlin metadata and DataStore classes
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Wear Ongoing Activity and Bluetooth HID callbacks
-keep class androidx.wear.ongoing.** { *; }
-keep class com.gesturemouse.wear.hid.** { *; }
