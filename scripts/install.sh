#!/usr/bin/env bash
# One-line installer for ofd-cli.
#
# Usage:
#   curl -fsSL https://github.com/rightgenius/ofd-cli/releases/latest/download/install.sh | sh
#
# What it does:
#   1. Detect the host OS and architecture.
#   2. Download the matching native binary from the latest GitHub release.
#   3. Verify the SHA-256 checksum against the published SHA256SUMS file.
#   4. Install to /usr/local/bin (or ~/.local/bin if no sudo).
#   5. Print a final `ofd --version` so the user can confirm the install.
#
# Set OFD_INSTALL_DIR to override the install location. Set OFD_VERSION
# to pin a specific release (default: latest).

set -euo pipefail

REPO="rightgenius/ofd-cli"
BINARY_NAME="ofd"

# ---------------------------------------------------------------------------
# 1. Detect platform
# ---------------------------------------------------------------------------

detect_platform() {
    local os arch
    case "$(uname -s)" in
        Linux*)  os="linux" ;;
        Darwin*) os="darwin" ;;
        MINGW*|MSYS*|CYGWIN*)
            echo "Windows detected. Run this in WSL or download the .exe manually from:" >&2
            echo "  https://github.com/${REPO}/releases/latest" >&2
            exit 1
            ;;
        *)
            echo "Unsupported OS: $(uname -s)" >&2
            exit 1
            ;;
    esac

    case "$(uname -m)" in
        x86_64|amd64)  arch="x64" ;;
        arm64|aarch64) arch="arm64" ;;
        *)
            echo "Unsupported architecture: $(uname -m)" >&2
            exit 1
            ;;
    esac

    echo "${os}-${arch}"
}

PLATFORM=$(detect_platform)
ASSET_NAME="ofd-${PLATFORM}"
echo "→ Detected platform: ${PLATFORM}"

# ---------------------------------------------------------------------------
# 2. Choose version
# ---------------------------------------------------------------------------

if [[ -z "${OFD_VERSION:-}" ]]; then
    OFD_VERSION=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
        | grep -oE '"tag_name": *"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
    if [[ -z "${OFD_VERSION}" ]]; then
        echo "Could not determine latest version. Set OFD_VERSION=v0.1.0 to pin one." >&2
        exit 1
    fi
fi
echo "→ Using version: ${OFD_VERSION}"

# ---------------------------------------------------------------------------
# 3. Download binary + checksum
# ---------------------------------------------------------------------------

BASE_URL="https://github.com/${REPO}/releases/download/${OFD_VERSION}"
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

echo "→ Downloading ${ASSET_NAME}..."
curl -fsSL -o "${TMPDIR}/${BINARY_NAME}" "${BASE_URL}/${ASSET_NAME}"

echo "→ Downloading SHA256SUMS..."
curl -fsSL -o "${TMPDIR}/SHA256SUMS" "${BASE_URL}/SHA256SUMS"

# ---------------------------------------------------------------------------
# 4. Verify checksum
# ---------------------------------------------------------------------------

EXPECTED=$(grep -E "[[:space:]]${ASSET_NAME}\$" "${TMPDIR}/SHA256SUMS" | awk '{print $1}')
if [[ -z "${EXPECTED}" ]]; then
    echo "No checksum found for ${ASSET_NAME} in SHA256SUMS." >&2
    echo "File contents:" >&2
    cat "${TMPDIR}/SHA256SUMS" >&2
    exit 1
fi

ACTUAL=$(sha256sum "${TMPDIR}/${BINARY_NAME}" | awk '{print $1}')
if [[ "${EXPECTED}" != "${ACTUAL}" ]]; then
    echo "Checksum mismatch!" >&2
    echo "  expected: ${EXPECTED}" >&2
    echo "  actual:   ${ACTUAL}" >&2
    exit 1
fi
echo "→ Checksum OK"

# ---------------------------------------------------------------------------
# 4b. Download + verify native dylibs (libawt.dylib etc.) that the binary
#     loads at runtime via System.loadLibrary("awt"). The macOS/Linux loader
#     only finds them in the binary's own directory, so they must be
#     co-located with `ofd`. Without them, image-rendering subcommands
#     (to-png and to-pdf on OFDs with embedded images) crash with
#     "UnsatisfiedLinkError: Can't load library: awt".
# ---------------------------------------------------------------------------

DYLIB_PATTERN='^lib[a-zA-Z][a-zA-Z0-9_]*\.(dylib|so)$'
DYLIB_NAMES=$(grep -E "${DYLIB_PATTERN}" "${TMPDIR}/SHA256SUMS" | awk '{print $2}')

if [[ -z "${DYLIB_NAMES}" ]]; then
    echo "WARN: no dylibs found in SHA256SUMS — image-rendering subcommands" \
         "will fail at runtime. (Only ${ASSET_NAME} is platform-specific" \
         "to this download; the dylib list is small.)" >&2
else
    echo "→ Downloading $(echo "${DYLIB_NAMES}" | wc -l | tr -d ' ') dylib(s)..."
    for dylib in ${DYLIB_NAMES}; do
        curl -fsSL -o "${TMPDIR}/${dylib}" "${BASE_URL}/${dylib}"
        # Verify each dylib against SHA256SUMS
        EXPECTED=$(grep -E "[[:space:]]${dylib}\$" "${TMPDIR}/SHA256SUMS" | awk '{print $1}')
        ACTUAL=$(sha256sum "${TMPDIR}/${dylib}" | awk '{print $1}')
        if [[ "${EXPECTED}" != "${ACTUAL}" ]]; then
            echo "Dylib checksum mismatch for ${dylib}!" >&2
            echo "  expected: ${EXPECTED}" >&2
            echo "  actual:   ${ACTUAL}" >&2
            exit 1
        fi
    done
    echo "→ Dylib checksums OK"
fi

# ---------------------------------------------------------------------------
# 5. Install
# ---------------------------------------------------------------------------

chmod +x "${TMPDIR}/${BINARY_NAME}"

if [[ -n "${OFD_INSTALL_DIR:-}" ]]; then
    DEST="${OFD_INSTALL_DIR}"
    mkdir -p "${DEST}"
    mv "${TMPDIR}/${BINARY_NAME}" "${DEST}/${BINARY_NAME}"
elif [[ -w /usr/local/bin ]]; then
    mv "${TMPDIR}/${BINARY_NAME}" "/usr/local/bin/${BINARY_NAME}"
    DEST="/usr/local/bin"
else
    # User-local fallback (XDG-spec). Works without sudo.
    DEST="${HOME}/.local/bin"
    mkdir -p "${DEST}"
    mv "${TMPDIR}/${BINARY_NAME}" "${DEST}/${BINARY_NAME}"
    if ! command -v ofd >/dev/null 2>&1; then
        echo ""
        echo "NOTE: ${DEST} is not on your PATH. Add this to your shell rc:"
        echo "  export PATH=\"\${HOME}/.local/bin:\${PATH}\""
    fi
fi

echo "→ Installed to ${DEST}/${BINARY_NAME}"

# Install dylibs alongside the binary (co-location is required for
# System.loadLibrary to find them at runtime).
if [[ -n "${DYLIB_NAMES:-}" ]]; then
    for dylib in ${DYLIB_NAMES}; do
        if [[ -f "${TMPDIR}/${dylib}" ]]; then
            mv "${TMPDIR}/${dylib}" "${DEST}/${dylib}"
        fi
    done
    echo "→ Installed $(echo "${DYLIB_NAMES}" | wc -l | tr -d ' ') dylib(s) to ${DEST}/"
fi

# ---------------------------------------------------------------------------
# 6. Verify
# ---------------------------------------------------------------------------

if command -v ofd >/dev/null 2>&1; then
    echo ""
    echo "✅ ofd-cli installed successfully."
    "${DEST}/${BINARY_NAME}" --version
else
    echo ""
    echo "✅ Binary at ${DEST}/${BINARY_NAME}, but not on PATH."
    echo "Run it directly: ${DEST}/${BINARY_NAME} --version"
fi
