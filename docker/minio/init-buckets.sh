#!/bin/sh
set -eu

mc alias set local http://minio:9000 "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}"
mc mb --ignore-existing "local/${MINIO_DOCUMENT_BUCKET}"
mc mb --ignore-existing "local/${MINIO_PUBLIC_IMAGE_BUCKET}"

# 图片桶用于前端直接读取；文档桶保持私有，文档由 Java 生成预签名 URL。
mc anonymous set download "local/${MINIO_PUBLIC_IMAGE_BUCKET}"
