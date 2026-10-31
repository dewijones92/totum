FROM eclipse-temurin:21-jre

ARG TOTUM_VERSION
ARG CLAUDE_CODE_VERSION=2.1.316

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates nodejs npm ffmpeg python3 python3-venv \
 && rm -rf /var/lib/apt/lists/* \
 && npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
 && npm cache clean --force

RUN test -n "$TOTUM_VERSION" \
 && cd /tmp \
 && curl -fsSLO "https://github.com/dewijones92/totum/releases/download/v${TOTUM_VERSION}/totum-cli.tar.gz" \
 && curl -fsSLO "https://github.com/dewijones92/totum/releases/download/v${TOTUM_VERSION}/totum-cli.tar.gz.sha256" \
 && sha256sum -c totum-cli.tar.gz.sha256 \
 && tar -xzf totum-cli.tar.gz -C /opt \
 && rm totum-cli.tar.gz totum-cli.tar.gz.sha256

RUN (userdel --remove ubuntu 2>/dev/null || true) \
 && useradd --create-home --uid 1000 totum \
 && python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir "yt-dlp[default]" \
 && chown -R totum:totum /opt/venv

USER totum
ENV HOME=/home/totum \
    TOTUM_PYTHON=/opt/venv/bin/python \
    TOTUM_JS_RUNTIME=node:/usr/bin/node \
    TOTUM_DATA=/home/totum/.totum \
    TOTUM_DOWNLOADS=/media/totum \
    TOTUM_LIBRARY_MOVIES=/media/videos/movies \
    TOTUM_LIBRARY_SERIES=/media/videos/series
EXPOSE 8770
CMD ["sh", "-c", "/opt/venv/bin/pip install -q -U 'yt-dlp[default]' || echo 'yt-dlp update failed; using the installed one'; exec /opt/totum/bin/totum serve --port=8770"]
