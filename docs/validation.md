# Validation on real data

Counts below were produced by running the tool on public data, not on the synthetic samples in `examples/`.

## Corpus
The rendered Dockerfiles of docker-library/python (`3a144d7`) and docker-library/nginx (`ab68aad`): 42 files. Template files with `{{ }}` placeholders were excluded because they are not Dockerfiles.

## Result
84 findings: 42 DKR001 (no `USER`, the container runs as root) and 42 DKR008 (low). Both are accurate for these images, which run as root by design in the base variants.

## What the real data exposed
Template placeholders were reported as unknown instructions (DKR000 info). That is correct behaviour for non-Dockerfile input, so the corpus was filtered rather than the tool changed.
