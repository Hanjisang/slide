FROM golang:1.23-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=1 go test ./... \
    && CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/go-parser ./cmd/server \
    && CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/verify-slide ./cmd/verify-slide

FROM debian:bookworm-slim
RUN apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends ca-certificates ffmpeg libstdc++6 wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system parser \
    && useradd --system --gid parser --no-create-home parser
COPY --from=build /out/go-parser /usr/local/bin/go-parser
COPY --from=build /out/verify-slide /usr/local/bin/verify-slide
USER parser
EXPOSE 8100
ENTRYPOINT ["/usr/local/bin/go-parser"]
