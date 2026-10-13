#!/usr/bin/env python3
# Run a command under a seccomp filter that imitates the Android app sandbox's two relevant behaviours:
#   openat2 (aarch64 nr 437) -> SIGSYS (kills the process)
#   linkat  (aarch64 nr 37)  -> EACCES (hard links are refused, like SELinux does for app data files)
#   ioctl FICLONE (reflink copy) -> EACCES (SELinux refuses it for app data files; measured on S21)
# Environment OA_TRAP_LINKAT=none|EACCES|EPERM selects the linkat answer (default EACCES);
# OA_TRAP_OPENAT2=sigsys|none (default sigsys); OA_TRAP_FICLONE=EACCES|none (default EACCES).
# Test helper of tests/openclaw-smoke (not part of the product). Linux aarch64 only: the filter checks the aarch64
# system call numbers and lets every other architecture through unfiltered. Run it inside a container, never on a
# machine whose own processes should keep their hard links.
# Usage: trap_android.py cmd args...
import ctypes, os, sys
libc = ctypes.CDLL(None, use_errno=True)
class SockFilter(ctypes.Structure):
    _fields_ = [("code", ctypes.c_uint16), ("jt", ctypes.c_uint8), ("jf", ctypes.c_uint8), ("k", ctypes.c_uint32)]
class SockFprog(ctypes.Structure):
    _fields_ = [("len", ctypes.c_uint16), ("filter", ctypes.POINTER(SockFilter))]
ld = os.environ.get("OA_TRAP_LINKAT", "EACCES"); o2 = os.environ.get("OA_TRAP_OPENAT2", "sigsys")
err = {"EACCES": 13, "EPERM": 1}.get(ld)
prog = [(0x20,0,0,4),(0x15,0,0,0xC00000B7) ]   # load arch; if not aarch64 -> next is allow via jump below
# classic BPF with explicit jumps: build list then fix up
insn = []
def add(c,jt,jf,k): insn.append((c,jt,jf,k))
add(0x20,0,0,4)               # ld arch
add(0x15,1,0,0xC00000B7)      # arch == aarch64 ? skip allow-return : fallthrough
add(0x06,0,0,0x7fff0000)      # ALLOW (other arch)
add(0x20,0,0,0)               # ld syscall nr
if o2 == "sigsys":
    add(0x15,0,1,437); add(0x06,0,0,0x00030000)   # openat2 -> TRAP(SIGSYS)
if err:
    add(0x15,0,1,37);  add(0x06,0,0,0x00050000 | err)  # linkat -> ERRNO
if os.environ.get("OA_TRAP_FICLONE", "EACCES") != "none":
    add(0x15,0,3,29); add(0x20,0,0,24); add(0x15,0,1,0x40049409); add(0x06,0,0,0x00050000 | 13)   # ioctl(fd, FICLONE) -> EACCES
add(0x06,0,0,0x7fff0000)
arr = (SockFilter * len(insn))(*[SockFilter(*p) for p in insn])
fprog = SockFprog(len(insn), arr)
if libc.prctl(38, 1, 0, 0, 0) != 0: sys.exit("no_new_privs failed")
if libc.prctl(22, 2, ctypes.byref(fprog), 0, 0) != 0: sys.exit("seccomp failed errno=%d" % ctypes.get_errno())
os.execvp(sys.argv[1], sys.argv[1:])
