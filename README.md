# Apk React Ch

Aplikasi Android (Java) yang hanya berisi WebView. Halaman yang tampil adalah `app/src/main/assets/index.html`.

Dukungan: Android 5.0 (API 21) sampai versi terbaru.

## Struktur

- `app/src/main/java/com/carrace/MainActivity.java`: WebView dan penyajian file dari `assets/`
- `app/src/main/assets/`: taruh `index.html` dan file build React di sini
- `app/src/main/res/`: icon (tikus outline), string, dan tema
- `.github/workflows/build.yml`: workflow GitHub Actions untuk build APK

## Cara build

1. Push folder ini ke repo GitHub.
2. Buka tab Actions, workflow Build APK jalan otomatis (atau pilih Run workflow).
3. Download artifact `Apk React Ch` dari halaman run tersebut.

## Catatan

- Halaman dimuat di `https://appassets.androidplatform.net/assets/index.html`, jadi aset React (module script) tetap bisa dimuat.
- WebView di Android 5 dan 6 ikut versi System WebView yang terpasang. Build React dengan target ES yang lebih lama bila halaman tampak kosong di perangkat lama.
# CVE, RCE, XSS, SQLI SCANNER, DUMP SCHOOL DATABASE
