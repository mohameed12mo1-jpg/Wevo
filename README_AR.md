# MicUp → Wevo Live Bridge

إضافة Android خارجية لـMicUp تنفذ واجهة AIDL المسماة `com.micplugin.IAudioPlugin`. تستقبل عينات mono بصيغة float بعد معالجة MicUp، وتحولها إلى PCM16 little-endian بمعدل 16 kHz، ثم تحاول إرسالها إلى مستمع XMicInject المحلي على `127.0.0.1:38673`.

## البناء
يُبنى APK تلقائياً بواسطة GitHub Actions عند كل push إلى main أو عبر التشغيل اليدوي:
Actions → Build MicUp Wevo Bridge APK.
الناتج: artifact باسم `MicUp-Wevo-Bridge-debug-apk`.

## تثبيت واستخدام
هذا APK هو **MicUp Audio Plugin** وليس LSPosed module. ثبّته كتطبيق عادي، ثم ابحث عنه من داخل MicUp في قائمة Plugins وأضفه إلى سلسلة المعالجة. اترك XMicInject الحالي كما هو ومفعلاً ضمن Wevo فقط.

## حدود الاختبار
- يثبت نجاح البناء أن المشروع تم تجميعه فقط؛ لا يثبت أن MicUp يتعرف إلى الإضافة أو أن اتصال loopback يصل إلى XMicInject.
- تقرير الفحص السابق يذكر مستمع XMicInject على 0.0.0.0:38673 وتنسيق PCM16LE mono 16 kHz، لكن يجب اختبار التوافق الفعلي على الهاتف.
- إذا لم يتصل، لا تغيّر إعدادات الروت أو LSPosed. نحتاج سجل Logcat لتحديد هل المشكلة اكتشاف Plugin أو اتصال TCP أو تنسيق الصوت.
