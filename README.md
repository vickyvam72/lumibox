# MovieBox Android

Versi aplikasi Android dari `moviebox-tui`. Begitu dibuka, aplikasi **langsung menampilkan daftar film/series** dari beranda MovieBox (tanpa harus mencari dulu). Pencarian tetap tersedia lewat ikon 🔍.

## Struktur

```
core/      ← kode Rust kamu (moviebox-tui), TIDAK diubah
bridge/    ← library Rust kecil (JNI) yang membuka fungsi core ke Android
android/   ← aplikasi Android (Kotlin + Jetpack Compose + ExoPlayer)
.github/workflows/build-apk.yml  ← build APK otomatis di GitHub
```

Alurnya: Android memanggil `bridge` → `bridge` memanggil `MovieBoxService` milik core (`homepage`, `search`, `details`, `episode_streams`, `get_ext_captions`) → hasilnya dikirim balik sebagai JSON.

## Fitur

- **Beranda langsung tampil**: baris-baris kategori (Sorotan, Trending, dst.) dari `homepage()`, infinite scroll, tarik ke bawah untuk refresh
- **Cari** film/series (otomatis saat berhenti mengetik)
- **Detail**: poster, rating, genre, sinopsis, pemeran, pilihan **bahasa audio (dub)**
- **Series**: pilih season dan episode
- **Pilih kualitas** (1080p/720p/…), lalu diputar di **player bawaan** (ExoPlayer, mendukung DASH + header/cookie MovieBox)
- **Subtitle** otomatis dimuat; Bahasa Indonesia dipilih duluan kalau ada (ganti lewat tombol CC di player)
- **Lanjutkan menonton**: posisi terakhir diingat per film/episode

## Cara mendapatkan APK (tanpa install Android Studio)

1. Buat akun di https://github.com (gratis), lalu buat repository baru (boleh **Private**).
2. Upload **seluruh isi folder ini** ke repository itu:
   - cara gampang: di halaman repo klik **Add file → Upload files**, lalu drag semua folder/file
     (pastikan folder `.github` ikut; kalau tersembunyi di Windows, aktifkan "Hidden items").
   - atau pakai git:
     ```bash
     git init
     git add .
     git commit -m "MovieBox Android"
     git branch -M main
     git remote add origin https://github.com/USERNAME/NAMA-REPO.git
     git push -u origin main
     ```
3. Buka tab **Actions** di repo → workflow **Build APK** akan jalan otomatis (±15–25 menit untuk build pertama).
   Kalau tidak jalan, klik **Build APK → Run workflow**.
4. Setelah centang hijau ✅, klik run tersebut → bagian **Artifacts** → download **MovieBox-APK** (file zip berisi `MovieBox.apk`).
5. Pindahkan `MovieBox.apk` ke HP → buka → izinkan "Install dari sumber tidak dikenal" → install.

**Bonus:** kalau kamu push tag, misalnya `git tag v0.1.0 && git push --tags`, APK juga otomatis ditaruh di halaman **Releases**, jadi link download-nya lebih rapi.

## Build di komputer sendiri (opsional)

Butuh: Rust, Android Studio (SDK + NDK), dan `cargo install cargo-ndk`.

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
cd bridge
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 26 -o ../android/app/src/main/jniLibs build --release
```

Lalu buka folder `android/` di Android Studio → **Build → Build APK(s)**.

## Mengubah sesuatu

| Mau mengubah… | File |
|---|---|
| Tab beranda yang dimuat (default `"2"`, sama seperti TUI) | `android/app/src/main/java/com/moviebox/app/data/Repo.kt` → `HOME_TAB_ID` |
| Tampilan beranda | `android/.../ui/HomeScreen.kt` |
| Halaman detail / episode / kualitas | `android/.../ui/DetailScreen.kt` |
| Player | `android/.../PlayerActivity.kt` |
| Warna/tema | `android/.../ui/Theme.kt` |
| Nama & versi aplikasi | `android/app/src/main/res/values/strings.xml`, `android/app/build.gradle.kts` (`versionCode`, `versionName`) |
| Fungsi Rust yang dibuka ke Android | `bridge/src/lib.rs` |

Kalau kamu memperbarui kode core, cukup timpa isi folder `core/` lalu push lagi; APK baru akan di-build otomatis.

## Catatan

- APK ditandatangani dengan keystore `android/app/moviebox-release.jks` (password `moviebox123`) yang ikut di repo, jadi APK versi baru bisa langsung di-install menimpa yang lama. Kalau repo-nya publik dan mau lebih aman, ganti keystore & password-nya.
- Kalau beranda gagal dimuat padahal internet lancar, kemungkinan DNS/ISP memblokir server API. Coba nyalakan **Private DNS** (Pengaturan → Jaringan → Private DNS → `dns.google` atau `one.one.one.one`) atau pakai VPN.
- Kalau sebuah video gagal diputar, coba kualitas lain dari dialog pilihan kualitas.
- Hanya provider **MovieBox** yang dipakai di versi Android ini (provider lain masih tersedia di core dan bisa ditambahkan lewat `bridge/src/lib.rs`).
