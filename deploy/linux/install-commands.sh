#!/usr/bin/env bash
set -euo pipefail

APP_DIR=/home/prpsdc/zszj/jpwise
SERVICE_NAME=crawler

sudo mkdir -p "${APP_DIR}/logs"
sudo chown -R prpsdc:prpsdc "${APP_DIR}"

# Run these copy commands from the directory that contains the packaged jar
# and the generated linux deployment files.
cp crawler-service-1.0.0.jar "${APP_DIR}/"
cp application.yml "${APP_DIR}/"
cp application-mysql.yml "${APP_DIR}/"
cp crawler.sh "${APP_DIR}/"
chmod +x "${APP_DIR}/crawler.sh"

sudo cp crawler.service "/etc/systemd/system/${SERVICE_NAME}.service"
sudo systemctl daemon-reload
sudo systemctl enable "${SERVICE_NAME}"

echo "Start:   sudo service ${SERVICE_NAME} start"
echo "Status:  sudo service ${SERVICE_NAME} status"
echo "Logs:    tail -f ${APP_DIR}/logs/crawler-service.log"
