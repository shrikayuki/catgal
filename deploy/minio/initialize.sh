#!/bin/sh
set -eu
umask 077
: "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}" "${MINIO_APP_ACCESS_KEY:?}" "${MINIO_APP_SECRET_KEY:?}" "${MINIO_BUCKET:?}"
case "$MINIO_BUCKET" in catgal-media) ;; *) echo 'Unexpected managed bucket.' >&2; exit 1 ;; esac
export MC_CONFIG_DIR=/tmp/catgal-mc
mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing "local/$MINIO_BUCKET" >/dev/null
if ! mc admin user info local "$MINIO_APP_ACCESS_KEY" >/dev/null 2>&1; then
    mc admin user add local "$MINIO_APP_ACCESS_KEY" "$MINIO_APP_SECRET_KEY" >/dev/null
fi
cat >/tmp/media-policy.json <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:GetBucketLocation"],"Resource":["arn:aws:s3:::$MINIO_BUCKET"]},
 {"Effect":"Allow","Action":["s3:PutObject","s3:GetObject","s3:DeleteObject"],"Resource":[
  "arn:aws:s3:::$MINIO_BUCKET/v1/avatars/*",
  "arn:aws:s3:::$MINIO_BUCKET/v1/game-covers/*",
  "arn:aws:s3:::$MINIO_BUCKET/v1/ai-bot-avatars/*",
  "arn:aws:s3:::$MINIO_BUCKET/v1/ai-bot-portraits/*"
 ]}
]}
EOF
mc admin policy create local catgal-media-app /tmp/media-policy.json >/dev/null
mc admin policy attach local catgal-media-app --user "$MINIO_APP_ACCESS_KEY" >/dev/null
mc anonymous set none "local/$MINIO_BUCKET" >/dev/null
echo 'Private bucket and scoped application account ready.'
