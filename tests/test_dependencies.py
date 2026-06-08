"""Tests for the intra-monorepo dependency graph utility."""

from __future__ import annotations

import textwrap
from pathlib import Path

import pytest

from vivarium_build_utils import dependencies


def _write_lib(
    libs_root: Path,
    dir_name: str,
    dist_name: str,
    dependencies: list[str] | None = None,
    optional: dict[str, list[str]] | None = None,
    changelog_version: str | None = None,
) -> None:
    pkg = libs_root / dir_name
    pkg.mkdir(parents=True)
    deps = "".join(f'    "{d}",\n' for d in (dependencies or []))
    extras = ""
    for name, items in (optional or {}).items():
        joined = "".join(f'    "{i}",\n' for i in items)
        extras += f"{name} = [\n{joined}]\n"
    pkg.joinpath("pyproject.toml").write_text(
        textwrap.dedent(f"""\
            [project]
            name = "{dist_name}"
            dependencies = [
            {deps}]
            [project.optional-dependencies]
            {extras}
            """),
        encoding="utf-8",
    )
    if changelog_version is not None:
        pkg.joinpath("CHANGELOG.rst").write_text(
            f"**{changelog_version} - 01/01/26**\n", encoding="utf-8"
        )


@pytest.fixture()
def suite(tmp_path: Path) -> Path:
    """A monorepo mirroring the real vivarium-suite graph, incl. a cycle case."""
    libs = tmp_path / "libs"
    libs.mkdir()
    _write_lib(libs, "artifact", "vivarium-artifact", changelog_version="2.0.0")
    _write_lib(libs, "config-tree", "vivarium-config-tree", changelog_version="5.0.1")
    _write_lib(
        libs,
        "engine",
        "vivarium-engine",
        dependencies=["vivarium-artifact", "vivarium-config-tree", "pandas"],
        optional={
            "test": ["vivarium-testing-utils", "pytest"],
            "docs": ["sphinx"],
            "lint": ["black"],
            "ci_github": ["vivarium-engine[test,docs,lint]"],
        },
        changelog_version="6.1.0",
    )
    _write_lib(
        libs,
        "cluster-tools",
        "vivarium-cluster-tools",
        dependencies=["vivarium-engine>=5.1.0", "vivarium-config-tree>=5.0.0"],
        optional={
            "test": ["vivarium-testing-utils"],
            "ci_github": ["vivarium-cluster-tools[test]"],
        },
        changelog_version="2.2.0",
    )
    # testing-utils: no in-tree *runtime* deps, but its validation extra depends
    # back on engine -> would create a cycle if all extras were folded in.
    _write_lib(
        libs,
        "testing-utils",
        "vivarium-testing-utils",
        dependencies=["pyyaml"],
        optional={
            "validation": ["vivarium-engine", "vivarium-gbd-mapping"],
            "test": ["pytest"],
            "ci_github": ["vivarium-testing-utils[test]"],
        },
        changelog_version="0.4.0",
    )
    _write_lib(libs, "gbd-mapping", "vivarium-gbd-mapping", changelog_version="6.0.0")
    return libs


def test_canonical_name() -> None:
    assert dependencies.canonical_name("Vivarium_Engine") == "vivarium-engine"
    assert dependencies.canonical_name("vivarium..config__tree") == "vivarium-config-tree"


def test_pretend_version_env_var() -> None:
    assert (
        dependencies.pretend_version_env_var("vivarium-engine")
        == "SETUPTOOLS_SCM_PRETEND_VERSION_FOR_VIVARIUM_ENGINE"
    )


@pytest.mark.parametrize(
    "spec, expected",
    [
        ("vivarium-engine>=5.1.0", ("vivarium-engine", [])),
        ("vivarium-engine[test,docs]>=5.1.0", ("vivarium-engine", ["test", "docs"])),
        ("pkg ; python_version < '3.11'", ("pkg", [])),
        ("vivarium_testing_utils", ("vivarium-testing-utils", [])),
    ],
)
def test_parse_requirement(spec: str, expected: tuple[str, list[str]]) -> None:
    assert dependencies._parse_requirement(spec) == expected


def test_find_libs_root_from_package_dir(suite: Path) -> None:
    assert dependencies.find_libs_root(suite / "engine") == suite


def test_find_libs_root_from_repo_root(suite: Path) -> None:
    assert dependencies.find_libs_root(suite.parent) == suite


def test_find_libs_root_not_a_monorepo(tmp_path: Path) -> None:
    standalone = tmp_path / "standalone"
    standalone.mkdir()
    standalone.joinpath("pyproject.toml").write_text('[project]\nname = "foo"\n')
    assert dependencies.find_libs_root(standalone) is None


def test_load_libs_empty_outside_monorepo(tmp_path: Path) -> None:
    assert dependencies.load_libs(tmp_path) == {}


def test_reachable_siblings_runtime(suite: Path) -> None:
    libs = dependencies.load_libs(suite)
    names = {lib.name for lib in dependencies.reachable_siblings("engine", libs)}
    assert names == {"vivarium-artifact", "vivarium-config-tree"}


def test_reachable_siblings_transitive(suite: Path) -> None:
    libs = dependencies.load_libs(suite)
    names = {lib.name for lib in dependencies.reachable_siblings("cluster-tools", libs)}
    # cluster-tools -> engine -> {artifact, config-tree}
    assert names == {"vivarium-engine", "vivarium-artifact", "vivarium-config-tree"}


def test_reachable_siblings_follows_ci_extra(suite: Path) -> None:
    libs = dependencies.load_libs(suite)
    names = {
        lib.name
        for lib in dependencies.reachable_siblings("engine", libs, extras=("ci_github",))
    }
    # ci_github -> test -> testing-utils, in addition to runtime deps.
    assert "vivarium-testing-utils" in names
    assert {"vivarium-artifact", "vivarium-config-tree"} <= names


def test_reachable_siblings_accepts_dist_name(suite: Path) -> None:
    libs = dependencies.load_libs(suite)
    by_dir = dependencies.reachable_siblings("engine", libs)
    by_name = dependencies.reachable_siblings("vivarium-engine", libs)
    assert {lib.name for lib in by_dir} == {lib.name for lib in by_name}


def test_changelog_version(suite: Path) -> None:
    libs = dependencies.load_libs(suite)
    assert libs["vivarium-engine"].changelog_version() == "6.1.0"


def test_topological_order_runtime(suite: Path) -> None:
    libs = dependencies.load_libs(suite)
    order = [
        lib.dir_name
        for lib in dependencies.topological_order(
            ["cluster-tools", "engine", "config-tree", "artifact"], libs
        )
    ]
    assert order.index("artifact") < order.index("engine")
    assert order.index("config-tree") < order.index("engine")
    assert order.index("engine") < order.index("cluster-tools")


def test_topological_order_runtime_is_acyclic_with_testing_utils(suite: Path) -> None:
    """Runtime-only graph has no cycle even though testing-utils[validation] would."""
    libs = dependencies.load_libs(suite)
    order = dependencies.topological_order(["engine", "testing-utils", "gbd-mapping"], libs)
    assert {lib.dir_name for lib in order} == {"engine", "testing-utils", "gbd-mapping"}


def test_topological_order_ci_github_is_acyclic(suite: Path) -> None:
    """ci_github edges keep testing-utils orderable before its consumers."""
    libs = dependencies.load_libs(suite)
    order = [
        lib.dir_name
        for lib in dependencies.topological_order(
            ["engine", "testing-utils"], libs, extras=("ci_github",)
        )
    ]
    # engine[ci_github] -> test -> testing-utils, so testing-utils releases first.
    assert order.index("testing-utils") < order.index("engine")


def test_topological_order_detects_cycle(suite: Path) -> None:
    """Folding validation into the graph creates engine <-> testing-utils."""
    libs = dependencies.load_libs(suite)
    # Make testing-utils' ci_github pull validation (the regression we guard against).
    libs["vivarium-testing-utils"].extras["ci_github"] = [
        ("vivarium-testing-utils", ["validation"])
    ]
    with pytest.raises(ValueError, match="cycle"):
        dependencies.topological_order(
            ["engine", "testing-utils"], libs, extras=("ci_github",)
        )


def test_topological_order_independent_nodes_are_alphabetical(suite: Path) -> None:
    """Mutually-independent packages come out in a stable alphabetical order."""
    libs = dependencies.load_libs(suite)
    order = [
        lib.dir_name
        for lib in dependencies.topological_order(
            ["gbd-mapping", "config-tree", "artifact"], libs
        )
    ]
    assert order == ["artifact", "config-tree", "gbd-mapping"]


def test_resolve_dir_name_unknown_target_raises(suite: Path) -> None:
    libs = dependencies.load_libs(suite)
    with pytest.raises(KeyError, match="no-such-pkg"):
        dependencies._resolve_dir_name(libs, "no-such-pkg")


def test_load_libs_skips_pyproject_without_project_name(suite: Path) -> None:
    # A pyproject.toml lacking [project].name must be ignored, not crash.
    (suite / "not-a-package").mkdir()
    (suite / "not-a-package" / "pyproject.toml").write_text(
        "[build-system]\nrequires = []\n", encoding="utf-8"
    )
    libs = dependencies.load_libs(suite)
    assert "not-a-package" not in {lib.dir_name for lib in libs.values()}


@pytest.mark.parametrize("spec", ["", ">=1.0", "  ", "[extras]"])
def test_parse_requirement_unparseable_returns_empty(spec: str) -> None:
    assert dependencies._parse_requirement(spec) == ("", [])


def test_pretend_version_env_var_normalizes() -> None:
    # Underscores, dots, and mixed case all normalize before upper-casing.
    assert (
        dependencies.pretend_version_env_var("Vivarium_Config.Tree")
        == "SETUPTOOLS_SCM_PRETEND_VERSION_FOR_VIVARIUM_CONFIG_TREE"
    )


def test_find_libs_root_defaults_to_cwd(suite: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # The production path passes no argument and relies on cwd.
    monkeypatch.chdir(suite / "engine")
    assert dependencies.find_libs_root() == suite


def test_changelog_version_missing_file_returns_none(suite: Path) -> None:
    # gbd-mapping is created with a CHANGELOG; remove it to exercise the None branch.
    (suite / "gbd-mapping" / "CHANGELOG.rst").unlink()
    libs = dependencies.load_libs(suite)
    assert libs["vivarium-gbd-mapping"].changelog_version() is None


def test_changelog_version_no_version_line_returns_none(suite: Path) -> None:
    (suite / "gbd-mapping" / "CHANGELOG.rst").write_text(
        "Changelog\n=========\nUnreleased changes.\n", encoding="utf-8"
    )
    libs = dependencies.load_libs(suite)
    assert libs["vivarium-gbd-mapping"].changelog_version() is None


# --- CLI layer (the contract base.mk parses) -------------------------------------


def test_cmd_siblings_output_format(
    suite: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Pin the exact tab-separated `<dir>\\t<env var>\\t<version>` line base.mk reads."""
    monkeypatch.chdir(suite / "engine")
    assert dependencies.main(["siblings", "engine"]) == 0
    lines = capsys.readouterr().out.splitlines()
    assert {Path(line.split("\t")[0]).name for line in lines} == {"artifact", "config-tree"}
    for line in lines:
        directory, env_var, version = line.split("\t")  # exactly three columns
        assert Path(directory).is_absolute()
        assert env_var.startswith("SETUPTOOLS_SCM_PRETEND_VERSION_FOR_")
        assert version  # these fixtures all set a CHANGELOG version


def test_cmd_siblings_follows_extra(
    suite: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(suite / "engine")
    assert dependencies.main(["siblings", "engine", "--extra", "ci_github"]) == 0
    dirs = {Path(line.split("\t")[0]).name for line in capsys.readouterr().out.splitlines()}
    assert "testing-utils" in dirs  # ci_github -> test -> testing-utils


def test_cmd_siblings_empty_version_column(
    suite: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """A sibling with no CHANGELOG version emits an empty third column, not a missing one."""
    (suite / "config-tree" / "CHANGELOG.rst").unlink()
    monkeypatch.chdir(suite / "engine")
    assert dependencies.main(["siblings", "engine"]) == 0
    rows = {
        Path(line.split("\t", 2)[0]).name: line.split("\t", 2)
        for line in capsys.readouterr().out.splitlines()
    }
    assert rows["config-tree"][2] == ""  # third column present and empty


def test_cmd_topo_prints_dir_names_deps_first(
    suite: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(suite)
    assert dependencies.main(["topo", "cluster-tools", "engine", "artifact"]) == 0
    order = capsys.readouterr().out.split()
    assert order.index("artifact") < order.index("engine") < order.index("cluster-tools")


def test_cli_no_op_outside_monorepo(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    standalone = tmp_path / "standalone"
    standalone.mkdir()
    standalone.joinpath("pyproject.toml").write_text(
        '[project]\nname = "foo"\n', encoding="utf-8"
    )
    monkeypatch.chdir(standalone)
    # siblings: prints nothing, succeeds.
    assert dependencies.main(["siblings", "foo"]) == 0
    assert capsys.readouterr().out == ""
    # topo: echoes the targets back verbatim (the passthrough base.mk relies on).
    assert dependencies.main(["topo", "foo", "bar"]) == 0
    assert capsys.readouterr().out.split() == ["foo", "bar"]
