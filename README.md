# SD & SDPP

## Android files and storage

The project workspace is stored in the app-specific external files directory:

`Android/data/<applicationId>/files/SdIDE/active_project`

The current application ID is `com.aistudio.sdide.kvnqmp`. This keeps the app
install-compatible with the existing APK. Android grants the app access to this
directory without broad storage permissions; Android's file manager may restrict
direct browsing of `Android/data`, so use the IDE's file manager or Android's
document picker when needed. Existing projects in the app's internal
`sd_workspace/active_project` are copied there on first launch.

From the top-right menu, the IDE can import source files (`.sd`, `.sdpp`,
`.sdall`, or `.txt`) and export the open source file using Android's document
picker. The **Quản lý bộ nhớ và tệp dữ liệu** screen manages files used by the
language file APIs: create, import, export, and delete. Source tabs continue to
support creating and deleting source files.

## SdIDE bytecode export

The bytecode export validates and tokenizes the selected entry file, then writes
a versioned binary token stream. `.sdbin` starts with `SDB1`; `.sdppbin` starts
with `SPP1`. Each file contains a format version, token count, and token type,
source position, newline flag, and UTF-8 lexeme for each token. This is a
portable SdIDE bytecode container, not native CPU machine code or an executable
binary. The Windows desktop app also has an **AOT qua C99** action: it emits
C99 and invokes an installed GCC/Clang compiler to create a host
executable under `build/native/`. Install GCC or Clang and make it available on
`PATH`, or set `SD_CC` to the compiler executable. The generated executable
targets the current desktop host; it is not an Android binary or a
`.sdbin`/`.sdppbin` file. Native compilation currently excludes imported
user-source modules. Android continues to run Sd/Sd++ in the interpreter.

## Internal Sd/Sd++ virtual machine

When running a single-file Sd or Sd++ program without active breakpoints, the
IDE compiles supported code to an internal stack-based bytecode VM. It runs in
the app and does not invoke GCC, Clang, Rust, or a network service. The current
VM subset includes integer/boolean variables and arithmetic, branches,
`for`/`while`, `brk`/`nxt`, and simple output. Programs using unsupported
features or active debugging automatically fall back to the existing
interpreter. The VM bytecode is currently an in-memory execution format; the
`.sdbin`/`.sdppbin` export remains the separate token-stream format described
above. This VM is portable, but it is not native machine code. Both Android and the
Windows desktop app use the VM where supported and fall back to the interpreter
for unsupported language features or active debugging.

Interpreter heap bookkeeping is bounded: at most 8,192 live heap blocks and 256
recently freed blocks are retained for debugger history. Freed block contents
are released immediately. Interpreter recursion is capped at 128 calls to
produce a language diagnostic before exhausting the device stack. Static
diagnostics run away from Android's UI thread, and stale results are discarded
when the project changes during analysis.

The Sd runtime incrementally sweeps a bounded batch every 64 heap allocations
and runs a full mark-and-sweep every 1,024 allocations, under allocation
pressure, at program exit, or on `sys.gc()`. Reachable pointers in active scopes,
globals, registers, arrays, structs, and heap blocks are retained. The register
bank now provides 224 simulated registers (`0Ax` through `15Nx`); these remain
virtual registers and do not access the device CPU.

The Android workbench's Terminal tab accepts local IDE commands (`help`, `files`,
`open`, `run`, `check`, `libs`, `pwd`, `clear`, and `stop`). It does not execute
arbitrary Android/Linux shell commands.

## Offline Sd / Sd++ libraries

On Android, open the top-right menu and choose **Thư viện Sd / Sd++**. The built-in
`ioprt`, `math`, `str`, `mem`, `file`, `sys`, and `draw` APIs are already part
of the interpreter. Install or remove additional source modules from `.sdlib`
files; installed modules are available to both live diagnostics and program
execution with `imp*module.ed` (Sd) or `imp*module.$` (Sd++).

A `.sdlib` is a ZIP archive containing `manifest.properties` and source files
under `src/`. Example manifest:

```properties
format=1
name=collections
version=1.0.0
description=Collection helpers for Sd
entry=collections.sd
```

The manifest `entry` names a source file in `src/`; the file basename is the
module name used by `imp*`. Optional helper modules can be included alongside
the entry file. Packages are installed locally without network access, limited
to 8 MiB, 32 source files, and 1 MiB per source file. Duplicate module names
across installed packages are rejected. Only `.sd` and `.sdpp` source files are
accepted. This milestone does not add C/C++ or Rust native-library loading or an
online registry.

The desktop IDE can create a `.sdlib` from the `.sd` and `.sdpp` files in the
open project using **Thư viện → Đóng gói dự án hiện tại**. Choose the package
name, version, description, entry module, and output path; the package is
validated against the same limits and its modules are imported through the
Sd/Sd++ front end before it is written. The Windows app can install and remove
local `.sdlib` packages and use their modules in execution and bytecode export.
The Android IDE can also build a `.sdlib` from the current project's `.sd` and
`.sdpp` files, save it through Android's document picker, then install or share
it from **Thư viện Sd / Sd++**.

The sample package [`examples/mathplus.sdlib`](./examples/mathplus.sdlib)
installs a `mathplus` module with `square(int)`. After installing it, use
`imp*mathplus.ed` and call `mathplus.square(7)`.

## Windows desktop version

The Windows desktop app shares the Sd engine and source-file formats with the
Android app. It supports project and source import/export, creating and deleting
source files, bytecode export, local library install/uninstall/package creation,
project data-file management, curriculum samples, and a C99 output viewer. Its
debugger provides breakpoints, step-into, step-over, continue, and stop; editor
font size and light/dark colors are configurable.

The desktop editor also includes offline C99/JavaScript/Python/Kotlin code
generation, with unsupported features reported explicitly, and a source
optimizer that shows a diff before applying changes. `Ctrl+Z`/`Ctrl+Y` undo and
redo editor changes, including an applied optimization. The offline coding
agent explains compiler diagnostics and safe local suggestions without sending
source to a remote service.

The library manager accepts local `.sdlib` packages and public GitHub Release
asset URLs ending in `.sdlib`. Downloads require HTTPS, are size-limited, and
are validated and compiled before installation; private repositories are not
accessed. The runtime exposes only its managed Sd heap and project data APIs;
it does not read or write memory belonging to other processes or the operating
system.

Windows also has **Nâng cao…** settings for editor font family, tab width, and
word wrapping, plus an optional GCC/Clang executable path and AOT optimization
level (`-O0`, `-O2`, or `-O3`). Leaving the compiler path blank uses `SD_CC` or
searches `PATH`; settings are saved for the current Windows user.

The Android IDE's **Sinh mã và tối ưu ngoại tuyến** tool can generate C99,
JavaScript, Python, or Kotlin from the entry program. JavaScript, Python, and
Kotlin support scalar variables and parameters, functions, conditionals, loops,
printing, and selected `math`/`str` calls; unsupported Sd features are reported
instead of silently omitted. Its source optimizer only proposes identity
arithmetic simplifications for declared integer variables; review the
line-by-line diff before applying. Applying a change uses the editor's undo
history. This is source generation, not a direct binary compiler.

Normal desktop execution skips unused C99 generation and uses the internal VM
where supported, falling back to the interpreter otherwise. Debugging uses the
interpreter. The desktop **AOT qua C99** action still invokes installed
GCC/Clang, and currently cannot compile programs that import user-source
modules. This is not a self-hosted compiler or direct machine-code backend.

To create a Windows installer, run the **Build Sd IDE Windows installer**
workflow on GitHub Actions and download the `SD-and-SDPP-Windows-Installer`
artifact.
The installer is built on Windows and includes its Java runtime. The Windows
package is currently version 1.1.0; native AOT still uses an installed
GCC/Clang compiler and is not yet a self-hosted Sd compiler.

The standalone Android debug build uses the app name **SD & SDPP** and package
ID `com.aistudio.sdide.kvnqmp.standalone`, so it can be installed alongside the
existing app. It has separate app data; projects and libraries are not copied
from the existing installation.

To move a project between Android and Windows, copy its source files and
`.sdide_entry` marker into a folder, then open that folder in the Windows app.
The Android project is stored under
`Android/data/<applicationId>/files/SdIDE/active_project`; connect the device
to a computer and copy the project files from there. Both apps use the same
Sd source formats, but this is file-based sharing, not live network
collaboration.
