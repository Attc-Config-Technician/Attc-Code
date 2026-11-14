#!/usr/bin/env bash
# Generates the local CA and the leaf certificate used by the in-guest
# IP-echo interceptor (IpEcho.java), and writes the PKCS#12 bundle into
# app/assets/tls/.
#
# The CA is only added to the guest Ubuntu trust store (never to Android's),
# so it can only affect programs the app runs inside its own rootfs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/assets/tls"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"

# Hosts the interceptor answers for: single source of truth is VpnState.java.
HOSTS=($(python3 - "$ROOT/app/src/com/attc/opencode/VpnState.java" <<'PYEOF'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
block = src[src.index('IP_ECHO_HOSTS = {'):]
block = block[:block.index('};')]
print("\n".join(re.findall(r'"([^"]+)"', block.split('{', 1)[1])))
PYEOF
))

SAN=""
for h in "${HOSTS[@]}"; do
  SAN+="DNS:$h,DNS:www.$h,"
done
SAN="${SAN%,}"

PASS="attcattc"

openssl req -x509 -newkey rsa:2048 -sha256 -nodes \
  -keyout "$TMP/ca.key" -out "$TMP/ca.pem" -days 3650 \
  -subj "/O=ATTC/CN=ATTC Local IP Echo CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1

openssl req -newkey rsa:2048 -sha256 -nodes \
  -keyout "$TMP/leaf.key" -out "$TMP/leaf.csr" \
  -subj "/O=ATTC/CN=attc-ip-echo" >/dev/null 2>&1

cat > "$TMP/leaf.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=$SAN
EOF

openssl x509 -req -in "$TMP/leaf.csr" -CA "$TMP/ca.pem" -CAkey "$TMP/ca.key" \
  -CAcreateserial -days 3650 -sha256 -extfile "$TMP/leaf.ext" \
  -out "$TMP/leaf.pem" >/dev/null 2>&1

openssl pkcs12 -export -in "$TMP/leaf.pem" -inkey "$TMP/leaf.key" \
  -certfile "$TMP/ca.pem" -name attc-ip-echo -out "$OUT/attc-ipecho.p12" \
  -passout "pass:$PASS"

cp "$TMP/ca.pem" "$OUT/attc-ipecho-ca.pem"

echo "wrote $OUT/attc-ipecho.p12 ($(wc -c < "$OUT/attc-ipecho.p12") bytes)"
echo "wrote $OUT/attc-ipecho-ca.pem ($(wc -c < "$OUT/attc-ipecho-ca.pem") bytes)"
echo "SAN count: ${#HOSTS[@]} hosts"
