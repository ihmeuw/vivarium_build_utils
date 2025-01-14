#!/bin/bash

# Define variables
dependency_name=$1
branch_name=$2
workflow=$3

root_dir=$(pwd)
dependency_branch_name='main'
branch_name_to_check=$(git rev-parse --abbrev-ref HEAD)
iterations=0

while [ "$branch_name_to_check" != "$dependency_branch_name" ] && [ $iterations -lt 20 ]
do
  echo "Checking for ${dependency_name} branch: '${branch_name_to_check}'"
  if git ls-remote --exit-code --heads https://github.com/ihmeuw/"${dependency_name}".git "${branch_name_to_check}" == "0"
  then
    dependency_branch_name=${branch_name_to_check}
    echo "Found matching branch: ${dependency_branch_name}"
  else
    echo "Could not find ${dependency_name} branch '${branch_name_to_check}'. Finding parent branch."
    # Get the commit hash of where this branch diverged from main
    merge_base=$(git merge-base "${branch_name_to_check}" "main")
    # Find the next parent branch by getting the symbolic name of the merge base
    branch_name_to_check=$(git name-rev --exclude "tags/*" --exclude "${branch_name_to_check}" --refs="refs/heads/*" --name-only "${merge_base}" | sed 's/\^[0-9]*$//')
    
    if [ -z "$branch_name_to_check" ] || [ "$branch_name_to_check" = "undefined" ]; then
      echo "Could not find parent branch. Will use released version of ${dependency_name}."
      branch_name_to_check="main"
    fi
    echo "Next branch to check: ${branch_name_to_check}"
    iterations=$((iterations+1))
  fi
done

if [ "$workflow" == "github" ]; then
  echo "${dependency_name}_branch_name=${dependency_branch_name}" >> "$GITHUB_ENV"
fi

if [ "$dependency_branch_name" != "main" ]; then
  echo "Cloning ${dependency_name} branch: ${dependency_branch_name}"
  cd ..
  git clone --branch="${dependency_branch_name}" https://github.com/ihmeuw/"${dependency_name}".git
  cd "${dependency_name}" || exit
  pip install .
  cd "$root_dir" || exit
fi
