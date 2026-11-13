FROM python:3.14-slim

# No build toolchain needed: every dependency ships pure-Python wheels (see
# requirements.txt), which keeps arm64 builds on the Pi fast and boring.
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

ENV CRASHLOG_DATA=/data
ENV PYTHONPATH=/srv/app
VOLUME ["/data"]
EXPOSE 9140

# Single worker on purpose: SQLite plus a bind-mounted directory, and the load is a
# handful of reports a day. Concurrency here would buy nothing and risk write races.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9140", "--workers", "1"]
