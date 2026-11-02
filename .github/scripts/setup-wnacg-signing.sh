#!/usr/bin/env bash
# Create and register ONE persistent WNACG signing key for all subsequent GitHub Actions builds.
set -euo pipefail
umask 077

repo="pulipulichen/extensions-source"
key_dir="${WNACG_SIGNING_DIR:-$HOME/.local/share/wnacg-signing}"
key_file="$key_dir/signingkey.jks"
password_file="$key_dir/keystore-password"
alias="wnacg-release"

for command in gh openssl keytool base64; do
  if ! command -v "$command" >/dev/null 2>&1; then
    printf 'Missing dependency: %s\n' "$command" >&2
    exit 1
  fi
done

if ! gh auth status --hostname github.com >/dev/null 2>&1; then
  echo 'Please log in first with: gh auth login' >&2
  exit 1
fi

mkdir -p "$key_dir"
chmod 700 "$key_dir"

if [[ -e "$key_file" || -e "$password_file" ]]; then
  if [[ ! -s "$key_file" || ! -s "$password_file" ]]; then
    echo "Incomplete signing key backup under $key_dir. Do not overwrite it." >&2
    exit 1
  fi
  echo "Reusing existing signing certificate from $key_file"
else
  password="$(openssl rand -hex 24)"
  # The same strong password is used for both keystore and private key.
  keytool -genkeypair \
    -alias "$alias" \
    -keyalg RSA -keysize 3072 -validity 10000 \
    -keystore "$key_file" -storetype JKS \
    -storepass "$password" -keypass "$password" \
    -dname "CN=WNACG Personal Extension, OU=Android, O=Personal, C=TW" \
    -noprompt
  printf '%s\n' "$password" > "$password_file"
  echo "Created persistent signing certificate under $key_dir"
fi

password="$(<"$password_file")"
keytool -list -keystore "$key_file" -storepass "$password" -alias "$alias" > /dev/null

# Upload secrets securely using stdin; never echo private key/password in logs.
base64 -w 0 "$key_file" | gh secret set SIGNING_KEY --repo "$repo"
printf '%s' "$alias" | gh secret set ALIAS --repo "$repo"
printf '%s' "$password" | gh secret set KEY_STORE_PASSWORD --repo "$repo"
printf '%s' "$password" | gh secret set KEY_PASSWORD --repo "$repo"

echo
echo "Done. The same certificate will sign every WNACG release/test build."
echo "Back up BOTH $key_file and $password_file securely."
echo "Do not delete them or create a replacement certificate for future updates."
echo "Certificate fingerprint:"
keytool -list -v -keystore "$key_file" -storepass "$password" -alias "$alias" \
  | grep 'SHA256:' | head -n 1
