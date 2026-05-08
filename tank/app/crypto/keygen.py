"""CLI: python -m app.crypto.keygen — generate Ed25519 key pair."""
from .signing import get_signing_key, public_key_b64

if __name__ == "__main__":
    get_signing_key()
    print("Generated/loaded Ed25519 key pair.")
    print(f"Public key (base64): {public_key_b64()}")
