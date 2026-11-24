package com.carrace;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;
        Intent svc = new Intent(context, SmsService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);
    }
}
