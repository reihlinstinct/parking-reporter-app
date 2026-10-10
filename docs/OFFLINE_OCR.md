# Offline plate suggestion preview

This stage replaces a proposed telemetry-bearing recognition SDK with Tesseract4Android 4.9.0. Recognition runs in JNI on the device. There is no recognition HTTP request, remote model download, or analytics integration in this path. The separate optional Android geocoder still has its existing explicit disclosure.

English tessdata_fast is bundled in the APK by a build-only script. Source commit: `87416418657359cb625c412a48b6e1d6d41c29bd`; SHA-256: `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`. The build and local install verify the model hash. The model is copied to noBackupFilesDir, never to shared storage.

Upstream sources:
- https://github.com/adaptech-cz/Tesseract4Android
- https://github.com/adaptech-cz/Tesseract4Android/blob/4.9.0/LICENSE
- https://github.com/tesseract-ocr/tessdata_fast
- https://github.com/tesseract-ocr/tessdata_fast/blob/main/LICENSE

The wrapper and model are Apache-2.0. Native Leptonica, libpng and Independent JPEG Group notices are packaged under assets/licenses. The application's MIT license does not override these licenses. Third-party JNI binary provenance still deserves a reproducible source-build review before release.

The explicit button scans a bounded local bitmap on a worker thread and offers at most ten candidates. Only seven/eight ASCII digits or the corresponding hyphenated forms are accepted. Letters are never silently converted to digits, spaced fragments are never joined, and output is never automatically approved. Choosing a candidate edits the draft and invalidates its digest approval. Empty/failing recognition preserves manual entry.

This is generic OCR, not a validated vehicle/license-plate detector. Full-frame OCR can miss small/angled plates or offer unrelated numbers. No accuracy promise is made. Synthetic native tests are not field evaluation. Android 15+ 16 KB page-size compatibility for the prebuilt native libraries is not yet verified. Physical-device memory/lifecycle tests, crop selection and a privacy-safe annotated test set remain before claiming M2 complete.

The model and native libraries increase APK size. No municipality credentials, network routes or real reports are involved.

## Optional Google ML Kit comparison

Bundled Latin text-recognition 16.0.1 is an explicit alternate OCR option. Model is packaged at build time, not fetched on first recognition. Input photographs and recognized plate text are processed on-device and are not sent to Google. The SDK sends usage/performance metrics to Google and may contact Google for updates or accelerator information. These metrics are allowed for this development stage and disclosed in the review/privacy screens. Tesseract remains available. No photos, reporter data or real reports are used in tests.

Sources: https://developers.google.com/ml-kit/terms and https://developers.google.com/ml-kit/vision/text-recognition/v2/android

Synthetic comparison uses equal inputs and the same candidate parser. It is a tiny development check, not field accuracy or evidence of behavior on the test phone. Default selection remains provisional until comparison results are reviewed.
