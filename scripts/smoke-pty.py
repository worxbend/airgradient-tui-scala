#!/usr/bin/env python3
"""Exercise the actual packaged app in a controlling PTY and prove terminal modes are restored."""
import argparse
import codecs
import fcntl
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

env = dict(os.environ, TERM="xterm-256color", COLORTERM="truecolor", LC_ALL="C.UTF-8")
process = subprocess.Popen(command, stdin=slave, stdout=slave, stderr=slave,
                           preexec_fn=control_terminal, env=env, close_fds=True)
transcript = bytearray()
screen = pyte.Screen(120, 30)
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

try:
    expect(b"AIRGRADIENT")
    os.write(master, b"h")
    expect(b"HISTORY")
    os.write(master, b"\x1b")
    time.sleep(0.15)
    os.write(master, b"c")
    expect(b"SETTINGS")
    os.write(master, b"\x1b")
    time.sleep(0.15)
    os.write(master, b"q")
    assert process.wait(timeout=10) == 0
    after = termios.tcgetattr(slave)
    assert before == after, "Application did not restore the original terminal settings"
    print("Real PTY: dashboard, history, settings, clean quit, terminal restoration passed")
finally:
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)
    os.close(master)
    os.close(slave)
