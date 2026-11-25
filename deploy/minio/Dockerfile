# syntax=docker/dockerfile:1
FROM golang:1.24.8-alpine AS build
ENV CGO_ENABLED=0 GOMAXPROCS=2 GOFLAGS=-p=2 GOTOOLCHAIN=local GOPROXY=https://goproxy.cn,direct
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go install github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go install github.com/minio/mc@RELEASE.2025-08-13T08-35-41Z && \
    mkdir /licenses && \
    cp "$(go list -m -f '{{.Dir}}' github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z)/LICENSE" /licenses/minio-LICENSE && \
    cp "$(go list -m -f '{{.Dir}}' github.com/minio/mc@RELEASE.2025-08-13T08-35-41Z)/LICENSE" /licenses/mc-LICENSE

FROM alpine:3.22
RUN apk add --no-cache ca-certificates && addgroup -g 1000 storage && adduser -D -u 1000 -G storage storage
COPY --from=build /go/bin/minio /go/bin/mc /usr/local/bin/
COPY --from=build /licenses /licenses
USER 1000:1000
ENV HOME=/tmp
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
CMD ["server", "/data", "--console-address", ":9001"]
