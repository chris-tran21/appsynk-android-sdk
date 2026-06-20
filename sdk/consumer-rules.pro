# ── AppSynk SDK consumer R8 / ProGuard rules ──────────────────────────────────
# Applied automatically to apps that depend on the SDK (consumerProguardFiles).

# Public API surface.
-keep public class io.appsynk.sdk.AppSynkSDK { public *; }
-keep public class io.appsynk.sdk.AppSynkSDK$debug { public *; }
-keep public class io.appsynk.sdk.AppSynkJava { public *; }
-keep public class io.appsynk.sdk.AppSynkJava$* { *; }
-keep public class io.appsynk.sdk.config.AppSynkOptions { *; }
-keep public class io.appsynk.sdk.config.AppSynkOptions$* { *; }

# Gson-serialized models + responses: R8 must not rename their fields (the wire format is keyed on
# @SerializedName) nor strip the reflectively-instantiated classes.
-keep class io.appsynk.sdk.models.** { *; }
-keep class io.appsynk.sdk.services.PingResponse { *; }
-keep class io.appsynk.sdk.services.SdkInitResponse { *; }
-keepclassmembers class io.appsynk.sdk.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# WorkManager instantiates the worker by reflection.
-keep class io.appsynk.sdk.queue.EventUploadWorker { *; }

# Google Play Install Referrer (AIDL service) — referenced reflectively.
-keep class com.android.installreferrer.** { *; }
-dontwarn com.android.installreferrer.**

# Google Advertising ID (Play Services).
-keep class com.google.android.gms.ads.identifier.** { *; }
-dontwarn com.google.android.gms.**
