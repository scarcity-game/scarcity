# combat

The fighting rules (#451, epic #449): the unarmed `strike` and its
`melee-attack`, which draws the hit and the damage. `Combat.standard()` is
the standard catalogue with these rules added, and `services/world` runs it.
Pure Java on `libs/world-model`.

See [AGENTS.md](AGENTS.md) for the rules and how to add one, and
`docs/action-types.md` for the contract.

    cd libs/combat
    mvn -B -f ../../pom.xml -pl libs/combat -am verify

No Docker needed.
