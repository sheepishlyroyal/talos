## What & why

<!-- One paragraph. Link the issue if there is one. -->

## Checklist

- [ ] `./gradlew :talos-mod:compileJava` green on every branch I touched
- [ ] `./gradlew :talos-graalpy-runtime:test` green (if the runtime/sandbox was touched)
- [ ] Python API changes: bridge export + pyapi wrapper + `__all__` + `talos.pyi` stub all updated
- [ ] Docs parity: README + `skill/SKILL.md` + docs site content updated (condensed in SKILL is fine)
- [ ] Invariants respected (game thread never enters Python; bounded queues; host-side suggestions)
