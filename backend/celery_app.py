"""Optional Celery worker and scheduler for unattended source monitoring."""
import os
from pathlib import Path
try:
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().parents[1] / ".env")
except ImportError:
    pass
from celery import Celery

broker = os.getenv("CELERY_BROKER_URL", os.getenv("REDIS_URL", "redis://redis:6379/0"))
celery_app = Celery("nexora_next", broker=broker, backend=os.getenv("CELERY_RESULT_BACKEND", broker), include=["backend.tasks"])
celery_app.conf.update(
    timezone="UTC",
    enable_utc=True,
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    beat_schedule={
        "daily-official-source-monitor": {
            "task": "backend.tasks.monitor_official_sources",
            "schedule": 24 * 60 * 60,
        }
    },
)
