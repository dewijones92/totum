# syntax=docker/dockerfile:1.28
FROM node:24-bookworm-slim AS build
WORKDIR /src
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/server/package.json apps/server/
COPY apps/web/package.json apps/web/
COPY packages/shared/package.json packages/shared/
RUN --mount=type=cache,id=pnpm,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY . .
RUN pnpm --filter @aerarium/server --filter @aerarium/web build

FROM node:24-alpine AS runtime
RUN apk add --no-cache tini
WORKDIR /app
COPY --from=build /src/apps/server/dist/server.mjs ./server/server.mjs
COPY --from=build /src/apps/web/dist ./web
ARG APP_VERSION=dev
ENV NODE_ENV=production \
    APP_VERSION=${APP_VERSION} \
    PORT=8080 \
    HOST=0.0.0.0 \
    STATIC_DIR=/app/web \
    DATA_DIR=/data
RUN mkdir -p /data && chown node:node /data
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "--disable-warning=ExperimentalWarning", "/app/server/server.mjs"]
