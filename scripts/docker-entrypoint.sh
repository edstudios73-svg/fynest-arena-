#!/bin/sh
# Container entrypoint. If started as root (e.g. a mounted persistent disk owned by root),
# make the data directory writable for the app user, then drop privileges: the bundled
# PostgreSQL refuses to run as root.
set -e
if [ "$(id -u)" = "0" ]; then
  if [ -n "$SMARTATTEND_DATA_DIR" ]; then
    mkdir -p "$SMARTATTEND_DATA_DIR"
    chown -R smartattend:smartattend "$SMARTATTEND_DATA_DIR"
  fi
  exec setpriv --reuid=smartattend --regid=smartattend --init-groups java $JAVA_OPTS -jar /app/app.jar
fi
exec java $JAVA_OPTS -jar /app/app.jar
