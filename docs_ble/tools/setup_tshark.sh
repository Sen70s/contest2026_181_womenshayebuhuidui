#!/usr/bin/env bash
# Install tshark into a private prefix without root. The dev box has no
# tshark package installed, sudo needs a password, and github is
# unreachable -- but apt-get download and the Ubuntu mirror both work.
set -Eeuo pipefail

PREFIX="${TSHARK_PREFIX:-$HOME/.local/tshark}"
PKGS=(tshark wireshark-common libwireshark15 libwireshark-data
      libwiretap12 libwsutil13 libsmi2ldbl liblua5.2-0 libspandsp2
      libssh-gcrypt-4 libc-ares2 libsnappy1v5 libnl-route-3-200
      libmaxminddb0 libbrotli1 libgcrypt20 libgnutls30)

mkdir -p "$PREFIX/debs" "$PREFIX/root"
cd "$PREFIX/debs"
apt-get download "${PKGS[@]}"
cd "$PREFIX"
for d in debs/*.deb; do dpkg -x "$d" root/; done

cat > "$PREFIX/tshark" <<EOF
#!/usr/bin/env bash
export LD_LIBRARY_PATH="$PREFIX/root/usr/lib/x86_64-linux-gnu"
exec "$PREFIX/root/usr/bin/tshark" "\$@"
EOF
chmod +x "$PREFIX/tshark"

"$PREFIX/tshark" --version | head -1
"$PREFIX/tshark" -G protocols | grep -q btbnep \
  && echo "btbnep dissector: OK" || { echo "btbnep MISSING"; exit 1; }
