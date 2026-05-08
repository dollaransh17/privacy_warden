from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    tank_host: str = "0.0.0.0"
    tank_port: int = 8443
    tank_db_path: str = "./data/warden.db"
    tank_db_key: str = "dev-key"
    redis_url: str = "redis://localhost:6379/0"

    tank_private_key_path: str = "./keys/tank_ed25519.key"
    tank_public_key_path: str = "./keys/tank_ed25519.pub"

    wa_token: str = ""
    wa_phone_number_id: str = ""
    wa_verify_token: str = "privacy-warden-verify"
    wa_recipient: str = ""

    llm_model_path: str = "./models/phi-3.5-mini-q4.gguf"
    llm_threads: int = 4


settings = Settings()
