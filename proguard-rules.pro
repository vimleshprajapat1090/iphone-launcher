keep class com.iphone.launcher.LockAdmin { *; }
-keep class com.iphone.launcher.NotificationListener { *; }
-keep class android.app.StatusBarManager {
    public void expandNotificationsPanel();
}
-keep class com.iphone.launcher.** { *; }
