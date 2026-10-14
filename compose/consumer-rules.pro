# `:feedback` finds the Compose hit tester with Class.forName, so that an app which adds this module
# gets tap-to-select on Compose content without a line of setup. A shrinker cannot see that lookup,
# hence this rule: keep the class's name and the Kotlin object's INSTANCE field.
-keep class app.overblast.feedback.compose.ComposeHitTester {
    public static ** INSTANCE;
}
