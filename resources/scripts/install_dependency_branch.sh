#!/bin/bash

# =============================================================================
# Recursive Upstream Dependency Branch Installer
# =============================================================================
#
# Resolves and installs upstream dependency branches, including transitive
# dependencies discovered by reading each dependency's Jenkinsfile.
#
# For each dependency, the script checks whether a matching branch exists in
# the dependency's remote repo. If a match is found (non-main), the dependency
# is cloned and installed from source. The script then recursively inspects
# that dependency's own Jenkinsfile for *its* upstream_repos and repeats the
# process, ensuring the entire transitive dependency tree is resolved.
#
# Usage (new):
#   ./install_dependency_branch.sh <dep1> [dep2 ...] [--workflow jenkins|github]
#
# Usage (legacy / backward-compatible):
#   ./install_dependency_branch.sh <dep_name> <branch_name> <workflow>
#
# =============================================================================

set -uo pipefail

# ---- Global state ----
declare -A REPO_BRANCH_MAP=()   # repo -> resolved branch name
declare -A RESOLVED_REPOS=()   # repos we've finished resolving (cycle guard)
declare -a INSTALL_ORDER=()    # repos to install, deepest-first (post-order)

ROOT_DIR=$(pwd)
WORKFLOW=""

# ---- Logging helpers ----
log() { echo "$@"; }

log_indent() {
  local depth=$1; shift
  local prefix=""
  for ((i = 0; i < depth; i++)); do prefix+="  "; done
  echo "${prefix}$*"
}

# ---- Determine the current branch name from the environment ----
determine_branch_to_check() {
  if [ -n "${CHANGE_BRANCH:-}" ]; then
    # Jenkins pull request build
    echo "$CHANGE_BRANCH"
  elif [ -n "${BRANCH_NAME:-}" ]; then
    # Jenkins branch build
    echo "$BRANCH_NAME"
  elif [ -n "${GITHUB_HEAD_REF:-}" ]; then
    # GitHub Actions pull request
    echo "$GITHUB_HEAD_REF"
  elif [ -n "${GITHUB_REF_NAME:-}" ]; then
    # GitHub Actions branch build
    echo "$GITHUB_REF_NAME"
  else
    # Local development – try to get branch name from git
    local branch
    branch=$(git symbolic-ref --short HEAD 2>/dev/null || git rev-parse --abbrev-ref HEAD)

    if [ "$branch" = "HEAD" ]; then
      branch=$(git reflog -1 2>/dev/null | grep -o 'from \S*' | cut -d' ' -f2 | sed 's/^origin\///')
      if [ -z "$branch" ]; then
        echo "Could not determine branch name, defaulting to main" >&2
        echo "main"
        return
      fi
    fi
    echo "$branch"
  fi
}

# ---- Resolve what branch to use for a single dependency ----
# Walks up the current repo's branch lineage until a matching remote branch is
# found in the dependency, or falls back to "main".
# NOTE: All logging goes to stderr so the caller can capture stdout cleanly.
resolve_branch_for_dep() {
  local dep=$1
  local branch_to_check=$2
  local depth=${3:-0}
  local resolved="main"
  local iterations=0

  local prev_branch_to_check=""
  while [ "$branch_to_check" != "$resolved" ] && [ $iterations -lt 20 ]; do
    log_indent "$depth" "Checking for ${dep} branch: '${branch_to_check}'" >&2

    if git ls-remote --exit-code --heads \
         "https://github.com/ihmeuw/${dep}.git" "$branch_to_check" &>/dev/null; then
      resolved="$branch_to_check"
      log_indent "$depth" "Found matching branch: ${resolved}" >&2
      break
    else
      log_indent "$depth" "Branch '${branch_to_check}' not found for ${dep}. Finding parent branch." >&2

      local merge_base
      merge_base=$(git merge-base origin/main HEAD 2>/dev/null || true)
      if [ -n "$merge_base" ]; then
        branch_to_check=$(git name-rev --exclude "tags/*" \
          --refs="refs/remotes/origin/*" --name-only "$merge_base" | \
          sed -E 's/^(remotes\/)?origin\///' | \
          sed -E 's/[~^][0-9]*$//')
        if [ "$branch_to_check" = "HEAD" ]; then
          branch_to_check="main"
        fi
      else
        # Fallback: try GitHub API for PR base branch
        local pr_number
        pr_number=$(echo "$branch_to_check" | grep -o 'PR-[0-9]*' | cut -d'-' -f2 || true)
        if [ -n "$pr_number" ] && [ -n "${GITHUB_TOKEN:-}" ]; then
          local base_branch
          base_branch=$(curl -s -H "Authorization: token ${GITHUB_TOKEN}" \
            "https://api.github.com/repos/ihmeuw/${dep}/pulls/${pr_number}" | \
            grep '"base": {' -A 3 | grep '"ref":' | cut -d'"' -f4 || true)
          branch_to_check="${base_branch:-main}"
        else
          branch_to_check="main"
        fi
      fi

      if [ -z "$branch_to_check" ] || [ "$branch_to_check" = "undefined" ]; then
        branch_to_check="main"
      fi

      # Avoid infinite loop if parent resolution returns the same branch
      if [ "$branch_to_check" = "$prev_branch_to_check" ]; then
        log_indent "$depth" "Parent branch resolution stuck on '${branch_to_check}', falling back to main." >&2
        branch_to_check="main"
      fi
      prev_branch_to_check="$branch_to_check"

      log_indent "$depth" "Next branch to check: ${branch_to_check}" >&2
      iterations=$((iterations + 1))
    fi
  done

  # Return via stdout (caller captures with $())
  echo "$resolved"
}

# ---- Fetch upstream_repos from a repo's Jenkinsfile on GitHub ----
# Does a minimal no-checkout clone to read just the Jenkinsfile, then parses
# the upstream_repos list from it.
get_transitive_deps() {
  local repo=$1
  local branch=$2
  local tmpdir
  tmpdir=$(mktemp -d)

  local deps=""
  if git clone --depth=1 --branch="$branch" --no-checkout \
       "https://github.com/ihmeuw/${repo}.git" "$tmpdir" 2>/dev/null; then
    # Checkout only the Jenkinsfile
    git -C "$tmpdir" checkout "$branch" -- Jenkinsfile 2>/dev/null || true

    if [ -f "$tmpdir/Jenkinsfile" ]; then
      # Extract the upstream_repos value – handles single-line and multi-line
      deps=$(sed -n '/upstream_repos\s*:/,/]/p' "$tmpdir/Jenkinsfile" | \
             grep -oE '"[a-zA-Z0-9_-]+"' | \
             tr -d '"' | \
             tr '\n' ' ')
    fi
  fi

  rm -rf "$tmpdir"
  echo "$deps"
}

# ---- Recursively resolve a dependency and its transitive deps ----
resolve_recursive() {
  local dep=$1
  local branch_to_check=$2
  local depth=${3:-0}

  # Guard against cycles and repeated work
  if [[ -v RESOLVED_REPOS["$dep"] ]]; then
    log_indent "$depth" "Already resolved: ${dep} -> ${REPO_BRANCH_MAP[$dep]}"
    return
  fi
  RESOLVED_REPOS["$dep"]=1

  log_indent "$depth" "Resolving: ${dep}"

  # Determine which branch (if any) this dependency has
  local resolved_branch
  resolved_branch=$(resolve_branch_for_dep "$dep" "$branch_to_check" "$depth")
  REPO_BRANCH_MAP["$dep"]="$resolved_branch"

  if [ "$resolved_branch" != "main" ]; then
    log_indent "$depth" "${dep} -> ${resolved_branch} (will install from source)"

    # Discover transitive dependencies from the dependency's Jenkinsfile
    log_indent "$depth" "Checking ${dep}'s Jenkinsfile for transitive dependencies..."
    local transitive_deps
    transitive_deps=$(get_transitive_deps "$dep" "$resolved_branch")

    if [ -n "$transitive_deps" ]; then
      log_indent "$depth" "Transitive deps for ${dep}: ${transitive_deps}"
      for tdep in $transitive_deps; do
        resolve_recursive "$tdep" "$branch_to_check" $((depth + 1))
      done
    else
      log_indent "$depth" "No transitive dependencies found for ${dep}"
    fi

    # Post-order: add after transitive deps so deepest deps are installed first
    INSTALL_ORDER+=("$dep")
  else
    log_indent "$depth" "${dep} -> main (using released version)"
  fi
}

# ---- Install all resolved non-main dependencies ----
install_resolved_deps() {
  echo ""
  echo "========================================="
  echo "Dependency Resolution Complete"
  echo "========================================="

  if [ ${#INSTALL_ORDER[@]} -eq 0 ]; then
    log "No upstream dependency branches to install (all using released versions)."
    return
  fi

  echo "Installation plan (deepest-first order):"
  for repo in "${INSTALL_ORDER[@]+"${INSTALL_ORDER[@]}"}"; do
    echo "  - ${repo} @ ${REPO_BRANCH_MAP[$repo]}"
  done
  echo ""

  cd ..  # Move to parent of the project root

  for repo in "${INSTALL_ORDER[@]+"${INSTALL_ORDER[@]}"}"; do
    local branch="${REPO_BRANCH_MAP[$repo]}"
    if [ -d "$repo" ]; then
      log "${repo} already cloned – reinstalling..."
      cd "$repo"
      pip install .
      cd ..
    else
      log "Cloning and installing ${repo} @ ${branch}..."
      git clone --branch="$branch" "https://github.com/ihmeuw/${repo}.git"
      cd "$repo"
      pip install .
      cd ..
    fi
  done

  cd "$ROOT_DIR"
}

# ---- Export branch info for CI systems ----
export_branch_info() {
  if [ "$WORKFLOW" = "github" ]; then
    for repo in "${!REPO_BRANCH_MAP[@]}"; do
      echo "${repo}_branch_name=${REPO_BRANCH_MAP[$repo]}" >> "$GITHUB_ENV"
    done
  fi
}

# ---- Main ----
main() {
  local repos=()

  # ---- Argument parsing ----
  # Legacy format (3 positional args): script.sh <dep> <branch> jenkins|github
  #   - <branch> is unused (detected from env); kept for backward compatibility.
  # New format: script.sh <dep1> [dep2 ...] [--workflow jenkins|github]
  if [ $# -eq 3 ] && { [ "$3" = "jenkins" ] || [ "$3" = "github" ]; }; then
    # Legacy invocation
    repos=("$1")
    WORKFLOW="$3"
  else
    while [[ $# -gt 0 ]]; do
      case $1 in
        --workflow)
          WORKFLOW="${2:-}"
          shift 2
          ;;
        *)
          repos+=("$1")
          shift
          ;;
      esac
    done
  fi

  if [ ${#repos[@]} -eq 0 ]; then
    echo "Usage: $0 <dep1> [dep2 ...] [--workflow jenkins|github]"
    echo "       $0 <dep_name> <branch_name> <workflow>  (legacy)"
    exit 1
  fi

  local branch_to_check
  branch_to_check=$(determine_branch_to_check)
  log "Determined branch to check: ${branch_to_check}"
  echo ""

  if [ "$branch_to_check" = "main" ]; then
    log "On main branch – all upstream dependencies will use released versions."
    export_branch_info
    return
  fi

  echo "========================================="
  echo "Resolving dependencies recursively"
  echo "========================================="
  for repo in "${repos[@]}"; do
    resolve_recursive "$repo" "$branch_to_check" 0
  done

  install_resolved_deps
  export_branch_info
}

main "$@"
