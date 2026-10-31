"""Compile the real Kotlin prompt/wire code and keep private artifacts out of Git."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import urllib.request

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[5]
SOURCE = HERE.parents[1] / "main/java/io/deepseekharness/mobile/companion"
FILES = ["CompanionContext.kt", "CompanionPrompt.kt", "PersonaVoicePrompt.kt", "GeminiWire.kt", "chat/QqFaces.kt"]
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(value if isinstance(value, bytes) else canonical(value).encode()).hexdigest()


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, name = tempfile.mkstemp(prefix=".write-", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def private_run(name):
    import re
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", name):
        raise ValueError("run 名称只能包含字母、数字、点、横线和下划线")
    root = PROJECT / "persona-eval-output"
    # Resolve symlinks before checking: a preexisting symlink must not redirect private writes.
    if root.resolve() != root.absolute() or (root / name).resolve() != (root / name).absolute():
        raise ValueError("私有输出目录不能是符号链接")
    relative = root.relative_to(PROJECT).as_posix() + "/privacy-check.json"
    check = subprocess.run(["git", "-C", str(PROJECT), "check-ignore", "-q", relative])
    tracked = subprocess.check_output(["git", "-C", str(PROJECT), "ls-files", "--", str(root)], text=True)
    if check.returncode != 0 or tracked.strip():
        raise ValueError("输出目录必须已被 Git 忽略且没有已跟踪文件")
    target = root / name
    target.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    target.chmod(0o700)
    return target


def code_hashes():
    files = ([SOURCE / f for f in FILES] + sorted(HERE.glob("*.py")) + sorted(HERE.glob("*.kt"))
             + sorted(HERE.glob("*.mjs")) + [HERE / "rubric.json", PROJECT / "source/src/companion/characterCard.ts"])
    return {str(p.relative_to(PROJECT)): digest(p.read_bytes()) for p in files}


class Bridge:
    def __init__(self, gradle, directory):
        gradle = Path(gradle)
        libs = gradle / "lib"
        if not list(libs.glob("kotlin-compiler-embeddable-2.*.jar")):
            raise ValueError("请设置 GRADLE_HOME 为 Gradle 8.11.1 目录")
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        json_jar = directory / "json-20240303.jar"
        if not json_jar.exists():
            urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar", json_jar)
        if digest(json_jar.read_bytes()) != JSON_SHA256:
            raise ValueError("org.json 下载校验失败")
        stdlib = sorted(libs.glob("kotlin-stdlib-2.*.jar"))[-1]
        sources = [SOURCE / f for f in FILES] + [HERE / "PersonaPromptBridge.kt"]
        signature = digest({str(p): digest(p.read_bytes()) for p in sources} | {"stdlib": digest(stdlib.read_bytes())})
        jar = directory / (signature + ".jar")
        classpath = os.pathsep.join(map(str, [json_jar, stdlib]))
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        self.log = (directory / "bridge.log").open("a", encoding="utf-8")
        if not jar.exists():
            subprocess.run([java, "-cp", str(libs / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                            "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", classpath,
                            "-d", str(jar), *map(str, sources)], stdout=self.log, stderr=self.log, check=True)
        self.process = subprocess.Popen([java, "-Xmx512m", "-Dfile.encoding=UTF-8", "-Duser.timezone=UTC", "-cp",
                                         str(jar) + os.pathsep + classpath,
                                         "io.deepseekharness.mobile.companion.eval.PersonaPromptBridgeKt"],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log,
                                        text=True, encoding="utf-8", bufsize=1)
        self.lock = threading.Lock()

    def call(self, value):
        with self.lock:
            self.process.stdin.write(canonical(value) + "\n")
            self.process.stdin.flush()
            line = self.process.stdout.readline()
            if not line:
                raise ValueError("Kotlin 提示词桥接进程退出；检查私有 bridge.log")
            result = json.loads(line)
            if "error" in result:
                raise ValueError("Kotlin 提示词桥接拒绝输入：" + result.get("type", "unknown"))
            return result

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.log.close()
