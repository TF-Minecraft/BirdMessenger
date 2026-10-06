# BirdMessenger

> Character-to-character letter delivery for TF-Minecraft.

BirdMessenger turns written correspondence into an in-world activity. Players send a letter from a bird coop or mailbox, choose a recipient's roleplay character, and wait for the bird's journey to finish. Mail belongs to the intended character, even when their player is offline or playing someone else.

## Features

- **Character recipients** — choose and confirm a recipient through an in-game character picker connected to RPCharacters.
- **Recipient opt-out** — players can use `/rpcharacter mail off` in RPCharacters to hide their active character from the recipient list, or `/rpcharacter mail on` to restore it. If a recipient opts out before a sender confirms, the letter is returned; already-sent mail still arrives.
- **Physical letters** — send supported blank, sealed, or opened letter items while preserving the item and its contents.
- **Sealed correspondence** — sign letters with their own title and break the seal when opening them to read.
- **Travel time** — delivery time follows the distance to the recipient's character location, with an estimated arrival shown to the sender.
- **Pending deliveries** — letters wait until their recipient is online and using the correct character; in-flight mail resumes after a restart.
- **Delivery feedback** — sender and recipient receive messages and bird-themed sound feedback as correspondence progresses.
- **Optional Discord notices** — TFMCWeb integration can notify linked recipients when a letter's flight completes.

The mailbox interaction protects against accidental ordinary left-click breaking, while deliberate sneak-breaking remains available. Sending, recipient selection, and confirmation all happen in game, keeping correspondence close to the roleplay world.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/BirdMessenger/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests

Install the pinned shared plugin dependencies, download the private build
inputs, then run the build with Java 21. In Bash:

```bash
python3 path/to/TLibs/tools/install-plugins.py --pom pom.xml --mode pinned &&
  (read -rsp 'ServerAssets token: ' GH_TOKEN && echo && export GH_TOKEN &&
    bash .github/scripts/prepare-release.sh) &&
  mvn clean verify
```

The installer comes from a separate TLibs checkout
(`git clone https://github.com/TF-Minecraft/TLibs.git`); point `path/to/TLibs` at it. The token needs Contents read access
to TF-Minecraft/ServerAssets. The prompt keeps it out of shell history, the
subshell keeps it out of your session and Maven, and Maven only runs if both
preparation steps succeed. CI supplies it from `DEPS_TOKEN`.

Tests use JUnit and Mockito and run without a live Minecraft server.
`mvn clean verify` requires 100% production line coverage with no class or package
exclusions. JaCoCo writes HTML and XML reports to `target/site/jacoco/`, and CI
uploads them alongside the Surefire test reports. The gate measures lines;
it does not require 100% branch coverage or replace testing on a live server.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
