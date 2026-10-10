#!/usr/bin/env python3
"""Run the JVM tests of the libraries on another JDK, such as the Java 11 they target.

The Kotlin Toolchain runs tests on JDK 17 or later only. This script runs the test classes that
`./kotlin build -p jvm` compiled with JUnit 4 on the JDK you name. It builds the classpath from
`./kotlin show dependencies` and the compiled class directories.

  tools/java11_tests.py --java "$JAVA_HOME_11_X64/bin/java" [module...]

Without modules it runs imagekodec, imagekodec-compose and imagekodec-coil. It exits with an
error when a test fails.
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")
CLASSES = ROOT / "build/artifacts/CompiledJvmArtifact"
MODULES = ["imagekodec", "imagekodec-compose", "imagekodec-coil"]
# JVM arguments of each module's tests, as test-settings@jvm sets them in its module.yaml.
JVM_ARGS = {"imagekodec": ["-Xmx3g"]}
COORDINATE = re.compile(r"^([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?(?=$| )")


def dependencies(module):
    """The local modules and the jars of the module's JVM test runtime classpath."""
    tree = subprocess.run(
        ["./kotlin", "show", "dependencies", "-m", module, "-p", "jvm", "--include-tests", "--scope", "runtime"],
        cwd=ROOT, check=True, text=True, stdout=subprocess.PIPE).stdout
    local = set(re.findall(r"─── Module (\S+)", tree))
    jars = {}
    for line in tree.splitlines():
        if "─── " not in line:
            continue
        # A direct dependency line starts with "<module>:<fragment>:"; the line under it names the artifact.
        match = COORDINATE.match(line.rsplit("─── ", 1)[1])
        if not match:
            continue
        group, artifact, declared, resolved = match.groups()
        version = resolved or declared
        folder = CACHE / ".m2.cache" / group.replace(".", "/") / artifact / version
        jar = folder / f"{artifact}-{version}.jar"
        # A KMP root artifact or a BOM has no jar of its own.
        if jar.exists():
            jars[f"{group}:{artifact}"] = jar
        elif not folder.is_dir():
            raise RuntimeError(f"{group}:{artifact}:{version} is not in {CACHE / '.m2.cache'}")
    return local, list(jars.values())


def test_classes(module):
    """Top-level classes of the module's test output whose names end in Test."""
    output = CLASSES / f"{module}jvmTest/kotlin-output"
    if not output.is_dir():
        raise RuntimeError(f"No test classes at {output}. Run `./kotlin build -p jvm` first.")
    return sorted(
        str(c.relative_to(output).with_suffix("")).replace("/", ".")
        for c in output.rglob("*Test.class") if "$" not in c.name)


def run(module, java):
    local, jars = dependencies(module)
    classpath = [CLASSES / f"{module}jvmTest/kotlin-output", CLASSES / f"{module}jvm/kotlin-output"]
    classpath += [CLASSES / f"{m}jvm/kotlin-output" for m in sorted(local) if m != module]
    classpath += jars
    command = [java, *JVM_ARGS.get(module, []), "-cp", ":".join(str(p) for p in classpath),
               "org.junit.runner.JUnitCore", *test_classes(module)]
    print(f"Running the {module} tests on {java}", flush=True)
    return subprocess.run(command, cwd=ROOT / module).returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True, help="the java command of the JDK to test on")
    parser.add_argument("modules", nargs="*", default=MODULES)
    args = parser.parse_args()
    failed = [m for m in args.modules if run(m, args.java) != 0]
    if failed:
        print(f"Tests failed in {', '.join(failed)}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
