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

## Google ML Kit (default engine) and Tesseract (Settings alternative)

Bundled Latin text-recognition 16.0.1 is the default OCR engine since #210 (owner decision 15 Nov 2026, after a private real-photo comparison). Tesseract stays available as a Settings-only choice, stored as a nonsecret preference ("ocr_engine" in the plain ui-prefs file); there is no per-report selector and no first-run choice. An install with no stored choice uses ML Kit, and the data note stays beside the switch in Settings. Model is packaged at build time, not fetched on first recognition. Input photographs and recognized plate text are processed on-device and are not sent to Google. The SDK sends usage/performance metrics to Google and may contact Google for updates or accelerator information. These metrics are allowed for this development stage and disclosed in the review/privacy screens. Tesseract remains available. No photos, reporter data or real reports are used in tests.

Sources: https://developers.google.com/ml-kit/terms and https://developers.google.com/ml-kit/vision/text-recognition/v2/android

(Superseded: ML Kit is now the default, see above.) Synthetic comparison uses equal inputs and the same candidate parser. It is a tiny development check, not field accuracy or evidence of behavior on the test phone. Public emulator run 38066741292: both engines returned the expected candidate on all four plate cases and no candidates on all four blank/distractor cases. Accuracy tied on this tiny corpus. Tesseract took 100-329 ms and ML Kit 3177-3641 ms per case on this network-disabled emulator, creating a recognizer for each call. These are not phone performance or field accuracy measurements. All 15 instrumented tests passed.

Observed run: https://github.com/reihlinstinct/parking-reporter-app/actions/runs/38066741292

## Real-photo comparison (#210, 15 Nov 2026)

Private sample of 30 recent photos, one run, API 30 emulator, network off, labels from report records. With the candidate filter as it was, both engines returned no candidate for 30 of 30 photos; ML Kit's raw text held the expected digits in 17 of 30 and Tesseract's in 1 of 30. The filter now accepts the ":" and "." separators and an attached flag letter that ML Kit returns (#435); an offline replay gave 15 of 30 exact top-1 for ML Kit and about 1 of 30 for Tesseract. Photos, plates and raw text are not published.
