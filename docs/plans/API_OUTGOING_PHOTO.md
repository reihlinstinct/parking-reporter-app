# API outgoing-photo preparation (inactive, issue #600)

The API bridge currently requires decoded images of at most 4 million pixels. Camera originals can be much larger. `ApiOutgoingPhoto` is a JVM preparation helper on the existing bridge classpath, not an active caller. It never modifies originals, writes files or makes a network call.

## Explicit policy

- Bind the original SHA256 before decode; input bytes are capped at 25 MiB. This is a preparation resource limit, not a claim about municipal upload limits.
- Accept JPEG and PNG only, inspect width/height before allocating decoded pixels, reject edges above 20,000. Power-of-two source subsampling bounds the decoded image to an edge of 2560 and 4 million pixels. The extra pixel ceiling preserves compatibility with the bridge rather than blindly raising its limit.
- Read JPEG APP1 or PNG eXIf orientation with bounded offsets; refuse malformed/duplicate orientation metadata. Apply all eight TIFF orientations before encoding. PNG eXIf CRC is verified. Decoder warnings refuse rather than silently accepting a recovered partial image.
- Encode a new RGB JPEG without copying source metadata, so EXIF/location metadata does not travel in the derivative. Transparent PNG input is composited onto white explicitly.
- Try JPEG quality 90, 80, 70, 60, requiring at most 1,100,000 bytes. Refuse rather than silently lowering quality further. These align with the Android `OutgoingEvidence` quality/byte policy but do not establish byte-identical output or image quality across encoders.
- Return original hash, outgoing hash, dimensions and applied orientation. Neither original nor derivative hash is permission to submit. The returned JPEG is copied on access and the result string is redacted.

## Approval and remaining wiring

An exact derivative must be reviewed and bound in the approved effective package before sending. No bridge/controller/workflow calls this helper yet. Do not substitute it silently for an already-approved original or app derivative. The later runner must retain the original reservation identity separately from outgoing attachment identity, preserve per-photo receipt mappings, and reject changed derivatives. Changing a derivative must never evade an original-photo duplicate fence.

Android parity is partial and explicit: both preserve original bytes, apply orientation and strip EXIF in a bounded JPEG derivative, but this helper adds a 4-million-pixel ceiling and white transparency matte, and uses the JVM encoder. It does not claim to reproduce the same JPEG bytes. Vendor constraints and approval presentation remain activation gates in #598.

Seven synthetic JVM tests cover a 24.36-million-pixel original, original/outgoing hashes and immutability, all eight EXIF orientations in both byte orders, PNG eXIf and CRC, transparency, corruption, digest mismatch, oversize input/dimensions and refusal at the quality floor. A rotated synthetic quadrant JPEG was opened and visually checked. Real user photos and municipal APIs were not used.
