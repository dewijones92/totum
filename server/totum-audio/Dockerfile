# Audio-only HLS for TorrServer streams. Python stdlib plus ffmpeg — nothing else, because the
# whole job is "run ffmpeg and serve a directory".
FROM alpine:3.24
RUN apk add --no-cache python3 ffmpeg
COPY server.py /app/server.py
# tmpfs-backed in compose: segments are transient and the Pi's disk is 88% full.
ENV WORK_DIR=/tmp/totum-audio
EXPOSE 8099
CMD ["python3", "-u", "/app/server.py"]
