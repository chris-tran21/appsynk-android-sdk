# ProGuard/R8 rules for the AppSynk SDK module.
#
# Minification is disabled for the library build (isMinifyEnabled = false), so these rules
# are not applied when assembling the AAR. Rules that must reach consumer apps live in
# consumer-rules.pro. Add module-specific keep rules here if you enable minification.
