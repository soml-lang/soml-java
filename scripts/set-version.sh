#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
version="${1:?Usage: sh scripts/set-version.sh <version>}"

set_version() {
	mvn --batch-mode --file "$1" org.codehaus.mojo:versions-maven-plugin:2.23.0:set "-DnewVersion=$version" -DgenerateBackupPoms=false
}

set_version pom.xml
set_version bench/pom.xml
set_version fuzz/pom.xml

# The install snippets in the readme.
perl -pi -e "s|<version>[^<]*</version>|<version>$version</version>|; s|sh\.soml:soml:[^\"]*|sh.soml:soml:$version|" readme.md

git commit --message "$version" pom.xml bench/pom.xml fuzz/pom.xml readme.md
