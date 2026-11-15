# Hardened multi-stage build. Synthetic example.
FROM golang:1.22.5-alpine AS build
WORKDIR /src
COPY . .
RUN go build -o /out/app ./cmd/app

FROM gcr.io/distroless/static@sha256:0000000000000000000000000000000000000000000000000000000000000000
COPY --from=build /out/app /app
USER 65532:65532
HEALTHCHECK --interval=30s CMD ["/app", "-health"]
ENTRYPOINT ["/app"]
