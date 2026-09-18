#!/usr/bin/env bash

APP_DIR=/home/prpsdc/zszj/jpwise
LOG_DIR="${APP_DIR}/logs"
CONSOLE_LOG="${LOG_DIR}/crawler-console.log"
PID_FILE="${APP_DIR}/crawler.pid"
JAVA_BIN=/home/prpsdc/zszj/jdk17/bin/java
JAR_FILE="${APP_DIR}/crawler-service-1.0.0.jar"
APP_CONFIG="${APP_DIR}/application.yml"
MYSQL_CONFIG="${APP_DIR}/application-mysql.yml"

mkdir -p "${LOG_DIR}"

{
  echo "[$(date '+%F %T')] starting crawler-service"

  if [ ! -x "${JAVA_BIN}" ]; then
    echo "ERROR: java executable not found: ${JAVA_BIN}"
    echo "Check with: ls -l ${JAVA_BIN}"
    exit 127
  fi

  if [ ! -f "${JAR_FILE}" ]; then
    echo "ERROR: jar not found: ${JAR_FILE}"
    exit 127
  fi

  if [ ! -f "${APP_CONFIG}" ]; then
    echo "ERROR: application config not found: ${APP_CONFIG}"
    exit 127
  fi

  if [ ! -f "${MYSQL_CONFIG}" ]; then
    echo "ERROR: mysql config not found: ${MYSQL_CONFIG}"
    exit 127
  fi

  if [ -f "${PID_FILE}" ]; then
    OLD_PID="$(cat "${PID_FILE}")"
    if [ -n "${OLD_PID}" ] && kill -0 "${OLD_PID}" 2>/dev/null; then
      echo "crawler-service is already running, pid=${OLD_PID}"
      exit 0
    fi
    rm -f "${PID_FILE}"
  fi
} >> "${CONSOLE_LOG}" 2>&1

cd "${APP_DIR}" || exit 1

nohup "${JAVA_BIN}" \
  -Xms256m \
  -Xmx512m \
  -Duser.timezone=Asia/Shanghai \
  -Dspring.config.location="${APP_CONFIG},${MYSQL_CONFIG}" \
  -jar "${JAR_FILE}" \
  >> "${CONSOLE_LOG}" 2>&1 &

echo $! > "${PID_FILE}"
echo "[$(date '+%F %T')] crawler-service started, pid=$(cat "${PID_FILE}")" >> "${CONSOLE_LOG}"
