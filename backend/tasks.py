from backend.celery_app import celery_app
from backend.app import monitor_sources_job

@celery_app.task(name="backend.tasks.monitor_official_sources")
def monitor_official_sources():
    return monitor_sources_job()
