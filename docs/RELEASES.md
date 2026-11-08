# Releases

APKs are published on [GitHub Releases](https://github.com/Sr-0w/Dot-Mail/releases), built from the tagged
commit and signed with the project key. Verify before installing:

```
apksigner verify --print-certs DotMail-<version>.apk
# Signer #1 certificate SHA-256 digest must be:
# 12c122aa367a5d0a0abf8f29e92fe28dfad37cf0373997494960e43391f7c81f4
sha256sum -c DotMail-<version>.apk.sha256
```

Pre-releases (`-alpha`, `-beta`) are for testing; expect rough edges. Debug builds and release builds are signed
with different keys, so switching between them requires uninstalling (which deletes local mail cache and
saved passwords — your mail stays on your server).
