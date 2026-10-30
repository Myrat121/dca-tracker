package com.dcatracker.app;

import android.content.Context;
import android.content.SharedPreferences;

/** One shared settings store for the screen and the background checks. */
public class Store {
    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("dca", Context.MODE_PRIVATE);
    }
}
