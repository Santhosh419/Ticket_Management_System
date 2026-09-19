#!/usr/bin/env bash
# Rebuilds the Arena-sandbox toolchain used to develop IntelliDesk.
# (Only needed inside the restricted sandbox - on a normal machine,
# the project builds with plain `mvn` from Maven Central.)
set -euo pipefail
mkdir -p ~/.cache/toolchain && cd ~/.cache/toolchain

# 1. JDK 17 (AOSP prebuilt mirror with real, non-LFS binaries)
curl -sL -o jdk17.tar.gz "https://codeload.github.com/msft-mirror-aosp/platform.prebuilts.jdk.jdk17/tar.gz/refs/heads/android13-d1-release"
tar xzf jdk17.tar.gz --strip-components=1 "platform.prebuilts.jdk.jdk17-android13-d1-release/linux-x86"
chmod +x linux-x86/bin/* linux-x86/lib/jspawnhelper || true

# 2. Maven 3.9.7 distribution + local artifact repository
curl -sL -o m2repo.tar.gz "https://codeload.github.com/mssalunkhe/.m2/tar.gz/refs/heads/main"
tar xzf m2repo.tar.gz --strip-components=1 ".m2-main/repository" ".m2-main/wrapper"
chmod +x wrapper/dists/apache-maven-3.9.7-bin/*/apache-maven-3.9.7/bin/*

# 3. FitForce .m2 (springdoc 2.5.0, swagger, jjwt 0.12.5, launcher)
curl -sL -o fitforce.tar.gz "https://codeload.github.com/karol199393/FitForce/tar.gz/refs/heads/develop"
tar xzf fitforce.tar.gz --strip-components=2 "FitForce-develop/.m2/repository"

# 4. H2 database + jackson-dataformat-yaml (from fetchlibs2 blobs)
gh api "repos/2ne1ugly/fetchlibs2/git/trees/HEAD?recursive=1" > /tmp/fetchlibs2tree.json
python3 - <<'EOF'
import json, base64, os, subprocess
tree = json.load(open("/tmp/fetchlibs2tree.json"))["tree"]
M2 = os.path.expanduser("~/.cache/toolchain/repository")
wanted = [
    "com/h2database/h2/2.2.224/h2-2.2.224.jar",
    "com/h2database/h2/2.2.224/h2-2.2.224.pom",
    "com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/2.17.1/jackson-dataformat-yaml-2.17.1.jar",
    "com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/2.17.1/jackson-dataformat-yaml-2.17.1.pom",
]
for tail in wanted:
    entry = next((t for t in tree if t["path"].endswith(tail)), None)
    if entry is None:
        raise SystemExit(f"not found in fetchlibs2: {tail}")
    out = subprocess.run(["gh", "api", f"repos/2ne1ugly/fetchlibs2/git/blobs/{entry['sha']}", "--jq", ".content"],
                         capture_output=True, text=True, check=True)
    dest = os.path.join(M2, tail)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    open(dest, "wb").write(base64.b64decode(out.stdout))
    print("fetched", tail)
EOF

# 5. Surefire 3.2.5 provider chain + launcher 1.9.3 (from a committed Jenkins m2)
D=repository/org/apache/maven/surefire/surefire-junit-platform/3.2.5; mkdir -p $D
gh api "repos/khahv/test-jenkins-library/git/blobs/23473fd2b2b43ea565b9384102e039f362d61f6e" --jq .content | base64 -d > $D/surefire-junit-platform-3.2.5.jar
gh api "repos/khahv/test-jenkins-library/git/blobs/0fba0051628816d28d0aa63ba3e800e4071dfb51" --jq .content | base64 -d > $D/surefire-junit-platform-3.2.5.pom
D=repository/org/apache/maven/surefire/surefire-providers/3.2.5; mkdir -p $D
gh api "repos/khahv/test-jenkins-library/git/blobs/953a8f85f1049a17e07c60ee8b2dd5b2d4d04729" --jq .content | base64 -d > $D/surefire-providers-3.2.5.pom
D=repository/org/apache/maven/surefire/common-java5/3.2.5; mkdir -p $D
gh api "repos/khahv/test-jenkins-library/git/blobs/eda1b3b4ac27b88bf005dc61bf9c161d97bfdaea" --jq .content | base64 -d > $D/common-java5-3.2.5.jar
gh api "repos/khahv/test-jenkins-library/git/blobs/9ecb32bad5c2f773485881d60856a154e245ab79" --jq .content | base64 -d > $D/common-java5-3.2.5.pom
D=repository/org/junit/platform/junit-platform-launcher/1.9.3; mkdir -p $D
gh api "repos/khahv/test-jenkins-library/git/blobs/5e556e44585648ffd5590a2c6c049f75e42773ad" --jq .content | base64 -d > $D/junit-platform-launcher-1.9.3.jar
gh api "repos/khahv/test-jenkins-library/git/blobs/3f08b2458f57daab4785d710720660569931cf97" --jq .content | base64 -d > $D/junit-platform-launcher-1.9.3.pom

cat > env.sh <<'ENV'
export TOOLCHAIN=$HOME/.cache/toolchain
export JAVA_HOME=$TOOLCHAIN/linux-x86
export PATH=$JAVA_HOME/bin:$PATH
export M2REPO=$TOOLCHAIN/repository
export MAVEN_HOME=$TOOLCHAIN/wrapper/dists/apache-maven-3.9.7-bin/3k9n615lchs6mp84v355m633uo/apache-maven-3.9.7
export PATH=$MAVEN_HOME/bin:$PATH
export MVN_OFFLINE="-o -Dmaven.repo.local=$M2REPO"
ENV

# 6. Python helper: pgserver (real PostgreSQL binaries)
pip install --user --break-system-packages pgserver >/dev/null 2>&1 || true

echo "Toolchain ready."
