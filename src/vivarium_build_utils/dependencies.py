"""Intra-monorepo dependency graph utilities.

A *monorepo* here is a repository whose packages live in ``<root>/libs/<pkg>/``,
each with its own ``pyproject.toml`` declaring a ``[project].name`` (e.g.
``vivarium-engine``). Two consumers use this module:

* ``make install`` (``base.mk``) - to install a package's in-tree sibling
  dependencies from source, so a single PR can update interdependent packages
  without an intermediate release.
* the vivarium-suite release workflow - to order releases dependencies-first.

Outside that layout the helpers report "not a monorepo" (:func:`find_libs_root`
returns ``None`` and :func:`load_libs` returns an empty mapping), so importing or
invoking this module from a standalone repo is harmless - callers simply no-op.

Two graphs matter. The *runtime* graph (edges from ``[project.dependencies]``)
governs what an installed wheel pulls in and is expected to be acyclic. The
*install-time* graph additionally follows the optional-dependency extras that a
given install activates (and the ``pkg[extra]`` self-references within them);
folding in every extra can introduce cycles (e.g. a test-only dependency that
depends back on its consumer), so callers choose which extras to include.
"""

from __future__ import annotations

import argparse
import graphlib
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # Python < 3.11
    import tomli as tomllib  # type: ignore[no-redef]

_CANONICALIZE = re.compile(r"[-_.]+")
# Distribution name (optionally with [extras]) at the start of a requirement string.
_REQUIREMENT = re.compile(r"^\s*([A-Za-z0-9][A-Za-z0-9._-]*)\s*(?:\[([^\]]*)\])?")
_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")


def canonical_name(name: str) -> str:
    """Return the PEP 503 normalized form of a distribution name."""
    return _CANONICALIZE.sub("-", name).strip("-").lower()


def pretend_version_env_var(dist_name: str) -> str:
    """Return the ``setuptools_scm`` pretend-version env var for a distribution.

    ``setuptools_scm`` reads ``SETUPTOOLS_SCM_PRETEND_VERSION_FOR_<NAME>`` where
    ``<NAME>`` is the distribution name normalized (PEP 503) then upper-cased with
    hyphens replaced by underscores - e.g. ``vivarium-engine`` becomes
    ``SETUPTOOLS_SCM_PRETEND_VERSION_FOR_VIVARIUM_ENGINE``.
    """
    return (
        "SETUPTOOLS_SCM_PRETEND_VERSION_FOR_"
        + canonical_name(dist_name).replace("-", "_").upper()
    )


def _parse_requirement(spec: str) -> tuple[str, list[str]]:
    """Return the canonical base name and extras of a requirement string.

    Strips environment markers, version specifiers, and whitespace. e.g.
    ``"vivarium-engine[test]>=5.1.0 ; python_version>'3.10'"`` ->
    ``("vivarium-engine", ["test"])``.
    """
    spec = spec.split(";", 1)[0]
    match = _REQUIREMENT.match(spec)
    if not match:
        return "", []
    extras = [e.strip() for e in (match.group(2) or "").split(",") if e.strip()]
    return canonical_name(match.group(1)), extras


@dataclass
class Lib:
    """A single in-tree package."""

    name: str  # canonical distribution name, e.g. "vivarium-engine"
    directory: Path  # the libs/<dir> path
    runtime: list[tuple[str, list[str]]] = field(default_factory=list)
    extras: dict[str, list[tuple[str, list[str]]]] = field(default_factory=dict)

    @property
    def dir_name(self) -> str:
        return self.directory.name

    def changelog_version(self) -> str | None:
        """Return the pending version from the first CHANGELOG.rst line, if any."""
        changelog = self.directory / "CHANGELOG.rst"
        if not changelog.exists():
            return None
        for line in changelog.read_text(encoding="utf-8").splitlines():
            match = _VERSION.search(line)
            if match:
                return match.group(0)
        return None


def find_libs_root(start: Path | str | None = None) -> Path | None:
    """Return the monorepo ``libs/`` directory for ``start``, or None.

    Walks upward from ``start`` (default: cwd). A directory qualifies as the libs
    root when it is named ``libs`` and contains at least one ``*/pyproject.toml``.
    Returns ``None`` when no such ancestor exists - i.e. this is not a monorepo
    layout - so standalone repos are detected and left alone.
    """
    start_path = Path(start).resolve() if start else Path.cwd()
    for directory in (start_path, *start_path.parents):
        if directory.name == "libs" and any(directory.glob("*/pyproject.toml")):
            return directory
        candidate = directory / "libs"
        if candidate.is_dir() and any(candidate.glob("*/pyproject.toml")):
            return candidate
    return None


def load_libs(libs_root: Path | None = None) -> dict[str, Lib]:
    """Load all in-tree packages, keyed by canonical distribution name.

    Returns an empty mapping when not in a monorepo layout.
    """
    if libs_root is None:
        libs_root = find_libs_root()
    if libs_root is None:
        return {}

    libs: dict[str, Lib] = {}
    for pyproject in sorted(libs_root.glob("*/pyproject.toml")):
        data = tomllib.loads(pyproject.read_text(encoding="utf-8"))
        project = data.get("project", {})
        name = project.get("name")
        if not name:
            continue
        runtime = [_parse_requirement(dep) for dep in project.get("dependencies", [])]
        extras = {
            extra: [_parse_requirement(dep) for dep in deps]
            for extra, deps in project.get("optional-dependencies", {}).items()
        }
        canonical = canonical_name(name)
        libs[canonical] = Lib(
            name=canonical, directory=pyproject.parent, runtime=runtime, extras=extras
        )
    return libs


def _resolve_dir_name(libs: dict[str, Lib], target: str) -> Lib:
    """Resolve a target given either its canonical dist name or its libs/ dir name."""
    canonical = canonical_name(target)
    if canonical in libs:
        return libs[canonical]
    by_dir = {lib.dir_name: lib for lib in libs.values()}
    if target in by_dir:
        return by_dir[target]
    raise KeyError(f"No in-tree package matching {target!r}")


def _direct_in_tree_deps(lib: Lib, libs: dict[str, Lib], extras: tuple[str, ...]) -> set[str]:
    """In-tree dist names directly required by ``lib`` under the given extras.

    Expands the lib's own ``pkg[extra]`` self-references so that a meta-extra like
    ``ci_github = ["vivarium-engine[test,docs,lint]"]`` contributes the deps of
    ``test``/``docs``/``lint``. Self edges (the package depending on itself) are
    not returned.
    """
    found: set[str] = set()
    queue: list[tuple[str, list[str]]] = list(lib.runtime)
    for extra in extras:
        queue.extend(lib.extras.get(extra, []))
    seen_self_extras: set[str] = set()
    while queue:
        base, dep_extras = queue.pop()
        if not base:
            continue
        if base == lib.name:
            # Self-reference such as pkg[test]: pull in those extras' deps.
            for extra in dep_extras:
                if extra not in seen_self_extras:
                    seen_self_extras.add(extra)
                    queue.extend(lib.extras.get(extra, []))
            continue
        if base in libs:
            found.add(base)
    return found


def reachable_siblings(
    target: str, libs: dict[str, Lib], extras: tuple[str, ...] = ()
) -> list[Lib]:
    """Return the transitive in-tree siblings ``target`` depends on.

    The target's own ``extras`` are followed (matching what the install will
    activate); deeper siblings contribute only their runtime dependencies, since
    that is all an installed dependency pulls in. The target itself is excluded.
    Order is unspecified - callers install siblings with ``--no-deps``, so the
    set is what matters and the result is immune to dependency cycles.
    """
    root = _resolve_dir_name(libs, target)
    result: dict[str, Lib] = {}
    frontier = _direct_in_tree_deps(root, libs, extras)
    while frontier:
        name = frontier.pop()
        if name == root.name or name in result:
            continue
        result[name] = libs[name]
        frontier |= _direct_in_tree_deps(libs[name], libs, extras=())
    return sorted(result.values(), key=lambda lib: lib.name)


def _edges(libs: dict[str, Lib], extras: tuple[str, ...]) -> dict[str, set[str]]:
    """Build the dependency edge map (lib -> in-tree deps) over the given extras."""
    return {name: _direct_in_tree_deps(lib, libs, extras) for name, lib in libs.items()}


def topological_order(
    targets: list[str], libs: dict[str, Lib], extras: tuple[str, ...] = ()
) -> list[Lib]:
    """Return ``targets`` ordered dependencies-first.

    Edges follow runtime deps plus the given ``extras`` (the dependency set
    actually installed during a release). Raises :class:`ValueError` on a cycle
    rather than emitting an undefined order.
    """
    resolved = [_resolve_dir_name(libs, t) for t in targets]
    subset = {lib.name for lib in resolved}
    edges = _edges(libs, extras)

    # Add nodes and their in-subset dependencies sorted, so the ordering among
    # otherwise-independent packages is deterministic.
    sorter: graphlib.TopologicalSorter[str] = graphlib.TopologicalSorter()
    for name in sorted(subset):
        sorter.add(name, *sorted(dep for dep in edges.get(name, set()) if dep in subset))
    try:
        order = list(sorter.static_order())
    except graphlib.CycleError as exc:
        raise ValueError(
            f"Dependency cycle detected among in-tree packages: {exc.args[1]}"
        ) from exc
    return [libs[name] for name in order]


def _cmd_siblings(args: argparse.Namespace) -> int:
    libs = load_libs()
    if not libs:
        return 0  # not a monorepo: nothing to pre-install
    extras = tuple(args.extra)
    for sibling in reachable_siblings(args.target, libs, extras):
        version = sibling.changelog_version() or ""
        # Tab-separated: <directory> <pretend-version env var> <pending version>
        print(f"{sibling.directory}\t{pretend_version_env_var(sibling.name)}\t{version}")
    return 0


def _cmd_topo(args: argparse.Namespace) -> int:
    libs = load_libs()
    if not libs:
        for target in args.targets:
            print(target)
        return 0
    extras = tuple(args.extra)
    for lib in topological_order(args.targets, libs, extras):
        print(lib.dir_name)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m vivarium_build_utils.dependencies",
        description="Intra-monorepo dependency graph queries.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    siblings = subparsers.add_parser(
        "siblings",
        help="Print the transitive in-tree siblings a package depends on, one per "
        "line as '<dir>\\t<pretend-version env var>\\t<pending version>'.",
    )
    siblings.add_argument("target", help="Target package (dist name or libs/ dir name).")
    siblings.add_argument(
        "--extra",
        action="append",
        default=[],
        help="Optional-dependency extra the install activates (repeatable).",
    )
    siblings.set_defaults(func=_cmd_siblings)

    topo = subparsers.add_parser(
        "topo", help="Print the given packages ordered dependencies-first."
    )
    topo.add_argument("targets", nargs="+", help="Packages (dist names or dir names).")
    topo.add_argument(
        "--extra",
        action="append",
        default=[],
        help="Optional-dependency extra installed during release (repeatable).",
    )
    topo.set_defaults(func=_cmd_topo)

    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
