"""Compile the production Kotlin prompt code into a JVM bridge; JSON helpers."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
import urllib.request

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[5]
SOURCE = HERE.parents[1] / "main/java/io/deepseekharness/mobile/companion"
FILES = ["CompanionContext.kt", "CompanionPrompt.kt", "PersonaVoicePrompt.kt", "GeminiWire.kt", "chat/QqFaces.kt"]
JSON_JAR = "https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar"
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
# Outputs contain the private persona and real replies; the directory is git-ignored.
OUTPUT = PROJECT / "persona-eval-output"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(value if isinstance(value, bytes) else canonical(value).encode()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def json_jar(directory):
    jar = Path(directory) / "json-20240303.jar"
    if not jar.exists():
        jar.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(JSON_JAR, jar)
    if digest(jar.read_bytes()) != JSON_SHA256:
        raise ValueError(f"{jar} 的 SHA-256 与 org.json 20240303 不符")
    return jar


class Bridge:
    """One JSON object per line in and out; a bridge error carries the Kotlin exception message."""

    def __init__(self, gradle, directory, sources, main_class, classpath=()):
        libs = Path(gradle) / "lib"
        if not list(libs.glob("kotlin-compiler-embeddable-2.*.jar")):
            raise ValueError(f"{libs} 里没有 kotlin-compiler-embeddable 2.x；GRADLE_HOME 应指向 Gradle 8.x 解压目录")
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        stdlib = sorted(libs.glob("kotlin-stdlib-2.*.jar"))[-1]
        cp = os.pathsep.join(map(str, [json_jar(directory), stdlib, *classpath]))
        jar = directory / (digest({str(p): digest(Path(p).read_bytes()) for p in sources} | {"cp": cp}) + ".jar")
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        self.log_path = directory / "bridge.log"
        self.log = self.log_path.open("a", encoding="utf-8")
        if not jar.exists():
            compiled = subprocess.run([java, "-cp", str(libs / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                                       "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", cp,
                                       "-d", str(jar), *map(str, sources)], stdout=self.log, stderr=self.log)
            if compiled.returncode:
                raise ValueError(f"Kotlin 编译失败，见 {self.log_path}")
        self.process = subprocess.Popen([java, "-Xmx512m", "-Dfile.encoding=UTF-8", "-Duser.timezone=UTC",
                                         "-cp", str(jar) + os.pathsep + cp, main_class],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log,
                                        text=True, encoding="utf-8", bufsize=1)
        self.lock = threading.Lock()

    def call(self, value):
        with self.lock:
            self.process.stdin.write(canonical(value) + "\n")
            self.process.stdin.flush()
            line = self.process.stdout.readline()
        if not line:
            raise ValueError(f"Kotlin 桥接进程退出，见 {self.log_path}")
        result = json.loads(line)
        if "error" in result:
            raise ValueError("Kotlin 桥接：" + result["error"])
        return result

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
        self.log.close()


def prompt_bridge(gradle, directory):
    sources = [SOURCE / f for f in FILES] + [HERE / "PersonaPromptBridge.kt"]
    return Bridge(gradle, directory, sources, "io.deepseekharness.mobile.companion.eval.PersonaPromptBridgeKt")
