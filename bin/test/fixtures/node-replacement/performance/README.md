# Current Baseline Fixture Scope

The performance evidence test uses the bundled node catalog and the retained `programmability` fixture tree. It hashes those sources before recording samples. The fixture tree is copied only into JUnit temporary directories to measure a current filesystem snapshot/restore proxy; it never touches `run/plugins/ReSync` or external data.

This is not a complete ReSync-folder snapshot fixture. Coordinated migration, full snapshot/restore, Remotely hydration, interactive editing, and live execution remain explicitly unavailable current measurements until their future acceptance methods run.
