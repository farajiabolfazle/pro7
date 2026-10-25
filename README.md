# VCam Injector — تزریق ویدیو به دوربین گوشی روت‌شده (نسخه پیشرفته)

## اجزا

1. **android_module/** — ماژول LSPosed: ساختن session دوربین (Camera1 و Camera2)
   را هوک می‌کند و فریم‌های ویدیو را روی Surface خروجی (پیش‌نمایش، MediaRecorder،
   ImageReader) رندر می‌کند؛ با پشتیبانی از **توقف/ادامه، پیمایش و تغییر سرعت زنده**.
2. **injector_win/app.py** — اپ ویندوزی پیشرفته:
   - پلیر داخلی با پیش‌نمایش ویدیو
   - **نوار پیمایش** (seek bar)
   - دکمه **توقف / ادامه**
   - اسلایدر **سرعت** ۰٫۲۵x تا ۴x
   - **زوم با اسکرول موس** روی پیش‌نمایش
   - پنجره **PiP** شناور، همیشه-روی-همه و قابل جابه‌جایی با موس
   - مدیریت گوشی با **کابل USB** (ADB): بررسی اتصال و روت، ارسال ویدیو،
     START/STOP تزریق، ری‌استارت اپ هدف
   - **همگام‌سازی زنده**: توقف/ادامه، پیمایش و سرعت بلافاصله روی تزریق گوشی
     هم اعمال می‌شود (از طریق فایل `control.json`)

## راه‌اندازی ویندوز

```
pip install -r injector_win/requirements.txt
python injector_win/app.py
```
نیازها: Python 3، کتابخانه‌های `opencv-python` و `pillow`، و Android
Platform-Tools (دستور `adb` در PATH). گوشی با کابل وصل باشد و USB Debugging
فعال (Settings > Developer options > USB debugging) و روت با Magisk.

## ساخت APK بدون اندروید استودیو — GitHub Actions (پیشنهادی)

1. یک ریپوی جدید در github.com بسازید (private هم می‌شود).
2. **کل محتویات پوشه `video_injector`** (شامل `android_module` و پوشه مخفی
   `.github`) را در ریشه ریپو آپلود کنید — از طریق گیت یا دکمه
   Add file > Upload files در خود سایت.
3. بخش **Actions** ریپو: workflow به نام «Build VCam APK» ظاهر می‌شود؛
   روی Run workflow بزنید (یا با هر push خودکار اجرا می‌شود).
4. بعد از اتمام (حدود ۵ دقیقه)، از صفحه همان اجرا بخش **Artifacts** فایل
   `vcam-debug-apk` را دانلود کنید — داخلش `app-debug.apk` است.

## ساخت محلی بدون اندروید استودیو (اختیاری)

1. JDK 17 را نصب کنید.
2. Android Command Line Tools را از
   https://developer.android.com/studio#command-tools دانلود و از حالت فشرده
   خارج کنید؛ با `sdkmanager` این‌ها را نصب کنید:
   `"platform-tools" "platforms;android-34" "build-tools;34.0.0"`
3. Gradle 8.7 را نصب کنید، سپس:
   ```
   cd android_module
   gradle assembleDebug
   ```
4. خروجی: `android_module/app/build/outputs/apk/debug/app-debug.apk`

## نصب ماژول روی گوشی

1. APK را نصب کنید.
2. در **LSPosed** ماژول «VCam Injector» را فعال و در Scope فقط اپ هدف
   (مثلاً دوربین) را تیک بزنید؛ اپ هدف را Force-stop کنید.

## روال استفاده روزمره

1. گوشی را با کابل وصل کنید، اپ ویندوزی را باز کنید، «Check device + root».
2. «Select video» → «Push video to phone».
3. «START injection» → «Restart target app».
4. حالا با همان اپ ویندوزی پخش را مدیریت کنید: توقف/ادامه، پیمایش، سرعت —
   همه لحظه‌ای روی تصویر دوربین گوشی اعمال می‌شود. PiP برای داشتن پیش‌نمایش
   کنار بقیه پنجره‌هاست.

## پروتکل کنترل زنده

اپ ویندوزی این فایل را روی گوشی می‌نویسد و ماژول هر ۳۰۰ میلی‌ثانیه می‌خواند:

```
/sdcard/vcam/control.json
{"playing": true, "position_ms": 12345, "speed": 1.0,
 "video": "/sdcard/vcam/video.mp4"}
```

## نکات

- ویدیو را با رزولوشن و نسبت تصویر خروجی دوربین بسازید (مثلاً 1920×1080).
- اگر تصویر مشکی شد، با H.264 و keyframe منظم دوباره انکود کنید:
  `ffmpeg -i in.mp4 -c:v libx264 -g 30 -pix_fmt yuv420p out.mp4`
- به اپ هدف دسترسی Storage بدهید تا فایل‌های config/control را بخواند.

## هشدار

فقط روی دستگاه خودتان و برای تست/توسعه استفاده کنید. دور زدن احراز هویت،
تأیید چهره یا فریب سرویس‌ها با این ابزار غیرقانونی است.
