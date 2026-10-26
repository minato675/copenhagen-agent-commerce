#!/usr/bin/env python3
"""Share a Gradle build layer without changing any service's runtime stage.

Only the established source-builder shape is eligible. Unknown Dockerfiles are
copied unchanged, so a future custom build still runs its original instructions.
The generated files live outside the source context and are never committed.
"""
from collections import defaultdict
from pathlib import Path
import json
import re
import sys

MODULES = ('mocks/pg-mock', 'apps/pay-service', 'mocks/ucp-merchant-mock',
           'apps/core-service', 'apps/mcp-server')


def prepare(source, output, image_prefix='smoke', tag='candidate'):
    output.mkdir(parents=True, exist_ok=True)
    groups = defaultdict(list)
    originals = {}
    default_ignore = source / 'backend/.dockerignore'
    default_ignore = default_ignore.read_bytes() if default_ignore.exists() else b''
    for module in MODULES:
        dockerfile = source / 'backend' / module / 'Dockerfile'
        original = dockerfile.read_bytes().decode('utf-8')
        originals[module] = original
        specific_ignore = dockerfile.with_name('Dockerfile.dockerignore')
        if specific_ignore.exists():
            ignore = specific_ignore.read_bytes()
            (output / (module.split('/')[-1] + '.Dockerfile.dockerignore')).write_bytes(ignore)
            if ignore != default_ignore:
                # A restricted context may intentionally exclude other modules.
                continue
        task = ':' + module.replace('/', ':') + ':bootJar'
        # Deliberately strict: do not rewrite arbitrary shell or build steps.
        pattern = (
            r'\AFROM (?P<jdk>\S+) AS builder\nWORKDIR /workspace\nCOPY \. \.\n'
            r'(?:ARG GRADLE_MAX_WORKERS\n)?'
            r'RUN --mount=type=cache,target=/root/\.gradle \\\n'
            r'(?:    set --; \\\n'
            r'    if \[ -n "\$GRADLE_MAX_WORKERS" \]; then set -- "--max-workers=\$GRADLE_MAX_WORKERS"; fi; \\\n)?'
            r'    chmod \+x gradlew && \./gradlew (?:"\$@" )?' + re.escape(task)
            + r' --no-daemon\n(?=\nFROM )'
        )
        match = re.match(pattern, original)
        if match:
            prefix = match.group().replace(task, '{tasks}')
            groups[prefix].append((module, match.end()))

    generated = dict(originals)
    for prefix, members in groups.items():
        if len(members) < 2:
            continue
        tasks = ' '.join(':' + module.replace('/', ':') + ':bootJar'
                         for module, _ in members)
        shared_prefix = prefix.replace('{tasks}', tasks)
        if prefix.startswith('FROM docker.io/library/eclipse-temurin:21-jdk-alpine AS builder\n'):
            # This short-lived HotSpot JVM benefits from the same startup tuning
            # as the smoke apps. Preserve any options provided by the base image.
            shared_prefix = shared_prefix.replace(
                ' && ./gradlew ',
                ' && JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:TieredStopAtLevel=1" ./gradlew ')
        for module, end in members:
            generated[module] = shared_prefix + originals[module][end:]
        print('BUILD shared-gradle services=' + ','.join(module.split('/')[-1]
                                                       for module, _ in members))

    for module in MODULES:
        service = module.split('/')[-1]
        (output / (service + '.Dockerfile')).write_bytes(generated[module].encode('utf-8'))
        if generated[module] == originals[module]:
            print('BUILD original-dockerfile service=' + service)
    targets = {module.split('/')[-1]: {
        'context': str((source / 'backend').resolve()),
        'dockerfile': str((output / (module.split('/')[-1] + '.Dockerfile')).resolve()),
        'tags': [image_prefix + '/' + module.split('/')[-1] + ':' + tag],
    } for module in MODULES}
    (output / 'bake.json').write_text(json.dumps({
        'group': {'default': {'targets': list(targets)}}, 'target': targets,
    }))
    base_images = {match.group(1) for original in originals.values()
                   for match in re.finditer(r'^FROM (\S+)', original, re.M)
                   if '/' in match.group(1) and '$' not in match.group(1)
                   and not match.group(1).startswith('--')}
    (output / 'base-images.txt').write_text('\n'.join(sorted(base_images)) + '\n')


if __name__ == '__main__':
    prepare(Path(sys.argv[1]), Path(sys.argv[2]), *sys.argv[3:])
