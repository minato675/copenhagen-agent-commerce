# Import/export only Gradle caches, never the source tree or application images.
# The cache mount ID matches the existing service Dockerfiles' default ID.
FROM docker.io/library/alpine:3.22 AS seed
ARG SMOKE_CACHE_RUN
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=bind,target=/seed \
    test -n "$SMOKE_CACHE_RUN" && \
    for directory in caches wrapper; do \
        if [ -d "/seed/$directory" ]; then cp -an "/seed/$directory" /root/.gradle/; fi; \
    done

FROM docker.io/library/alpine:3.22 AS collect
ARG SMOKE_CACHE_RUN
RUN --mount=type=cache,target=/root/.gradle \
    test -n "$SMOKE_CACHE_RUN" && mkdir /export && \
    for directory in caches wrapper; do \
        if [ -d "/root/.gradle/$directory" ]; then cp -a "/root/.gradle/$directory" /export/; fi; \
    done && \
    find /export -name '*.lock' -type f -delete

FROM scratch AS export
COPY --from=collect /export/ /
