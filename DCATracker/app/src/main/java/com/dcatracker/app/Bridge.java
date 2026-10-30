package com.dcatracker.app;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

/** Functions the tracker page can call as window.Android.* */
public class Bridge {

    private final Context ctx;

    Bridge(Context c) {
        ctx = c.getApplicationContext();
    }

    @JavascriptInterface
    public String get(String key) {
        return Store.prefs(ctx).getString(key, null);
    }

    @JavascriptInterface
    public void set(String key, String value) {
        Store.prefs(ctx).edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String httpGet(String url) {
        try {
            return Net.get(url);
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public String httpPost(String url, String jsonBody) {
        try {
            return Net.postJson(url, jsonBody);
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public void notify(String title, String message) {
        Alerts.notify(ctx, title, message);
    }

    @JavascriptInterface
    public String notifStatus() {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "denied";
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled() ? "granted" : "denied";
    }

    @JavascriptInterface
    public void requestNotif() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
