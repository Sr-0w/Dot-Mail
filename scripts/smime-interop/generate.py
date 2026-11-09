#!/usr/bin/env python3
"""Generates the S/MIME interoperability fixtures with the openssl CLI (and gpgsm when it is installed).

    python3 scripts/smime-interop/generate.py            # writes data/src/test/resources/smime-interop/

Everything here is a throw-away TEST key: self-signed identities created by this script. The point is to have messages
produced by independent implementations (OpenSSL's `cms` and legacy `smime` commands, GnuPG's gpgsm) in the shapes real
clients send, so the receive path (Mime.cache -> verify / decrypt) is exercised against bytes we did not write ourselves.
`manifest.json` states what each message must yield; DataInteropTest reads it.

Needs: openssl >= 3 (cms, smime), python3. Optional: gpgsm (cases are skipped when it is missing or fails).
"""
import base64, json, os, pathlib, shutil, subprocess, sys, tempfile, textwrap

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "data/src/test/resources/smime-interop"
KEYS = OUT / "keys"
CASES = OUT / "cases"
CRLF = "\r\n"

# Some distributions' OpenSSL policy refuses SHA-1 signatures; real clients still send them, so the generator uses a neutral config.
_CONF = pathlib.Path(tempfile.mkdtemp(prefix="smime-interop-conf-")) / "openssl.cnf"
_CONF.write_text("openssl_conf = default_conf\n[default_conf]\n[req]\ndistinguished_name = dn\n[dn]\n")

def run(*args, stdin=None, env=None, check=True, timeout=None):
    env = dict(env if env is not None else os.environ)
    env.setdefault("OPENSSL_CONF", str(_CONF))
    p = subprocess.run([str(a) for a in args], input=stdin, capture_output=True, env=env, timeout=timeout)
    if check and p.returncode != 0:
        raise SystemExit(f"{' '.join(map(str, args))}\n{p.stderr.decode(errors='replace')}")
    return p

# ---- identities ------------------------------------------------------------------------------------------------------
IDENTITIES = {
    # name: (key kind, email, key usage)
    "alice-rsa":   ("rsa:2048", "alice-rsa@example.test", "digitalSignature,keyEncipherment"),
    "alice-p256":  ("ec:prime256v1", "alice-p256@example.test", "digitalSignature,keyAgreement"),
    "alice-p384":  ("ec:secp384r1", "alice-p384@example.test", "digitalSignature,keyAgreement"),
    "bob-rsa":     ("rsa:2048", "bob-rsa@example.test", "digitalSignature,keyEncipherment"),
    "carol-p256":  ("ec:prime256v1", "carol-p256@example.test", "digitalSignature,keyAgreement"),
    "dave-p384":   ("ec:secp384r1", "dave-p384@example.test", "digitalSignature,keyAgreement"),
    "erin-rsa":    ("rsa:2048", "erin-rsa@example.test", "digitalSignature,keyEncipherment"),
}

def make_identity(name, kind, email, usage):
    key, crt = KEYS / f"{name}.key.pem", KEYS / f"{name}.crt.pem"
    if kind.startswith("rsa:"):
        run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", f"rsa_keygen_bits:{kind[4:]}", "-out", key)
    else:
        run("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", f"ec_paramgen_curve:{kind[3:]}", "-pkeyopt", "ec_param_enc:named_curve", "-out", key)
    run("openssl", "req", "-x509", "-new", "-key", key, "-subj", f"/CN={name}", "-days", "3650", "-out", crt,
        "-addext", f"subjectAltName=email:{email}", "-addext", f"keyUsage=critical,{usage}",
        "-addext", "extendedKeyUsage=emailProtection", "-addext", "basicConstraints=CA:FALSE")

# ---- message pieces --------------------------------------------------------------------------------------------------
def text_entity(body):
    return ("Content-Type: text/plain; charset=utf-8" + CRLF + "Content-Transfer-Encoding: 8bit" + CRLF + CRLF + body + CRLF).encode()

def mixed_entity(body, name="proof.txt", data="attached bytes"):
    b = "=_interop_boundary"
    return (f"Content-Type: multipart/mixed; boundary=\"{b}\"{CRLF}{CRLF}--{b}{CRLF}Content-Type: text/plain; charset=utf-8{CRLF}"
            f"Content-Transfer-Encoding: 8bit{CRLF}{CRLF}{body}{CRLF}--{b}{CRLF}Content-Type: text/plain; name=\"{name}\"{CRLF}"
            f"Content-Disposition: attachment; filename=\"{name}\"{CRLF}Content-Transfer-Encoding: base64{CRLF}{CRLF}"
            f"{base64.b64encode(data.encode()).decode()}{CRLF}--{b}--{CRLF}").encode()

def qp_folded_entity(body):
    """An entity whose headers are folded and whose body is quoted-printable with soft line breaks: byte-exact signing matters."""
    return (("Content-Type: text/plain;" + CRLF + " charset=\"utf-8\";" + CRLF + " format=flowed" + CRLF +
             "Content-Transfer-Encoding: quoted-printable" + CRLF +
             "Content-Description: a long header value that is folded over" + CRLF + " several lines on purpose" + CRLF + CRLF +
             body.replace("é", "=C3=A9") + " with a soft break =" + CRLF + "continues here=20" + CRLF + "caf=C3=A9 done" + CRLF)).encode()

def outer_headers(frm, to, subject, extra=""):
    return f"From: {frm}{CRLF}To: {to}{CRLF}Subject: {subject}{CRLF}Date: Mon, 09 Nov 2026 10:00:00 +0000{CRLF}Message-ID: <{abs(hash(subject)) % 10**9}@interop.test>{CRLF}MIME-Version: 1.0{CRLF}{extra}"

def split_message(raw):
    """(headers dict-ish text, body bytes) of an openssl SMIME output (LF or CRLF separated)."""
    found = [(i, sep) for sep in (b"\r\n\r\n", b"\n\n") if (i := raw.find(sep)) >= 0]
    if not found: raise SystemExit("no header/body separator")
    i, sep = min(found)  # the earliest blank line ends the outer headers (the signed entity has its own further down)
    return raw[:i].decode(), raw[i + len(sep):]

def openssl_cms(mode, entity, *extra, tool="cms", frm=None, to=None, subject=None):
    args = ["openssl", tool, mode, "-outform", "SMIME"]
    if frm: args += ["-from", frm]
    if to: args += ["-to", to]
    if subject: args += ["-subject", subject]
    return run(*args, *extra, stdin=entity).stdout

def sign_args(who, md="sha256", *more):
    return ["-signer", KEYS / f"{who}.crt.pem", "-inkey", KEYS / f"{who}.key.pem", "-md", md, *more]

def enc_args(*recips, cipher="-aes256", more=()):
    out = []
    for r in recips: out += ["-recip", KEYS / f"{r}.crt.pem"]
    return [cipher, *more, *out] if False else [*out, cipher, *more]

def rewrite_cte(raw, cte):
    """Re-serialises an openssl message with its single base64 CMS part carried as binary / 8bit instead (seen from some clients)."""
    head, body = split_message(raw)
    if "boundary=" in head:  # multipart/signed: the signature is the last part
        b = head.split("boundary=")[1].split(";")[0].strip('"\r\n ')
        marker = ("--" + b).encode()
        parts = body.split(marker)
        sig = parts[-2]
        sh, sb = sig.split(b"\r\n\r\n", 1) if b"\r\n\r\n" in sig else sig.split(b"\n\n", 1)
        sh = sh.replace(b"base64", cte.encode()).replace(b"Content-Transfer-Encoding: " + cte.encode(), b"Content-Transfer-Encoding: " + cte.encode())
        der = base64.b64decode(sb.strip())
        parts[-2] = sh + b"\r\n\r\n" + der + b"\r\n"
        return head.encode() + b"\r\n\r\n" + marker.join(parts)
    head = "\r\n".join(l for l in head.splitlines())
    head = head.replace("Content-Transfer-Encoding: base64", "Content-Transfer-Encoding: " + cte)
    return head.encode() + b"\r\n\r\n" + base64.b64decode(body)

def with_lf_only(raw):
    return raw.replace(b"\r\n", b"\n")

# ---- the cases -------------------------------------------------------------------------------------------------------
cases = []
def add(name, raw, **expect):
    (CASES / f"{name}.eml").write_bytes(raw)
    cases.append({"name": name, "file": f"cases/{name}.eml", **expect})

def build():
    A = {"frm": "Alice <alice-rsa@example.test>", "to": "Bob <bob-rsa@example.test>"}
    body = "interop body with accents éè ✓"
    ent = text_entity(body)
    sign = lambda who, md="sha256", *more: sign_args(who, md, *more)

    # Detached signatures, digests and algorithms.
    for md in ("sha1", "sha256", "sha512"):
        add(f"sign-rsa-{md}", openssl_cms("-sign", ent, *sign("alice-rsa", md), **A, subject=f"sign rsa {md}"), kind="signed", from_="alice-rsa@example.test", text=body, signature="VALID_TRUSTED")
    add("sign-rsa-pss-sha256", openssl_cms("-sign", ent, *sign("alice-rsa", "sha256", "-keyopt", "rsa_padding_mode:pss"), **A, subject="rsa pss"), kind="signed", from_="alice-rsa@example.test", text=body, signature="VALID_TRUSTED")
    add("sign-ecdsa-p256-sha256", openssl_cms("-sign", ent, *sign("alice-p256", "sha256"), frm="Alice <alice-p256@example.test>", to=A["to"], subject="ecdsa p256"), kind="signed", from_="alice-p256@example.test", text=body, signature="VALID_TRUSTED")
    add("sign-ecdsa-p384-sha384", openssl_cms("-sign", ent, *sign("alice-p384", "sha384"), frm="Alice <alice-p384@example.test>", to=A["to"], subject="ecdsa p384"), kind="signed", from_="alice-p384@example.test", text=body, signature="VALID_TRUSTED")
    add("sign-with-attachment", openssl_cms("-sign", mixed_entity(body), *sign("alice-rsa"), **A, subject="attachment"), kind="signed", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"], signature="VALID_TRUSTED")

    # Legacy content types (openssl smime emits application/x-pkcs7-signature / x-pkcs7-mime).
    add("sign-legacy-x-pkcs7-signature", openssl_cms("-sign", ent, *sign("alice-rsa"), tool="smime", **A, subject="legacy signature"), kind="signed", from_="alice-rsa@example.test", text=body, signature="VALID_TRUSTED")
    add("sign-opaque-legacy-x-pkcs7-mime", openssl_cms("-sign", ent, "-nodetach", *sign("alice-rsa"), tool="smime", **A, subject="legacy opaque"), kind="opaque", from_="alice-rsa@example.test", text=body, signature="VALID_TRUSTED")
    add("sign-opaque-cms", openssl_cms("-sign", mixed_entity(body), "-nodetach", *sign("alice-rsa"), **A, subject="opaque"), kind="opaque", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"], signature="VALID_TRUSTED")

    # Transfer encodings and line endings of the carrying message.
    detached = openssl_cms("-sign", ent, *sign("alice-rsa"), **A, subject="line endings")
    add("sign-lf-only-line-endings", with_lf_only(detached), kind="signed", from_="alice-rsa@example.test", text=body, signature="VALID_TRUSTED", note="messages normalised to LF by some servers/stores; the signature is over CRLF")
    add("sign-binary-cte-signature", rewrite_cte(detached, "binary"), kind="signed", from_="alice-rsa@example.test", text=body, signature="VALID_TRUSTED")
    add("sign-quoted-printable-folded-headers", openssl_cms("-sign", qp_folded_entity("café é body"), "-binary", *sign("alice-rsa"), **A, subject="qp folded"), kind="signed", from_="alice-rsa@example.test", text="caf", signature="VALID_TRUSTED")

    # Encryption: key transport (PKCS#1 v1.5, OAEP), key agreement (ECDH), several recipients, legacy type, encodings.
    sec = mixed_entity(body)
    add("enc-rsa-pkcs1-aes256", openssl_cms("-encrypt", sec, *enc_args("bob-rsa"), **A, subject="rsa"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-rsa-aes128", openssl_cms("-encrypt", sec, *enc_args("bob-rsa", cipher="-aes128"), **A, subject="rsa aes128"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-rsa-aes192", openssl_cms("-encrypt", sec, *enc_args("bob-rsa", cipher="-aes192"), **A, subject="rsa aes192"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-rsa-oaep-sha256", openssl_cms("-encrypt", sec, *enc_args("bob-rsa", more=("-keyopt", "rsa_padding_mode:oaep", "-keyopt", "rsa_oaep_md:sha256")), **A, subject="oaep"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-rsa-oaep-sha1", openssl_cms("-encrypt", sec, *enc_args("bob-rsa", more=("-keyopt", "rsa_padding_mode:oaep")), **A, subject="oaep sha1"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-ecdh-p256-aes128", openssl_cms("-encrypt", sec, *enc_args("carol-p256", cipher="-aes128"), frm=A["frm"], to="Carol <carol-p256@example.test>", subject="ecdh p256"), kind="encrypted", decrypt_with="carol-p256", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-ecdh-p256-aes256-sha256kdf", openssl_cms("-encrypt", sec, *enc_args("carol-p256", more=("-keyopt", "ecdh_kdf_md:sha256")), frm=A["frm"], to="Carol <carol-p256@example.test>", subject="ecdh p256 kdf"), kind="encrypted", decrypt_with="carol-p256", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-ecdh-p384-aes256", openssl_cms("-encrypt", sec, *enc_args("dave-p384"), frm=A["frm"], to="Dave <dave-p384@example.test>", subject="ecdh p384"), kind="encrypted", decrypt_with="dave-p384", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-several-recipients-we-are-last", openssl_cms("-encrypt", sec, *enc_args("erin-rsa", "carol-p256", "bob-rsa"), frm=A["frm"], to="erin-rsa@example.test, carol-p256@example.test, bob-rsa@example.test", subject="three recipients"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-several-recipients-ec-not-first", openssl_cms("-encrypt", sec, *enc_args("bob-rsa", "carol-p256"), frm=A["frm"], to="bob-rsa@example.test, carol-p256@example.test", subject="rsa then ec"), kind="encrypted", decrypt_with="carol-p256", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-legacy-x-pkcs7-mime", openssl_cms("-encrypt", sec, "-aes256", KEYS / "bob-rsa.crt.pem", tool="smime", **A, subject="legacy enc"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    enc = openssl_cms("-encrypt", sec, *enc_args("bob-rsa"), **A, subject="encodings")
    add("enc-lf-only-line-endings", with_lf_only(enc), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-binary-cte", rewrite_cte(enc, "binary"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])
    add("enc-8bit-cte", rewrite_cte(enc, "8bit"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"])

    # Wrapped: signed-then-encrypted, and triple wrapped (signed(enveloped(signed(content)))).
    inner_signed = openssl_cms("-sign", mixed_entity(body), *sign("alice-rsa"), **A, subject="sign then encrypt")
    add("wrap-signed-then-encrypted", openssl_cms("-encrypt", mime_entity_of(inner_signed), *enc_args("bob-rsa"), **A, subject="sign then encrypt"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"], signature="VALID_TRUSTED")
    enveloped = openssl_cms("-encrypt", mime_entity_of(inner_signed), *enc_args("bob-rsa"), **A, subject="triple")
    add("wrap-triple-detached-outer", openssl_cms("-sign", mime_entity_of(enveloped), *sign("alice-rsa"), **A, subject="triple"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"], signature="VALID_TRUSTED")
    add("wrap-triple-opaque-outer", openssl_cms("-sign", mime_entity_of(enveloped), "-nodetach", *sign("alice-rsa"), **A, subject="triple opaque"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"], signature="VALID_TRUSTED")
    opaque_inner = openssl_cms("-sign", mixed_entity(body), "-nodetach", *sign("alice-rsa"), **A, subject="opaque then encrypt")
    add("wrap-opaque-signed-then-encrypted", openssl_cms("-encrypt", mime_entity_of(opaque_inner), *enc_args("bob-rsa"), **A, subject="opaque then encrypt"), kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, attachments=["proof.txt"], signature="VALID_TRUSTED")

def mime_entity_of(message):
    """The MIME entity (content headers + body) of an openssl output, i.e. what a client would sign/encrypt next, with CRLF."""
    head, body = split_message(message)
    keep = []
    for line in head.replace("\r\n", "\n").split("\n"):
        if line[:1] in " \t" and keep: keep[-1] += "\r\n" + line
        elif line.lower().startswith(("content-", "mime-version")): keep.append(line)
    entity = ("\r\n".join(l for l in keep if not l.lower().startswith("mime-version")) + "\r\n\r\n").encode() + body.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    return entity

# ---- gpgsm (optional) ------------------------------------------------------------------------------------------------
def gpgsm_cases():
    if not shutil.which("gpgsm"):
        print("gpgsm not installed: skipping its cases"); return
    home = pathlib.Path(tempfile.mkdtemp(prefix="gpgsm-interop-")); os.chmod(home, 0o700)
    env = dict(os.environ, GNUPGHOME=str(home))
    try:
        (home / "gpg-agent.conf").write_text("allow-loopback-pinentry\nbatch\n")
        (home / "gpgsm.conf").write_text("disable-crl-checks\nlog-file /dev/null\n")
        for who in ("alice-rsa", "bob-rsa"):
            p12 = home / f"{who}.p12"
            run("openssl", "pkcs12", "-export", "-inkey", KEYS / f"{who}.key.pem", "-in", KEYS / f"{who}.crt.pem", "-out", p12, "-passout", "pass:test")
            run("gpgsm", "--batch", "--pinentry-mode", "loopback", "--passphrase", "test", "--import", p12, env=env, timeout=30)
        listing = run("gpgsm", "--batch", "--with-colons", "--list-keys", env=env, timeout=30).stdout.decode()
        fprs = {l.split(":")[9]: None for l in listing.splitlines() if l.startswith("fpr:")}
        # Trust the self-signed test certificates so gpgsm will use them.
        with open(home / "trustlist.txt", "w") as f:
            for fpr in fprs: f.write(f"{fpr} S relax\n")
        body = "gpgsm interop body é"
        ent = text_entity(body)
        sig = run("gpgsm", "--batch", "--pinentry-mode", "loopback", "--passphrase", "test", "--detach-sign", "--local-user", "alice-rsa@example.test", "--digest-algo", "SHA256", stdin=ent, env=env, timeout=30).stdout
        b = "=_gpgsm_boundary"
        raw = (outer_headers("Alice <alice-rsa@example.test>", "Bob <bob-rsa@example.test>", "gpgsm detached",
                f"Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\"; micalg=sha-256; boundary=\"{b}\"{CRLF}") + CRLF +
               f"--{b}{CRLF}").encode() + ent.rstrip(b"\r\n") + (f"{CRLF}--{b}{CRLF}Content-Type: application/pkcs7-signature; name=\"smime.p7s\"{CRLF}Content-Transfer-Encoding: base64{CRLF}Content-Disposition: attachment; filename=\"smime.p7s\"{CRLF}{CRLF}").encode() + \
               base64.encodebytes(sig).replace(b"\n", b"\r\n") + f"--{b}--{CRLF}".encode()
        add("gpgsm-sign-detached", raw, kind="signed", from_="alice-rsa@example.test", text=body, signature="VALID_TRUSTED", source="gpgsm")
        cms = run("gpgsm", "--batch", "--pinentry-mode", "loopback", "--passphrase", "test", "--encrypt", "--recipient", "bob-rsa@example.test", "--local-user", "alice-rsa@example.test", stdin=ent, env=env, timeout=30).stdout
        raw = (outer_headers("Alice <alice-rsa@example.test>", "Bob <bob-rsa@example.test>", "gpgsm encrypted",
                f"Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"{CRLF}Content-Transfer-Encoding: base64{CRLF}Content-Disposition: attachment; filename=\"smime.p7m\"{CRLF}") + CRLF).encode() + base64.encodebytes(cms).replace(b"\n", b"\r\n")
        add("gpgsm-encrypt", raw, kind="encrypted", decrypt_with="bob-rsa", from_="alice-rsa@example.test", text=body, source="gpgsm")
    except (SystemExit, Exception) as e:  # includes subprocess.TimeoutExpired: gpgsm is a best-effort second implementation
        print("gpgsm cases skipped:", str(e).splitlines()[0] if str(e) else e)
    finally:
        subprocess.run(["gpgconf", "--kill", "all"], env=env, capture_output=True)
        shutil.rmtree(home, ignore_errors=True)

def main():
    shutil.rmtree(OUT, ignore_errors=True)
    KEYS.mkdir(parents=True); CASES.mkdir(parents=True)
    for name, spec in IDENTITIES.items(): make_identity(name, *spec)
    build()
    gpgsm_cases()
    versions = {"openssl": run("openssl", "version").stdout.decode().strip()}
    (OUT / "manifest.json").write_text(json.dumps({"generator": "scripts/smime-interop/generate.py", "tools": versions, "cases": cases}, indent=1, ensure_ascii=False))
    print(f"{len(cases)} cases -> {OUT.relative_to(ROOT)}")

if __name__ == "__main__":
    main()
