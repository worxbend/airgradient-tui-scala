#!/usr/bin/env python3
"""Exercise the actual packaged app in a controlling PTY and prove terminal modes are restored."""
import argparse
import codecs
import fcntl
import json
import os
import select
import signal
import struct
import subprocess
import termios
import time
from pathlib import Path
import pyte

parser = argparse.ArgumentParser()
target = parser.add_mutually_exclusive_group(required=True)
target.add_argument("--jar", type=Path)
target.add_argument("--binary", type=Path)
args = parser.parse_args()
command = (["java", "--enable-native-access=ALL-UNNAMED", "-jar", str(args.jar.resolve())] if args.jar
           else [str(args.binary.resolve())]) + ["--demo"]
master, slave = os.openpty()
before = termios.tcgetattr(slave)
fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 30, 120, 0, 0))

def control_terminal():
    os.setsid()
    fcntl.ioctl(0, termios.TIOCSCTTY, 0)

# The parent test runner may set NO_COLOR; this PTY explicitly exercises truecolor output.
env = dict(os.environ, TERM="xterm-256color", COLORTERM="truecolor", CLICOLOR_FORCE="1", LC_ALL="C.UTF-8")
process = subprocess.Popen(command, stdin=slave, stdout=slave, stderr=slave,
                           preexec_fn=control_terminal, env=env, close_fds=True)
transcript = bytearray()
class Screen(pyte.Screen):
    """Glyphora probes the terminal at startup (CSI ? 996 n, CSI ? u). pyte 0.8.2 passes private=True to handlers
    that reject it, so ignore the private variants, as a terminal that does not support those queries would."""

    def report_device_status(self, mode, **kwargs):
        if not kwargs.get("private"):
            super().report_device_status(mode)

    def restore_cursor(self, **kwargs):
        if not kwargs.get("private"):
            super().restore_cursor()

screen = Screen(120, 30)
stream = pyte.Stream(screen)
decoder = codecs.getincrementaldecoder("utf-8")("replace")

def expect(token):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        ready, _, _ = select.select([master], [], [], 0.2)
        if ready:
            try:
                chunk = os.read(master, 65536)
            except OSError:
                chunk = b""
            transcript.extend(chunk)
            stream.feed(decoder.decode(chunk))
            if token.decode() in "\n".join(screen.display):
                return
        if process.poll() is not None:
            break
    raise AssertionError(f"Expected {token!r}; exit={process.poll()}\n" + "\n".join(screen.display))

def check_background(theme):
    # Finish receiving the frame: a title can arrive before the bottom rows in a PTY write.
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        ready, _, _ = select.select([master], [], [], 0.15)
        if not ready:
            break
        chunk = os.read(master, 65536)
        transcript.extend(chunk)
        stream.feed(decoder.decode(chunk))
    defaults = [(x, y) for y in range(screen.lines) for x in range(screen.columns)
                if screen.buffer[y][x].bg == "default"]
    if theme == "mono":
        assert defaults, "Mono must retain terminal-default backgrounds"
    else:
        assert not defaults, f"{theme}: terminal background leaks at {defaults[:8]}"

try:
    expect(b"q quit")
    check_background("aurora")
    os.write(master, b"h")
    expect(b"HISTORY")
    os.write(master, b"\x1b")
    time.sleep(0.15)
    os.write(master, b"c")
    expect(b"SETTINGS")
    themes = json.loads((Path(__file__).resolve().parents[1] / "docs/assets/themes.json").read_text())
    for theme in themes[1:] + themes[:1]:
        os.write(master, b"t")
        expect(f"Theme: {theme} /".encode())
        check_background(theme)
    os.write(master, b"\x1b")
    time.sleep(0.15)
    os.write(master, b"\x10")
    expect(b"Commands")
    check_background("aurora")
    os.write(master, b"paper")
    expect(b"Use theme paper")
    os.write(master, b"\r")
    expect(b"Theme: paper")
    os.write(master, b"\x10")
    expect(b"Commands")
    os.write(master, b"Inspect PM2.5 history")
    expect(b"Inspect PM2.5 history")
    os.write(master, b"\r")
    expect(b"HISTORY / PM2.5")
    os.write(master, b"\x1b")
    time.sleep(0.15)
    for width, height, token in ((48, 18, b"Particles >0.3"), (160, 44, b"REPORTING / recent samples")):
        screen.resize(lines=height, columns=width)
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))
        os.killpg(process.pid, signal.SIGWINCH)
        expect(token)
        check_background("paper")
    os.write(master, b"q")
    assert process.wait(timeout=10) == 0
    after = termios.tcgetattr(slave)
    assert before == after, "Application did not restore the original terminal settings"
    print(f"Real PTY: dashboard, history, settings, all {len(themes)} themes and backgrounds, command search, resize, clean quit, terminal restoration passed")
finally:
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)
    os.close(master)
    os.close(slave)
