FROM 10.25.13.206:5000/devops/golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/go-parser ./cmd/server

FROM 10.25.13.206:5000/devops/alpine:3.22
RUN addgroup -S parser && adduser -S -G parser parser
COPY --from=build /out/go-parser /usr/local/bin/go-parser
USER parser
EXPOSE 8100
ENTRYPOINT ["/usr/local/bin/go-parser"]
