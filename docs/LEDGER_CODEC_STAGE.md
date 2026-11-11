# Inactive codec stage (#88)

Depends on the record envelope in #280. `EncryptedRecordCodec` is a conversion
boundary, not an active store. No production caller, Room schema update or
migration is installed. Keys are owned and zeroed by the future session, not the
stateless codec. Immutable identity/hash/path/revision are retained for duplicate
protection. A tombstone is explicitly present without readable draft content.

PR targets main so Android and repository CI run (their triggers filter main).
Review the implementation separately from its parent commits. Merge dependencies
first, then rebase and repeat exact-head CI/review. Do not activate this code or
infer real-data migration approval from its presence.
