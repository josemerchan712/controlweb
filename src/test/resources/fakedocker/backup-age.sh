#!/usr/bin/env bash
# A fake `age` for BackupScriptsTest. "Encrypting" writes the real age header line and copies the input; "decrypting" removes the
# header line. Enough to test the pipelines: the real cipher is not what is under test.
# FAKE_AGE_FAIL: encryption fails. FAKE_AGE_EMPTY: it succeeds but writes nothing. FAKE_AGE_GARBAGE / FAKE_AGE_TINY: it succeeds but
# writes a file without the age header / a file that is only the header. FAKE_AGE_DECRYPT_FAIL: wrong identity.
echo "age $*" >> "${FAKE_STATE:?}/calls.log"
case "$1" in
  --recipient|-r)
    if [ -n "${FAKE_AGE_FAIL:-}" ]; then cat > /dev/null; exit 1; fi
    if [ -n "${FAKE_AGE_EMPTY:-}" ]; then cat > /dev/null; exit 0; fi
    # exits 0 but writes something that is not an age file (300 bytes, no header) / only the header (22 bytes)
    if [ -n "${FAKE_AGE_GARBAGE:-}" ]; then cat > /dev/null; printf 'x%.0s' {1..300}; exit 0; fi
    if [ -n "${FAKE_AGE_TINY:-}" ]; then cat > /dev/null; echo "age-encryption.org/v1"; exit 0; fi
    echo "age-encryption.org/v1"
    cat
    ;;
  --decrypt|-d)
    if [ -n "${FAKE_AGE_DECRYPT_FAIL:-}" ]; then echo "age: error: no identity matched any of the recipients" >&2; exit 1; fi
    read -r _header
    cat
    ;;
  *) echo "fake age: unexpected: $*" >&2; exit 2 ;;
esac
