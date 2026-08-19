FROM golang:1.23-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=1 go test ./... && CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/go-parser ./cmd/server

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates wget \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system parser && useradd --system --gid parser --no-create-home parser
COPY --from=build /out/go-parser /usr/local/bin/go-parser
USER parser
EXPOSE 8100
ENTRYPOINT ["/usr/local/bin/go-parser"]
