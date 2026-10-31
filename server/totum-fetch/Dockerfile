FROM eclipse-temurin:21-jre

ARG TOTUM_VERSION
ARG CLAUDE_CODE_VERSION=2.1.316

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates nodejs npm \
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

RUN useradd --create-home --uid 10870 totum
USER totum
ENV HOME=/home/totum
EXPOSE 8770
CMD ["/opt/totum/bin/totum", "serve", "--port=8770"]
