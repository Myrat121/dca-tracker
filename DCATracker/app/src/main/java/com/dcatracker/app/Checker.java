package com.dcatracker.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Background price check. Uses the same saved settings as the tracker screen
 * (ladder, filled steps, sell %, near %, price source, Telegram) and the same
 * "already alerted" keys, so the screen and the background never repeat an alert.
 */
public class Checker {

    static final String[] SYMS = {"BTC", "ETH"};
    static final String[] GECKO = {"bitcoin", "ethereum"};
    static final String[] PAIR = {"BTCUSDT", "ETHUSDT"};

    static final double[][][] DEFAULT_STEPS = {
            {{83900, 67}, {77188, 81}, {71013, 97}, {65332, 117}, {60105, 140}, {55297, 168}},
            {{2618, 30}, {2409, 36}, {2216, 44}, {2039, 52}, {1876, 63}, {1725, 75}}
    };
    static final boolean[][] DEFAULT_FILLS = {
            {true, false, false, false, false, false},
            {true, true, false, false, false, false}
    };

    // Emoji written as escapes so the source compiles the same everywhere
    static final String E_TARGET = "🎯";
    static final String E_GREEN = "🟢";
    static final String E_WARN = "⚠️";

    static void run(Context c) throws Exception {
        SharedPreferences p = Store.prefs(c);
        double[] px = fetchPrices(p);
        if (px == null) return;

        double roi = num(p, "dca_roi", 0.04);
        double nearPct = num(p, "dca_nearpct", 1);
        JSONObject alerted = obj(p, "dca_alerted");
        JSONObject near = obj(p, "dca_near");
        JSONObject tg = obj(p, "dca_tg");
        List<String> msgs = new ArrayList<>();

        for (int i = 0; i < SYMS.length; i++) {
            String sym = SYMS[i];
            double price = px[i];
            if (!(price > 0)) continue;

            double[][] st = steps(p, i);
            boolean[] f = fills(p, i, st.length);
            double inv = 0, qty = 0;
            int next = -1;
            for (int j = 0; j < st.length; j++) {
                if (f[j]) {
                    inv += st[j][1];
                    qty += st[j][1] / st[j][0];
                } else if (next < 0) {
                    next = j;
                }
            }
            double avg = qty > 0 ? inv / qty : 0;
            double target = qty > 0 ? avg * (1 + roi) : 0;

            String key = null, msg = null;
            if (target > 0 && price >= target) {
                key = "sell-" + Math.round(target);
                msg = E_TARGET + " " + sym + " at " + usd(price) + " hit your sell target " + usd(target)
                        + " (avg " + usd(avg) + ", profit ≈ " + usd2(qty * price - inv) + ")";
            } else if (next >= 0 && price <= st[next][0]) {
                key = "buy-" + (next + 1);
                msg = E_GREEN + " " + sym + " at " + usd(price) + " reached buy step " + (next + 1) + ": "
                        + usd(st[next][0]) + " for " + usd(st[next][1]) + ". Tick it when filled.";
            }
            if (key != null) {
                if (!key.equals(alerted.optString(sym))) {
                    alerted.put(sym, key);
                    msgs.add(msg);
                }
                continue;
            }

            if (!(nearPct > 0)) continue;
            double nr = nearPct / 100;
            String np = fmtPct(nearPct);
            if (next >= 0 && price > st[next][0] && price <= st[next][0] * (1 + nr)) {
                String k = "nearbuy-" + (next + 1) + "-" + np;
                if (!k.equals(near.optString(sym + "b"))) {
                    near.put(sym + "b", k);
                    msgs.add(E_WARN + " " + sym + " at " + usd(price) + " is "
                            + String.format(Locale.US, "%.2f", (price / st[next][0] - 1) * 100)
                            + "% above buy step " + (next + 1) + " (" + usd(st[next][0]) + ", " + usd(st[next][1]) + ")");
                }
            }
            if (target > 0 && price < target && price >= target * (1 - nr)) {
                String k = "nearsell-" + Math.round(target) + "-" + np;
                if (!k.equals(near.optString(sym + "s"))) {
                    near.put(sym + "s", k);
                    msgs.add(E_WARN + " " + sym + " at " + usd(price) + " is "
                            + String.format(Locale.US, "%.2f", (1 - price / target) * 100)
                            + "% below your sell target " + usd(target) + " (+" + usd2(inv * roi) + " profit)");
                }
            }
        }

        p.edit()
                .putString("dca_alerted", alerted.toString())
                .putString("dca_near", near.toString())
                .apply();

        String token = tg.optString("token", "");
        String chat = tg.optString("chat", "");
        for (String m : msgs) {
            Alerts.notify(c, "DCA alert", m);
            if (!token.isEmpty() && !chat.isEmpty()) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("chat_id", chat);
                    body.put("text", m);
                    Net.postJson("https://api.telegram.org/bot" + token + "/sendMessage", body.toString());
                } catch (Exception ignored) {
                }
            }
        }
    }

    static double[] fetchPrices(SharedPreferences p) {
        String src = str(p, "dca_source", "bybit");
        String type = str(p, "dca_pricetype", "last");
        if ("bybit".equals(src)) {
            String[] hosts = {"https://api.bybit.com", "https://api.bytick.com"};
            for (String host : hosts) {
                try {
                    double[] px = new double[SYMS.length];
                    for (int i = 0; i < SYMS.length; i++) {
                        JSONObject t = new JSONObject(Net.get(host + "/v5/market/tickers?category=linear&symbol=" + PAIR[i]))
                                .getJSONObject("result").getJSONArray("list").getJSONObject(0);
                        px[i] = Double.parseDouble(t.getString("mark".equals(type) ? "markPrice" : "lastPrice"));
                    }
                    return px;
                } catch (Exception ignored) {
                }
            }
        }
        try {
            JSONObject d = new JSONObject(Net.get(
                    "https://api.coingecko.com/api/v3/simple/price?vs_currencies=usd&ids=bitcoin,ethereum"));
            double[] px = new double[SYMS.length];
            for (int i = 0; i < SYMS.length; i++) px[i] = d.getJSONObject(GECKO[i]).getDouble("usd");
            return px;
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---- saved settings (stored as JSON text by the tracker screen) ----

    static Object read(SharedPreferences p, String k) {
        String s = p.getString(k, null);
        if (s == null) return null;
        try {
            return new JSONTokener(s).nextValue();
        } catch (Exception e) {
            return null;
        }
    }

    static double[][] steps(SharedPreferences p, int i) {
        Object o = read(p, "dca_steps");
        if (o instanceof JSONObject) {
            JSONArray a = ((JSONObject) o).optJSONArray(SYMS[i]);
            if (a != null && a.length() > 0) {
                double[][] r = new double[a.length()][2];
                for (int j = 0; j < a.length(); j++) {
                    JSONArray s = a.optJSONArray(j);
                    if (s == null) return DEFAULT_STEPS[i];
                    r[j][0] = s.optDouble(0);
                    r[j][1] = s.optDouble(1);
                }
                return r;
            }
        }
        return DEFAULT_STEPS[i];
    }

    static boolean[] fills(SharedPreferences p, int i, int n) {
        Object o = read(p, "dca_fills");
        JSONArray a = o instanceof JSONObject ? ((JSONObject) o).optJSONArray(SYMS[i]) : null;
        boolean[] r = new boolean[n];
        for (int j = 0; j < n; j++) {
            r[j] = a != null ? a.optBoolean(j, false) : (j < DEFAULT_FILLS[i].length && DEFAULT_FILLS[i][j]);
        }
        return r;
    }

    static double num(SharedPreferences p, String k, double d) {
        Object o = read(p, k);
        return o instanceof Number ? ((Number) o).doubleValue() : d;
    }

    static String str(SharedPreferences p, String k, String d) {
        Object o = read(p, k);
        return o instanceof String ? (String) o : d;
    }

    static JSONObject obj(SharedPreferences p, String k) {
        Object o = read(p, k);
        return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
    }

    // ---- formatting (matches the screen) ----

    static String usd(double n) {
        return n < 100 ? String.format(Locale.US, "$%,.2f", n) : String.format(Locale.US, "$%,.0f", n);
    }

    static String usd2(double n) {
        return String.format(Locale.US, "$%,.2f", n);
    }

    static String fmtPct(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
