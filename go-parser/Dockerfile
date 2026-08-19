FROM golang:1.23-bookworm AS build
WORKDIR /src
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN mkdir -p /out/vendor/hwp /out/vendor/tron \
    && unzip -q hwp.zip -d /tmp/hwp-sdk \
    && unzip -q tron.zip -d /tmp/tron-sdk \
    && install -m 0755 /tmp/hwp-sdk/hwp/linux/libhwp_sdk.so /out/vendor/hwp/libhwp_sdk.so \
    && install -m 0755 /tmp/tron-sdk/tron/linux/libtronc.so /out/vendor/tron/libtronc.so
RUN CGO_ENABLED=1 go test ./... && CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/go-parser ./cmd/server

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates wget libstdc++6 libgcc-s1 \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system parser && useradd --system --gid parser --no-create-home parser
COPY --from=build /out/go-parser /usr/local/bin/go-parser
COPY --from=build /out/vendor /opt/vendor
USER parser
EXPOSE 8100
ENTRYPOINT ["/usr/local/bin/go-parser"]
