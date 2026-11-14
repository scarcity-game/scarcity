# world-seed

The test area: its fixed ids (`WorldIds`), its fresh states (`TestArea`,
`src/main/resources/seed/`) and `Seeder`, which seeds it in one transaction
if it doesn't exist. The orbiter is controlled by `orbital`, and
the area keeps game `time`. There is no upgrade path (`AGENTS.md`).

    JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn -B -f ../../pom.xml -pl libs/world-seed -am verify

The tests need Docker (Testcontainers).
